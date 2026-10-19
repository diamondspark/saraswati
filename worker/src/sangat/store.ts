// All SQL for Sangat. Rooms, members, pending joins and suggestions are read
// from and written to storage on every message; nothing is kept in memory
// between messages, so a fresh instance over the same storage sees the same rooms.

import { fromBinary, toBinary } from "@bufbuild/protobuf";
import {
  RoomStateSchema,
  TrackInfoSchema,
  type RoomState,
  type TrackInfo,
} from "../gen/listentogether_pb";
import { EMPTY_ROOM_CLEANUP_MS, RECONNECT_GRACE_MS } from "./limits";
import { SANGAT_TABLES } from "./schema";

export interface Room {
  code: string;
  /** "" while the room has no host. */
  hostUserId: string;
  hostDeviceId: string;
  carMode: boolean;
  state: RoomState;
  createdAt: number;
  /** Set when the last member is gone; the room is then hidden and awaiting deletion. */
  emptySince: number | null;
  hostDisconnectedAt: number | null;
}

export interface Member {
  userId: string;
  roomCode: string;
  deviceId: string;
  username: string;
  sessionToken: string;
  isHost: boolean;
  connected: boolean;
  disconnectedAt: number | null;
}

export interface PendingJoin {
  roomCode: string;
  userId: string;
  username: string;
  deviceId: string;
}

export interface Suggestion {
  id: string;
  roomCode: string;
  fromUserId: string;
  fromUsername: string;
  track: TrackInfo;
  createdAt: number;
}

interface RoomRow extends Record<string, SqlStorageValue> {
  code: string;
  hostUserId: string;
  hostDeviceId: string;
  carMode: number;
  state: ArrayBuffer;
  createdAt: number;
  emptySince: number | null;
  hostDisconnectedAt: number | null;
}

interface MemberRow extends Record<string, SqlStorageValue> {
  userId: string;
  roomCode: string;
  deviceId: string;
  username: string;
  sessionToken: string;
  isHost: number;
  connected: number;
  disconnectedAt: number | null;
}

interface SuggestionRow extends Record<string, SqlStorageValue> {
  id: string;
  roomCode: string;
  fromUserId: string;
  fromUsername: string;
  track: ArrayBuffer;
  createdAt: number;
}

interface CodeRow extends Record<string, SqlStorageValue> {
  code: string;
}

const blob = (bytes: Uint8Array): ArrayBuffer =>
  bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;

const toRoom = (row: RoomRow): Room => ({
  code: row.code,
  hostUserId: row.hostUserId,
  hostDeviceId: row.hostDeviceId,
  carMode: row.carMode !== 0,
  state: fromBinary(RoomStateSchema, new Uint8Array(row.state)),
  createdAt: row.createdAt,
  emptySince: row.emptySince,
  hostDisconnectedAt: row.hostDisconnectedAt,
});

const toMember = (row: MemberRow): Member => ({
  userId: row.userId,
  roomCode: row.roomCode,
  deviceId: row.deviceId,
  username: row.username,
  sessionToken: row.sessionToken,
  isHost: row.isHost !== 0,
  connected: row.connected !== 0,
  disconnectedAt: row.disconnectedAt,
});

export class Store {
  constructor(private readonly storage: DurableObjectStorage) {}

  private get sql(): SqlStorage {
    return this.storage.sql;
  }

  /** Runs `fn` as one atomic unit: a throw undoes every write it made. */
  transaction<T>(fn: () => T): T {
    return this.storage.transactionSync(fn);
  }

  // Rooms

  /** The room under `code`, including one that has been emptied and awaits deletion. */
  getRoom(code: string): Room | null {
    const row = this.sql.exec<RoomRow>("SELECT * FROM sangat_rooms WHERE code = ?", code).toArray()[0];
    return row ? toRoom(row) : null;
  }

  /** The room under `code` unless it has been emptied. This is what a player can reach. */
  getOpenRoom(code: string): Room | null {
    const room = this.getRoom(code);
    return room && room.emptySince === null ? room : null;
  }

  openRooms(): Room[] {
    return this.sql
      .exec<RoomRow>("SELECT * FROM sangat_rooms WHERE emptySince IS NULL ORDER BY createdAt, rowid")
      .toArray()
      .map(toRoom);
  }

  openRoomCount(): number {
    return this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_rooms WHERE emptySince IS NULL")
      .one().n;
  }

  insertRoom(room: Room): void {
    this.sql.exec(
      `INSERT INTO sangat_rooms (code, hostUserId, hostDeviceId, carMode, state, createdAt, emptySince, hostDisconnectedAt)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      room.code,
      room.hostUserId,
      room.hostDeviceId,
      room.carMode ? 1 : 0,
      blob(toBinary(RoomStateSchema, room.state)),
      room.createdAt,
      room.emptySince,
      room.hostDisconnectedAt,
    );
  }

  saveRoom(room: Room): void {
    this.sql.exec(
      `UPDATE sangat_rooms
       SET hostUserId = ?, hostDeviceId = ?, carMode = ?, state = ?, emptySince = ?, hostDisconnectedAt = ?
       WHERE code = ?`,
      room.hostUserId,
      room.hostDeviceId,
      room.carMode ? 1 : 0,
      blob(toBinary(RoomStateSchema, room.state)),
      room.emptySince,
      room.hostDisconnectedAt,
      room.code,
    );
  }

  /** Deletes the room with its members, pending joins and suggestions. */
  deleteRoom(code: string): void {
    this.sql.exec("DELETE FROM sangat_suggestions WHERE roomCode = ?", code);
    this.sql.exec("DELETE FROM sangat_pending_joins WHERE roomCode = ?", code);
    this.sql.exec("DELETE FROM sangat_members WHERE roomCode = ?", code);
    this.sql.exec("DELETE FROM sangat_rooms WHERE code = ?", code);
  }

  /** Rooms past their cleanup deadline. */
  emptiedRoomsDue(now: number): string[] {
    return this.sql
      .exec<CodeRow>(
        "SELECT code FROM sangat_rooms WHERE emptySince IS NOT NULL AND emptySince + ? <= ?",
        EMPTY_ROOM_CLEANUP_MS,
        now,
      )
      .toArray()
      .map((row) => row.code);
  }

  /** Rooms nobody is in that were never marked empty. */
  roomsWithoutMembers(): string[] {
    return this.sql
      .exec<CodeRow>(
        `SELECT code FROM sangat_rooms r
         WHERE emptySince IS NULL
           AND NOT EXISTS (SELECT 1 FROM sangat_members m WHERE m.roomCode = r.code)`,
      )
      .toArray()
      .map((row) => row.code);
  }

  wipe(): void {
    for (const table of SANGAT_TABLES) this.sql.exec(`DELETE FROM ${table}`);
  }

  // Members

  member(userId: string): Member | null {
    const row = this.sql.exec<MemberRow>("SELECT * FROM sangat_members WHERE userId = ?", userId).toArray()[0];
    return row ? toMember(row) : null;
  }

  memberByToken(sessionToken: string): Member | null {
    const row = this.sql
      .exec<MemberRow>("SELECT * FROM sangat_members WHERE sessionToken = ?", sessionToken)
      .toArray()[0];
    return row ? toMember(row) : null;
  }

  /** Everyone in the room, in the order they joined. */
  members(roomCode: string): Member[] {
    return this.sql
      .exec<MemberRow>("SELECT * FROM sangat_members WHERE roomCode = ? ORDER BY rowid", roomCode)
      .toArray()
      .map(toMember);
  }

  connectedMembers(roomCode: string): Member[] {
    return this.sql
      .exec<MemberRow>(
        "SELECT * FROM sangat_members WHERE roomCode = ? AND connected = 1 ORDER BY rowid",
        roomCode,
      )
      .toArray()
      .map(toMember);
  }

  memberCount(roomCode: string): number {
    return this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_members WHERE roomCode = ?", roomCode)
      .one().n;
  }

  connectedCount(roomCode: string): number {
    return this.sql
      .exec<{ n: number }>(
        "SELECT COUNT(*) AS n FROM sangat_members WHERE roomCode = ? AND connected = 1",
        roomCode,
      )
      .one().n;
  }

  membersOfDevice(deviceId: string): Member[] {
    return this.sql
      .exec<MemberRow>("SELECT * FROM sangat_members WHERE deviceId = ? ORDER BY rowid", deviceId)
      .toArray()
      .map(toMember);
  }

  /** Every member marked connected, in any room. */
  allConnectedMembers(): Member[] {
    return this.sql
      .exec<MemberRow>("SELECT * FROM sangat_members WHERE connected = 1 ORDER BY rowid")
      .toArray()
      .map(toMember);
  }

  /** Disconnected members whose reconnect grace has run out, oldest first. */
  expiredMembers(now: number): Member[] {
    return this.sql
      .exec<MemberRow>(
        `SELECT * FROM sangat_members
         WHERE connected = 0 AND disconnectedAt IS NOT NULL AND disconnectedAt + ? <= ?
         ORDER BY disconnectedAt, rowid`,
        RECONNECT_GRACE_MS,
        now,
      )
      .toArray()
      .map(toMember);
  }

  insertMember(member: Member): void {
    this.sql.exec(
      `INSERT INTO sangat_members (userId, roomCode, deviceId, username, sessionToken, isHost, connected, disconnectedAt)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
      member.userId,
      member.roomCode,
      member.deviceId,
      member.username,
      member.sessionToken,
      member.isHost ? 1 : 0,
      member.connected ? 1 : 0,
      member.disconnectedAt,
    );
  }

  saveMember(member: Member): void {
    this.sql.exec(
      "UPDATE sangat_members SET isHost = ?, connected = ?, disconnectedAt = ? WHERE userId = ?",
      member.isHost ? 1 : 0,
      member.connected ? 1 : 0,
      member.disconnectedAt,
      member.userId,
    );
  }

  deleteMember(userId: string): void {
    this.sql.exec("DELETE FROM sangat_members WHERE userId = ?", userId);
  }

  // Pending joins

  pendingJoins(roomCode: string): PendingJoin[] {
    return this.sql
      .exec<PendingJoin & Record<string, SqlStorageValue>>(
        "SELECT roomCode, userId, username, deviceId FROM sangat_pending_joins WHERE roomCode = ? ORDER BY rowid",
        roomCode,
      )
      .toArray();
  }

  pendingJoin(roomCode: string, userId: string): PendingJoin | null {
    return (
      this.sql
        .exec<PendingJoin & Record<string, SqlStorageValue>>(
          "SELECT roomCode, userId, username, deviceId FROM sangat_pending_joins WHERE roomCode = ? AND userId = ?",
          roomCode,
          userId,
        )
        .toArray()[0] ?? null
    );
  }

  /** The join request this user (socket) has open, in whichever room. */
  pendingJoinOf(userId: string): PendingJoin | null {
    return (
      this.sql
        .exec<PendingJoin & Record<string, SqlStorageValue>>(
          "SELECT roomCode, userId, username, deviceId FROM sangat_pending_joins WHERE userId = ?",
          userId,
        )
        .toArray()[0] ?? null
    );
  }

  allPendingJoins(): PendingJoin[] {
    return this.sql
      .exec<PendingJoin & Record<string, SqlStorageValue>>(
        "SELECT roomCode, userId, username, deviceId FROM sangat_pending_joins ORDER BY rowid",
      )
      .toArray();
  }

  pendingCount(roomCode: string): number {
    return this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_pending_joins WHERE roomCode = ?", roomCode)
      .one().n;
  }

  insertPendingJoin(pending: PendingJoin): void {
    this.sql.exec(
      "INSERT INTO sangat_pending_joins (roomCode, userId, username, deviceId) VALUES (?, ?, ?, ?)",
      pending.roomCode,
      pending.userId,
      pending.username,
      pending.deviceId,
    );
  }

  deletePendingJoin(roomCode: string, userId: string): void {
    this.sql.exec("DELETE FROM sangat_pending_joins WHERE roomCode = ? AND userId = ?", roomCode, userId);
  }

  deletePendingJoinsOfUser(userId: string): void {
    this.sql.exec("DELETE FROM sangat_pending_joins WHERE userId = ?", userId);
  }

  deletePendingJoinsOfDevice(deviceId: string): void {
    this.sql.exec("DELETE FROM sangat_pending_joins WHERE deviceId = ?", deviceId);
  }

  deletePendingJoinsOfRoom(roomCode: string): void {
    this.sql.exec("DELETE FROM sangat_pending_joins WHERE roomCode = ?", roomCode);
  }

  // Suggestions

  suggestions(roomCode: string): Suggestion[] {
    return this.sql
      .exec<SuggestionRow>(
        "SELECT * FROM sangat_suggestions WHERE roomCode = ? ORDER BY createdAt, rowid",
        roomCode,
      )
      .toArray()
      .map((row) => ({
        id: row.id,
        roomCode: row.roomCode,
        fromUserId: row.fromUserId,
        fromUsername: row.fromUsername,
        track: fromBinary(TrackInfoSchema, new Uint8Array(row.track)),
        createdAt: row.createdAt,
      }));
  }

  suggestionCount(roomCode: string): number {
    return this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_suggestions WHERE roomCode = ?", roomCode)
      .one().n;
  }

  suggestionExists(id: string): boolean {
    return this.sql.exec("SELECT 1 FROM sangat_suggestions WHERE id = ?", id).toArray().length > 0;
  }

  insertSuggestion(suggestion: Suggestion): void {
    this.sql.exec(
      "INSERT INTO sangat_suggestions (id, roomCode, fromUserId, fromUsername, track, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
      suggestion.id,
      suggestion.roomCode,
      suggestion.fromUserId,
      suggestion.fromUsername,
      blob(toBinary(TrackInfoSchema, suggestion.track)),
      suggestion.createdAt,
    );
  }

  deleteSuggestionsOfRoom(roomCode: string): void {
    this.sql.exec("DELETE FROM sangat_suggestions WHERE roomCode = ?", roomCode);
  }

  // Deadlines

  /** The earliest moment something here expires, or null when nothing will. */
  nextDeadline(): number | null {
    return this.sql
      .exec<{ at: number | null }>(
        `SELECT MIN(at) AS at FROM (
           SELECT disconnectedAt + ? AS at FROM sangat_members
             WHERE connected = 0 AND disconnectedAt IS NOT NULL
           UNION ALL
           SELECT emptySince + ? AS at FROM sangat_rooms WHERE emptySince IS NOT NULL
         )`,
        RECONNECT_GRACE_MS,
        EMPTY_ROOM_CLEANUP_MS,
      )
      .one().at;
  }
}
