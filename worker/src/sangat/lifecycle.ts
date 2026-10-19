// Disconnects, reconnects and everything that expires: a port of metroserver's
// lifecycle.go. A phone that drops keeps its seat for RECONNECT_GRACE_MS; a room
// nobody is left in goes EMPTY_ROOM_CLEANUP_MS after that. Both deadlines live
// in storage and are served by the one DO alarm.

import {
  ReconnectedPayloadSchema,
  ReconnectPayloadSchema,
  SuggestionReceivedPayloadSchema,
  UserDisconnectedPayloadSchema,
  UserReconnectedPayloadSchema,
  JoinRequestPayloadSchema,
} from "../gen/listentogether_pb";
import { RECONNECT_GRACE_MS } from "./limits";
import {
  connectedIds,
  currentRoom,
  decode,
  emptyRoom,
  fail,
  liveState,
  markUserConnected,
  message,
  removeFromRoom,
  send,
  setHost,
  toAll,
} from "./roomOps";
import type { Member, Store } from "./store";
import type { Handler, Out, Runtime } from "./types";

const CLOSE_NORMAL = 1000;

export const isGraceOver = (disconnectedAt: number | null, now: number): boolean =>
  disconnectedAt !== null && now - disconnectedAt >= RECONNECT_GRACE_MS;

/**
 * The socket holding `userId` went away. The member keeps their seat, marked
 * disconnected, until the grace runs out or they reconnect.
 */
export function disconnectUser(rt: Runtime, userId: string): Out[] {
  const { store } = rt;
  store.deletePendingJoinsOfUser(userId);
  const member = store.member(userId);
  if (!member || !member.connected) return [];
  const room = store.getRoom(member.roomCode);
  if (!room || room.emptySince !== null) {
    store.deleteMember(userId);
    return [];
  }

  const now = rt.now();
  member.connected = false;
  member.disconnectedAt = now;
  store.saveMember(member);
  markUserConnected(room, userId, false);
  if (room.hostUserId === userId) room.hostDisconnectedAt = now;
  store.saveRoom(room);

  const notice = message(UserDisconnectedPayloadSchema, { userId, username: member.username });
  return toAll(connectedIds(store, room.code), "user_disconnected", notice);
}

/**
 * Members marked connected whose socket no longer exists, and join requests
 * from sockets that no longer exist. This is what an instance finds after a
 * restart or a redeploy dropped every socket without a close event; it treats
 * them as disconnects so their holders can reconnect.
 */
export function disconnectOrphans(rt: Runtime, liveUserIds: Set<string>): Out[] {
  const { store } = rt;
  const outs: Out[] = [];
  for (const member of store.allConnectedMembers()) {
    if (!liveUserIds.has(member.userId)) outs.push(...disconnectUser(rt, member.userId));
  }
  for (const pending of store.allPendingJoins()) {
    if (!liveUserIds.has(pending.userId)) store.deletePendingJoin(pending.roomCode, pending.userId);
  }
  return outs;
}

/** A member whose grace has run out leaves the room for good. */
export function expireMember(store: Store, member: Member, now: number): Out[] {
  const room = store.getRoom(member.roomCode);
  if (!room || room.emptySince !== null) {
    store.deleteMember(member.userId);
    return [];
  }
  return removeFromRoom(store, room, member, now);
}

/** Everything that is due at `now`: expired seats, then rooms past their cleanup. */
export function sweep(rt: Runtime, now: number): Out[] {
  const { store } = rt;
  const outs: Out[] = [];
  for (const member of store.expiredMembers(now)) outs.push(...expireMember(store, member, now));
  for (const code of store.roomsWithoutMembers()) {
    const room = store.getRoom(code);
    if (room) outs.push(...emptyRoom(store, room, now));
  }
  for (const code of store.emptiedRoomsDue(now)) store.deleteRoom(code);
  return outs;
}

export const handleReconnect: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(ReconnectPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid reconnect payload")];
  if (p.sessionToken === "") return [fail(me, "missing_session_token", "Session token is required")];
  if (currentRoom(store, me)) {
    return [fail(me, "already_in_room", "Leave the current room before reconnecting")];
  }
  if (store.pendingJoinOf(me)) {
    return [fail(me, "already_pending", "Cancel the pending join request before reconnecting")];
  }

  // A token only works from the device it was issued to.
  const member = store.memberByToken(p.sessionToken);
  if (!member || member.deviceId !== conn.att.deviceId) {
    return [fail(me, "session_not_found", "Session not found or expired")];
  }

  const now = rt.now();
  if (!member.connected && isGraceOver(member.disconnectedAt, now)) {
    return [fail(me, "session_expired", "Session has expired"), ...expireMember(store, member, now)];
  }
  const room = store.getOpenRoom(member.roomCode);
  if (!room) {
    store.deleteMember(member.userId);
    return [fail(me, "room_not_found", "Room no longer exists")];
  }

  // The seat may still be tied to an older socket of this device: a phone that
  // changed networks, or one the hub restarted under. The newest wins.
  if (member.connected) {
    rt.detachSocket(member.userId, CLOSE_NORMAL, "replaced by a newer connection");
  }

  conn.att.userId = member.userId;
  conn.att.started = true;
  conn.dirty = true;

  member.connected = true;
  member.disconnectedAt = null;
  store.saveMember(member);
  markUserConnected(room, member.userId, true);
  room.emptySince = null;
  if (room.hostUserId === member.userId) {
    room.hostDisconnectedAt = null;
  } else if (room.hostUserId === "" && room.state.hostId === "") {
    // A room left hostless by an expiry adopts the first member to come back.
    setHost(store, room, member);
  }
  store.saveRoom(room);

  const isHost = room.hostUserId === member.userId;
  const outs: Out[] = [
    send(member.userId, "reconnected", ReconnectedPayloadSchema, {
      roomCode: room.code,
      userId: member.userId,
      state: liveState(room.state, now),
      isHost,
    }),
  ];
  if (isHost) {
    for (const pending of store.pendingJoins(room.code)) {
      outs.push(
        send(member.userId, "join_request", JoinRequestPayloadSchema, {
          userId: pending.userId,
          username: pending.username,
        }),
      );
    }
    for (const suggestion of store.suggestions(room.code)) {
      outs.push(
        send(member.userId, "suggestion_received", SuggestionReceivedPayloadSchema, {
          suggestionId: suggestion.id,
          fromUserId: suggestion.fromUserId,
          fromUsername: suggestion.fromUsername,
          trackInfo: suggestion.track,
        }),
      );
    }
  }
  const back = message(UserReconnectedPayloadSchema, { userId: member.userId, username: member.username });
  outs.push(...toAll(connectedIds(store, room.code, member.userId), "user_reconnected", back));
  return outs;
};
