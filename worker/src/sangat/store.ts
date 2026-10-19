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
import { EMPTY_ROOM_CLEANUP_MS, MAX_QUEUE_STATE_BYTES, MAX_ROOM_STATE_BYTES, RECONNECT_GRACE_MS } from "./limits";
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

const toSuggestion = (row: SuggestionRow): Suggestion => ({
  id: row.id,
  roomCode: row.roomCode,
  fromUserId: row.fromUserId,
  fromUsername: row.fromUsername,
  track: fromBinary(TrackInfoSchema, new Uint8Array(row.track)),
  createdAt: row.createdAt,
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

  /**
   * Saves the room unless its serialized state is over MAX_ROOM_STATE_BYTES (a
   * row holds about 2 MB). Then nothing is written and the answer is false.
   */
  trySaveRoom(room: Room): boolean {
    return this.writeRoom(room, toBinary(RoomStateSchema, room.state));
  }

  /**
   * For changes that can add to the queue. They stop at MAX_QUEUE_STATE_BYTES, below
   * the cap, so that a member joining or reconnecting always has room; a change that
   * leaves the state no bigger than it was is never refused for size.
   */
  trySaveRoomGrowth(room: Room): boolean {
    const state = toBinary(RoomStateSchema, room.state);
    if (state.byteLength > MAX_QUEUE_STATE_BYTES) {
      const stored = this.sql
        .exec<{ n: number }>("SELECT length(state) AS n FROM sangat_rooms WHERE code = ?", room.code)
        .toArray()[0];
      if (!stored || state.byteLength > stored.n) return false;
    }
    return this.writeRoom(room, state);
  }

  private writeRoom(room: Room, state: Uint8Array): boolean {
    if (state.byteLength > MAX_ROOM_STATE_BYTES) return false;
    this.sql.exec(
      `UPDATE sangat_rooms
       SET hostUserId = ?, hostDeviceId = ?, carMode = ?, state = ?, emptySince = ?, hostDisconnectedAt = ?
       WHERE code = ?`,
      room.hostUserId,
      room.hostDeviceId,
      room.carMode ? 1 : 0,
      blob(state),
      room.emptySince,
      room.hostDisconnectedAt,
      room.code,
    );
    return true;
  }

  /** For changes that cannot grow the state (a host change, a flag); too large is then a bug, and throws. */
  saveRoom(room: Room): void {
    if (!this.trySaveRoom(room)) throw new Error("room state too large");
  }

  /**
   * Rooms the device hosts, or last hosted if they have since been emptied and
   * await deletion: the number its create_room is checked against.
   */
  roomsHostedBy(deviceId: string): number {
    return this.sql
      .exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_rooms WHERE hostDeviceId = ?", deviceId)
      .one().n;
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

  /** Disconnected members whose reconnect grace has run out, oldest first; of one room if `roomCode` is given. */
  expiredMembers(now: number, roomCode?: string): Member[] {
    return this.sql
      .exec<MemberRow>(
        `SELECT * FROM sangat_members
         WHERE connected = 0 AND disconnectedAt IS NOT NULL AND disconnectedAt + ? <= ?
           AND (? IS NULL OR roomCode = ?)
         ORDER BY disconnectedAt, rowid`,
        RECONNECT_GRACE_MS,
        now,
        roomCode ?? null,
        roomCode ?? null,
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
      .map(toSuggestion);
  }

  /** A pending suggestion of this room; one id from another room is not found. */
  suggestion(roomCode: string, id: string): Suggestion | null {
    const row = this.sql
      .exec<SuggestionRow>("SELECT * FROM sangat_suggestions WHERE roomCode = ? AND id = ?", roomCode, id)
      .toArray()[0];
    return row ? toSuggestion(row) : null;
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

  deleteSuggestion(id: string): void {
    this.sql.exec("DELETE FROM sangat_suggestions WHERE id = ?", id);
  }

  deleteSuggestionsOfUser(userId: string): void {
    this.sql.exec("DELETE FROM sangat_suggestions WHERE fromUserId = ?", userId);
  }

  /** The suggestions made by any seat this device holds, in any room. Run before the seats are removed. */
  deleteSuggestionsOfDevice(deviceId: string): void {
    this.sql.exec(
      "DELETE FROM sangat_suggestions WHERE fromUserId IN (SELECT userId FROM sangat_members WHERE deviceId = ?)",
      deviceId,
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
