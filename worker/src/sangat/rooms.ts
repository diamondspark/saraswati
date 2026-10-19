// create_room, join_room, approve_join, reject_join and leave_room: a port of
// the matching handlers in metroserver's rooms.go. Error codes and texts are
// metroserver's, word for word.

import { create } from "@bufbuild/protobuf";
import {
  ApproveJoinPayloadSchema,
  CreateRoomPayloadSchema,
  JoinApprovedPayloadSchema,
  JoinRejectedPayloadSchema,
  JoinRequestPayloadSchema,
  HostChangedPayloadSchema,
  JoinRoomPayloadSchema,
  KickedPayloadSchema,
  KickUserPayloadSchema,
  RejectJoinPayloadSchema,
  RoomCreatedPayloadSchema,
  RoomStateSchema,
  TransferHostPayloadSchema,
  UserJoinedPayloadSchema,
  UserInfoSchema,
} from "../gen/listentogether_pb";
import { newRoomCode, newSessionToken } from "./ids";
import {
  MAX_CLIENTS_PER_ROOM,
  MAX_PENDING_JOINS,
  MAX_ROOMS,
  MAX_ROOMS_PER_DEVICE,
  USERNAME_MAX,
} from "./limits";
import {
  addUser,
  connectedIds,
  connectedMemberOf,
  currentRoom,
  decode,
  fail,
  hostIsConnected,
  isActiveHost,
  liveState,
  message,
  removeFromRoom,
  send,
  setHost,
  toAll,
} from "./roomOps";
import { normalizeRoomCode, sanitizeString } from "./sanitize";
import type { Room } from "./store";
import type { Handler } from "./types";

const ROOM_CODE_ATTEMPTS = 100;
const KICK_REASON_MAX = 200;
const DEFAULT_KICK_REASON = "You have been kicked from the room";

export const handleCreateRoom: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(CreateRoomPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid create room payload")];
  if (p.username === "") return [fail(me, "missing_username", "Username is required")];
  if (currentRoom(store, me)) {
    return [fail(me, "already_in_room", "Leave the current room before creating another")];
  }
  if (store.pendingJoinOf(me)) {
    return [fail(me, "already_pending", "Cancel the pending join request before creating a room")];
  }
  const username = sanitizeString(p.username, USERNAME_MAX);
  if (username === "") return [fail(me, "invalid_username", "Username is invalid")];

  let code = "";
  for (let attempt = 0; attempt < ROOM_CODE_ATTEMPTS; attempt++) {
    const candidate = newRoomCode();
    // A room that has been emptied still holds its code until it is deleted.
    if (!store.getRoom(candidate)) {
      code = candidate;
      break;
    }
  }
  if (code === "") return [fail(me, "server_error", "Failed to create room")];
  if (store.openRoomCount() >= MAX_ROOMS) {
    return [fail(me, "room_limit_reached", "Server is at room capacity")];
  }
  if (store.roomsHostedBy(conn.att.deviceId) >= MAX_ROOMS_PER_DEVICE) {
    return [fail(me, "room_limit_reached", "This device is hosting as many rooms as it may")];
  }

  const now = rt.now();
  const sessionToken = newSessionToken();
  const room: Room = {
    code,
    hostUserId: me,
    hostDeviceId: conn.att.deviceId,
    carMode: false,
    state: create(RoomStateSchema, {
      roomCode: code,
      hostId: me,
      users: [create(UserInfoSchema, { userId: me, username, isHost: true, isConnected: true })],
      isPlaying: false,
      position: 0n,
      lastUpdate: BigInt(now),
      volume: 1,
      queue: [],
      revision: 0n,
    }),
    createdAt: now,
    emptySince: null,
    hostDisconnectedAt: null,
  };
  store.insertRoom(room);
  store.insertMember({
    userId: me,
    roomCode: code,
    deviceId: conn.att.deviceId,
    username,
    sessionToken,
    isHost: true,
    connected: true,
    disconnectedAt: null,
  });
  conn.att.started = true;
  conn.dirty = true;
  return [send(me, "room_created", RoomCreatedPayloadSchema, { roomCode: code, userId: me, sessionToken })];
};

export const handleJoinRoom: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(JoinRoomPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid join room payload")];
  if (p.username === "") return [fail(me, "missing_username", "Username is required")];
  if (currentRoom(store, me)) {
    return [fail(me, "already_in_room", "Leave the current room before joining another")];
  }
  if (store.pendingJoinOf(me)) {
    return [fail(me, "already_pending", "You already have a pending join request")];
  }
  const username = sanitizeString(p.username, USERNAME_MAX);
  if (username === "") return [fail(me, "invalid_username", "Username is invalid")];
  if (p.roomCode === "") return [fail(me, "missing_room_code", "Room code is required")];
  const code = normalizeRoomCode(p.roomCode);
  if (code === "") return [fail(me, "invalid_room_code", "Room code is invalid")];

  const room = store.getOpenRoom(code);
  if (!room) return [fail(me, "room_not_found", "Room not found")];
  if (store.connectedCount(code) >= MAX_CLIENTS_PER_ROOM) return [fail(me, "room_full", "Room is full")];
  if (store.pendingCount(code) >= MAX_PENDING_JOINS) {
    return [fail(me, "too_many_pending", "Too many pending join requests")];
  }
  if (room.state.hostId === "") return [fail(me, "room_invalid", "Room is no longer valid")];

  store.insertPendingJoin({ roomCode: code, userId: me, username, deviceId: conn.att.deviceId });
  conn.att.started = true;
  conn.dirty = true;
  // With the host away the request waits in storage; the host gets it on reconnect.
  if (!hostIsConnected(room)) return [];
  return [send(room.hostUserId, "join_request", JoinRequestPayloadSchema, { userId: me, username })];
};

export const handleApproveJoin: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(ApproveJoinPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid approve join payload")];
  if (p.userId === "") return [fail(me, "missing_user_id", "User ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) {
    return [fail(me, "not_host", "Only the host can approve join requests")];
  }
  const pending = store.pendingJoin(room.code, p.userId);
  if (!pending) return [fail(me, "join_request_not_found", "Join request not found")];
  if (store.connectedCount(room.code) >= MAX_CLIENTS_PER_ROOM) return [fail(me, "room_full", "Room is full")];

  const now = rt.now();
  const member = {
    userId: pending.userId,
    roomCode: room.code,
    deviceId: pending.deviceId,
    username: pending.username,
    sessionToken: newSessionToken(),
    isHost: false,
    connected: true,
    disconnectedAt: null,
  };
  store.deletePendingJoin(room.code, pending.userId);
  store.insertMember(member);
  addUser(room, member);
  store.saveRoom(room);

  const approval = send(member.userId, "join_approved", JoinApprovedPayloadSchema, {
    roomCode: room.code,
    userId: member.userId,
    sessionToken: member.sessionToken,
    state: liveState(room.state, now),
  });
  const joined = message(UserJoinedPayloadSchema, { userId: member.userId, username: member.username });
  return [approval, ...toAll(connectedIds(store, room.code, member.userId), "user_joined", joined)];
};

export const handleRejectJoin: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(RejectJoinPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid reject join payload")];
  if (p.userId === "") return [fail(me, "missing_user_id", "User ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) {
    return [fail(me, "not_host", "Only the host can reject join requests")];
  }
  const pending = store.pendingJoin(room.code, p.userId);
  if (!pending) return [fail(me, "join_request_not_found", "Join request not found")];

  store.deletePendingJoin(room.code, pending.userId);
  const reason = sanitizeString(p.reason, 200) || "Join request rejected by host";
  return [send(pending.userId, "join_rejected", JoinRejectedPayloadSchema, { reason })];
};

// leave_room has no reply of its own. Outside a room it only withdraws a
// pending join request, silently.
export const handleLeaveRoom: Handler = (rt, conn) => {
  const { store } = rt;
  const me = conn.att.userId;
  const current = currentRoom(store, me);
  if (!current) {
    store.deletePendingJoinsOfUser(me);
    return [];
  }
  return removeFromRoom(store, current.room, current.member, rt.now());
};

// Only a connected member can be kicked: a seat inside its reconnect grace is
// not among the room's clients, as in metroserver. The kicked member's seat and
// token are deleted; everyone who remains (the host included) hears user_left.
export const handleKickUser: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(KickUserPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid kick user payload")];
  if (p.userId === "") return [fail(me, "missing_user_id", "User ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) return [fail(me, "not_host", "Only the host can kick users")];
  if (p.userId === me) return [fail(me, "cannot_kick_self", "You cannot kick yourself")];
  const target = connectedMemberOf(store, room.code, p.userId);
  if (!target) return [fail(me, "user_not_found", "User not found in room")];

  const reason = sanitizeString(p.reason, KICK_REASON_MAX) || DEFAULT_KICK_REASON;
  return [
    send(target.userId, "kicked", KickedPayloadSchema, { reason }),
    ...removeFromRoom(store, room, target, rt.now()),
  ];
};

export const handleTransferHost: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(TransferHostPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid transfer host payload")];
  if (p.newHostId === "") return [fail(me, "missing_user_id", "New host user ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) return [fail(me, "not_host", "Only the host can transfer ownership")];
  if (p.newHostId === me) return [fail(me, "cannot_transfer_to_self", "You are already the host")];
  const target = connectedMemberOf(store, room.code, p.newHostId);
  if (!target) return [fail(me, "user_not_found", "Target user not found in room")];
  // A handover is one more room for the target's device to host, so the per-device cap
  // applies as it does to create_room. A device that already hosts this room gains none.
  if (target.deviceId !== room.hostDeviceId && store.roomsHostedBy(target.deviceId) >= MAX_ROOMS_PER_DEVICE) {
    return [fail(me, "room_limit_reached", "That device is hosting as many rooms as it may")];
  }

  setHost(store, room, target);
  store.saveRoom(room);
  const changed = message(HostChangedPayloadSchema, { newHostId: target.userId, newHostName: target.username });
  return toAll(connectedIds(store, room.code), "host_changed", changed);
};
