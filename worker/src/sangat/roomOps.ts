// Operations on rooms shared by the message handlers and the lifecycle code.
// All of them are synchronous: they read and write storage and return the
// messages to send, so one message's effect on a room is atomic.

import { clone, create, fromBinary, toBinary, type DescMessage, type MessageInitShape } from "@bufbuild/protobuf";
import {
  ErrorPayloadSchema,
  JoinRejectedPayloadSchema,
  RoomStateSchema,
  UserInfoSchema,
  HostChangedPayloadSchema,
  UserLeftPayloadSchema,
  type RoomState,
} from "../gen/listentogether_pb";
import type { Member, Room, Store } from "./store";
import type { Out } from "./types";

/** Serialized once, then addressed to as many sockets as needed. */
export function message<D extends DescMessage>(
  schema: D,
  init: MessageInitShape<D>,
): Uint8Array {
  return toBinary(schema, create(schema, init));
}

export function send<D extends DescMessage>(
  to: string,
  type: string,
  schema: D,
  init: MessageInitShape<D>,
): Out {
  return { to, type, payload: message(schema, init) };
}

export function fail(to: string, code: string, text: string): Out {
  return send(to, "error", ErrorPayloadSchema, { code, message: text });
}

/** Decodes a client payload; null when the bytes are not a valid message. */
export function decode<D extends DescMessage>(schema: D, bytes: Uint8Array) {
  try {
    return fromBinary(schema, bytes);
  } catch {
    return null;
  }
}

export function toAll(recipients: string[], type: string, payload: Uint8Array): Out[] {
  return recipients.map((to) => ({ to, type, payload }));
}

/** The user ids of the room's connected members, in join order. */
export function connectedIds(store: Store, roomCode: string, except?: string): string[] {
  return store
    .connectedMembers(roomCode)
    .map((m) => m.userId)
    .filter((id) => id !== except);
}

/** The room this user is a connected member of. */
export function currentRoom(store: Store, userId: string): { member: Member; room: Room } | null {
  const member = store.member(userId);
  if (!member || !member.connected) return null;
  const room = store.getOpenRoom(member.roomCode);
  return room ? { member, room } : null;
}

export function hostIsConnected(room: Room): boolean {
  return room.hostUserId !== "" && room.hostDisconnectedAt === null;
}

export function isActiveHost(room: Room, userId: string): boolean {
  return room.hostUserId === userId && room.hostDisconnectedAt === null;
}

// Where playback is now: the stored position plus the time since the last
// update while playing, never past the end of the track.
export function livePosition(state: RoomState, nowMs: bigint): bigint {
  let position = state.position < 0n ? 0n : state.position;
  if (state.isPlaying && state.lastUpdate > 0n) {
    const elapsed = nowMs - state.lastUpdate;
    if (elapsed > 0n) position += elapsed;
  }
  const duration = state.currentTrack?.duration ?? 0n;
  if (duration > 0n && position > duration) return duration;
  return position;
}

export function liveState(state: RoomState, now: number): RoomState {
  const copy = clone(RoomStateSchema, state);
  copy.position = livePosition(state, BigInt(now));
  copy.lastUpdate = BigInt(now);
  return copy;
}

/** The earliest-joined connected member (other than `except`), or null. */
export function firstEligibleHost(store: Store, roomCode: string, except?: string): Member | null {
  return store.connectedMembers(roomCode).find((m) => m.userId !== except) ?? null;
}

/**
 * Makes `host` the room's host (or leaves it hostless with null): the room row,
 * the member rows and the state's user list all change together. Car mode
 * belongs to the host's phone, so it is switched off when the host's device changes.
 */
export function setHost(store: Store, room: Room, host: Member | null): void {
  const hostUserId = host?.userId ?? "";
  const hostDeviceId = host?.deviceId ?? "";
  if (hostDeviceId !== room.hostDeviceId) room.carMode = false;
  room.hostUserId = hostUserId;
  room.hostDeviceId = hostDeviceId;
  room.hostDisconnectedAt = null;
  room.state.hostId = hostUserId;
  for (const user of room.state.users) user.isHost = user.userId === hostUserId;
  for (const member of store.members(room.code)) {
    const isHost = member.userId === hostUserId;
    if (member.isHost !== isHost) {
      member.isHost = isHost;
      store.saveMember(member);
    }
  }
}

export function addUser(room: Room, member: Member): void {
  room.state.users.push(
    create(UserInfoSchema, {
      userId: member.userId,
      username: member.username,
      isHost: member.isHost,
      isConnected: member.connected,
    }),
  );
}

export function markUserConnected(room: Room, userId: string, connected: boolean): void {
  const user = room.state.users.find((u) => u.userId === userId);
  if (user) user.isConnected = connected;
}

/**
 * Called when a room has no members left. It stays in storage, unreachable,
 * until the alarm deletes it; people still waiting to be let in are told so.
 */
export function emptyRoom(store: Store, room: Room, now: number): Out[] {
  const outs = store
    .pendingJoins(room.code)
    .map((p) => send(p.userId, "join_rejected", JoinRejectedPayloadSchema, { reason: "Room is no longer available" }));
  store.deletePendingJoinsOfRoom(room.code);
  store.deleteSuggestionsOfRoom(room.code);
  // The room keeps naming the device that last hosted it, so it counts toward that
  // device's cap until it is deleted.
  const lastHostDeviceId = room.hostDeviceId;
  setHost(store, room, null);
  room.hostDeviceId = lastHostDeviceId;
  room.emptySince = now;
  store.saveRoom(room);
  return outs;
}

/**
 * Takes a member out of their room for good (they left, their grace ran out, or
 * their phone was revoked) and tells everyone who remains. When the host goes,
 * the earliest-joined connected member takes over; with nobody connected the
 * room has no host until someone reconnects.
 */
export function removeFromRoom(store: Store, room: Room, member: Member, now: number): Out[] {
  const wasHost = member.isHost || room.hostUserId === member.userId;
  store.deleteMember(member.userId);
  // What they suggested goes with them: the host should not be asked to approve a pick from someone who is gone.
  store.deleteSuggestionsOfUser(member.userId);
  room.state.users = room.state.users.filter((u) => u.userId !== member.userId);

  if (store.memberCount(room.code) === 0) return emptyRoom(store, room, now);

  let newHost: Member | null = null;
  if (wasHost) {
    newHost = firstEligibleHost(store, room.code);
    setHost(store, room, newHost);
  }
  store.saveRoom(room);

  const left = message(UserLeftPayloadSchema, { userId: member.userId, username: member.username });
  const changed =
    wasHost && newHost
      ? message(HostChangedPayloadSchema, { newHostId: newHost.userId, newHostName: newHost.username })
      : null;
  const outs: Out[] = [];
  for (const to of connectedIds(store, room.code)) {
    outs.push({ to, type: "user_left", payload: left });
    if (changed) outs.push({ to, type: "host_changed", payload: changed });
  }
  return outs;
}

/** A member of this room who is connected, or null (a seat inside its reconnect grace is not one). */
export function connectedMemberOf(store: Store, roomCode: string, userId: string): Member | null {
  const member = store.member(userId);
  return member && member.connected && member.roomCode === roomCode ? member : null;
}
