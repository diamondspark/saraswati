// Sangat (listen together) inside the FamilyHub Durable Object.
//
// Sockets use the WebSocket Hibernation API, so the object can be evicted
// between messages: a socket's identity ({userId, deviceId, ...}), its rate-limit
// window and the time it was last heard from live in its serialized attachment,
// and every room, member, pending join and suggestion lives in SQLite. The only
// state kept in memory is a message queue, a per-socket throttle and a cache of
// the alarm time, all rebuildable.
//
// Every event that touches rooms (a message, a close, the alarm) runs through
// one promise chain. Decoding a gzipped message awaits, which opens the Durable
// Object's input gate; the chain keeps messages in the order they were sent,
// keeps room changes from interleaving, and means only one decode is in flight
// against the 128 MB limit. A handler itself is synchronous: it reads and
// writes storage inside one transaction and returns the messages to send.
//
// A few handlers need the SHA-256 of a session token (create_room and
// approve_join mint one, reconnect is handed one), and hashing awaits, so
// prepareHash computes it in the same promise chain, right after decode and
// before the socket's attachment is read. Reading it only once, after every
// await this method makes, means the handler always sees the freshest copy
// and a later write-back of it can never clobber rate-limit bookkeeping
// (lastSeen, rateCount, ...) that a message on the same socket recorded while
// decode or the hash was in flight; it is also how a device revoked while
// either was computing is caught before the handler runs.

import { create, toBinary } from "@bufbuild/protobuf";
import { EnvelopeSchema, ErrorPayloadSchema, ReconnectPayloadSchema } from "../gen/listentogether_pb";
import { CodecError, decodeEnvelope, encodeEnvelope } from "./codec";
import { handleClientCapabilities, handlePing } from "./connection";
import { newSessionToken, newUserId } from "./ids";
import { disconnectOrphans, disconnectUser, dueRooms, handleReconnect, sweepRoom } from "./lifecycle";
import {
  ALARM_RETRY_MS,
  CLOSE_GOING_AWAY,
  CLOSE_POLICY_VIOLATION,
  CLOSE_REASON_IDLE,
  MAX_PENDING_BYTES_PER_SOCKET,
  MAX_PENDING_BYTES_TOTAL,
  MAX_PENDING_MESSAGES_PER_SOCKET,
  MAX_READ_MESSAGE_BYTES,
  MAX_SOCKETS_PER_DEVICE,
  RATE_LIMITED_ERROR_CODE,
  RATE_LIMITED_MESSAGE,
  REQUEST_SYNC_MIN_INTERVAL_MS,
} from "./limits";
import { floodedTooLong, idleDeadline, recordMessage } from "./liveness";
import { handleBufferReady, handlePlaybackAction, handleRequestSync } from "./playback";
import { decode, fail, hostIsConnected, message, removeFromRoom } from "./roomOps";
import {
  handleApproveJoin,
  handleCreateRoom,
  handleJoinRoom,
  handleKickUser,
  handleLeaveRoom,
  handleRejectJoin,
  handleTransferHost,
} from "./rooms";
import { Store, type Room } from "./store";
import { handleApproveSuggestion, handleRejectSuggestion, handleSuggestTrack } from "./suggestions";
import type { Attachment, Conn, Handler, Out, PreHashed, Runtime, SangatHooks } from "./types";
import { sha256Hex } from "../secrets";

export type LiveRoom = {
  roomCode: string;
  hostName: string;
  hostDeviceId: string;
  carMode: boolean;
  memberCount: number;
  /** Epoch milliseconds. */
  startedAt: number;
  /** The song on now, for display only: no id, URL or thumbnail leaves with it. Null before the first song. */
  nowPlaying: { title: string; artist: string } | null;
};

const NOW_PLAYING_TITLE_MAX = 100;
const NOW_PLAYING_ARTIST_MAX = 60;

function nowPlayingOf(room: Room): LiveRoom["nowPlaying"] {
  const track = room.state.currentTrack;
  if (!track || track.title === "") return null;
  return { title: track.title.slice(0, NOW_PLAYING_TITLE_MAX), artist: track.artist.slice(0, NOW_PLAYING_ARTIST_MAX) };
}

const HANDLERS = new Map<string, Handler>([
  ["create_room", handleCreateRoom],
  ["join_room", handleJoinRoom],
  ["leave_room", handleLeaveRoom],
  ["approve_join", handleApproveJoin],
  ["reject_join", handleRejectJoin],
  ["playback_action", handlePlaybackAction],
  ["ping", handlePing],
  ["request_sync", handleRequestSync],
  ["reconnect", handleReconnect],
  ["suggest_track", handleSuggestTrack],
  ["approve_suggestion", handleApproveSuggestion],
  ["reject_suggestion", handleRejectSuggestion],
  ["buffer_ready", handleBufferReady],
  ["kick_user", handleKickUser],
  ["transfer_host", handleTransferHost],
  ["client_capabilities", handleClientCapabilities],
]);

const CLOSE_MESSAGE_TOO_BIG = 1009;
const CLOSE_INTERNAL_ERROR = 1011;

const encoder = new TextEncoder();
const WEBSOCKET_OPEN = 1;

// The answer to a message over the rate limit. It is small enough that it is never
// gzipped, so it can be sent on the spot instead of waiting in the queue.
const RATE_LIMITED_FRAME = toBinary(
  EnvelopeSchema,
  create(EnvelopeSchema, {
    type: "error",
    payload: message(ErrorPayloadSchema, { code: RATE_LIMITED_ERROR_CODE, message: RATE_LIMITED_MESSAGE }),
    compressed: false,
  }),
);

function safeClose(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason.slice(0, 120));
  } catch {
    // already closed
  }
}

export class Sangat {
  private readonly store: Store;
  private readonly runtime: Runtime;
  private tail: Promise<void> = Promise.resolve();
  private reconciled = false;
  // The alarm time as last set from here; undefined until first read from storage.
  private alarmAt: number | null | undefined;
  private readonly lastSyncAt = new Map<string, number>();
  // Messages each socket has in the queue, counted from arrival until handled, and the
  // bytes they hold: per socket and in all.
  private readonly waiting = new WeakMap<WebSocket, number>();
  private readonly waitingBytes = new WeakMap<WebSocket, number>();
  private totalWaitingBytes = 0;
  // Socket side effects a handler asked for; they run only once its changes are committed.
  private effects: (() => void)[] = [];

  constructor(
    private readonly ctx: DurableObjectState,
    private readonly hooks: SangatHooks = {},
  ) {
    this.store = new Store(ctx.storage);
    this.runtime = {
      store: this.store,
      now: () => Date.now(),
      detachSocket: (userId, code, reason) => {
        this.effects.push(() => this.detachSocket(userId, code, reason));
      },
      allowSyncResponse: (userId, now) => this.allowSyncResponse(userId, now),
      roomStarted: (hostDeviceId) => {
        this.effects.push(() => this.notifyHook(() => this.hooks.roomStarted?.(hostDeviceId)));
      },
      roomEnded: (hostDeviceId) => {
        this.effects.push(() => this.notifyHook(() => this.hooks.roomEnded?.(hostDeviceId)));
      },
    };
  }

  // A hook is the hub's business; whatever it does, it must not stop the effects after it or the flush.
  private notifyHook(call: () => void): void {
    try {
      call();
    } catch (error) {
      console.error("sangat: hook failed", errorName(error));
    }
  }

  // The hub's entry points

  /** Whether the device may open another socket: it has fewer than MAX_SOCKETS_PER_DEVICE live ones. */
  hasRoomForSocket(deviceId: string): boolean {
    let open = 0;
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WEBSOCKET_OPEN) continue;
      const att = this.attachmentOf(ws);
      if (att && !att.closed && att.deviceId === deviceId) open++;
    }
    return open < MAX_SOCKETS_PER_DEVICE;
  }

  /** Upgrades an authenticated device's request. The caller has already checked auth, the kill switch and hasRoomForSocket. */
  accept(deviceId: string): Response {
    this.reconcileNow();
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const attachment: Attachment = { userId: this.unusedUserId(), deviceId, lastSeen: Date.now() };
    server.serializeAttachment(attachment);
    // The new socket's idle deadline may now be the earliest one.
    this.announce([]);
    return new Response(null, { status: 101, webSocket: client });
  }

  onMessage(ws: WebSocket, data: ArrayBuffer | string): Promise<void> {
    // A string is at least as many bytes as it has UTF-16 units.
    const size = typeof data === "string" ? data.length : data.byteLength;
    if (size > MAX_READ_MESSAGE_BYTES) {
      safeClose(ws, CLOSE_MESSAGE_TOO_BIG, "message too big");
      return Promise.resolve();
    }
    const receivedAt = Date.now();
    // Before the queue, so a flood cannot pile up behind it holding 512 KB frames.
    // This runs synchronously, in arrival order, and stores its count in the
    // attachment, where a hibernated object finds it again.
    const att = this.attachmentOf(ws);
    if (att?.closed) {
      this.dropClosed(ws);
      return Promise.resolve();
    }
    if (att) {
      const allowed = recordMessage(att, receivedAt);
      ws.serializeAttachment(att);
      if (!allowed) {
        try {
          ws.send(RATE_LIMITED_FRAME);
        } catch {
          // the socket closed under us
        }
        // A burst is answered; a socket that keeps it up for several windows in a row is cut off.
        if (floodedTooLong(att)) this.cutOff(ws, att, "rate limited");
        return Promise.resolve();
      }
    }

    // A socket that has too many messages waiting, or too many bytes of them, is not
    // keeping up. So does the hub as a whole: 128 MB is all the memory there is. The
    // socket is closed and its member disconnected, which also frees the queue of it.
    // A string is counted at its worst case of 3 bytes per UTF-16 unit.
    const bytes = typeof data === "string" ? data.length * 3 : data.byteLength;
    const waiting = this.waiting.get(ws) ?? 0;
    const waitingBytes = this.waitingBytes.get(ws) ?? 0;
    if (
      waiting >= MAX_PENDING_MESSAGES_PER_SOCKET ||
      waitingBytes + bytes > MAX_PENDING_BYTES_PER_SOCKET ||
      this.totalWaitingBytes + bytes > MAX_PENDING_BYTES_TOTAL
    ) {
      this.cutOff(ws, att, "backpressure");
      return Promise.resolve();
    }
    this.waiting.set(ws, waiting + 1);
    this.waitingBytes.set(ws, waitingBytes + bytes);
    this.totalWaitingBytes += bytes;
    return this.enqueue(async () => {
      try {
        await this.handleMessage(ws, data, receivedAt);
      } finally {
        this.waiting.set(ws, (this.waiting.get(ws) ?? 1) - 1);
        this.waitingBytes.set(ws, (this.waitingBytes.get(ws) ?? bytes) - bytes);
        this.totalWaitingBytes -= bytes;
      }
    });
  }

  onClose(ws: WebSocket): Promise<void> {
    return this.enqueue(async () => {
      const attachment = this.attachmentOf(ws);
      if (!attachment) return;
      this.lastSyncAt.delete(attachment.userId);
      const outs = this.store.transaction(() => disconnectUser(this.runtime, attachment.userId));
      await this.flush(outs);
      await this.scheduleAlarm();
    });
  }

  onAlarm(): Promise<void> {
    return this.enqueue(async () => {
      this.alarmAt = null; // the alarm that just fired is spent
      const now = Date.now();
      let failed = false;
      try {
        // Silent sockets are cut off first; the orphan step below then finds their
        // members without a socket and disconnects them like any other drop.
        this.closeIdleSockets(now);
        const outs: Out[] = [];
        // Each piece of work is its own transaction: one that fails (a room whose
        // state cannot be read, say) must not undo the others' expiries.
        const attempt = (work: () => Out[]) => {
          try {
            outs.push(...this.store.transaction(work));
          } catch (error) {
            failed = true;
            console.error("sangat: alarm work failed", errorName(error));
          }
        };
        attempt(() => disconnectOrphans(this.runtime, this.liveUserIds()));
        let due: string[] = [];
        try {
          due = dueRooms(this.store, now);
        } catch (error) {
          failed = true;
          console.error("sangat: alarm lookup failed", errorName(error));
        }
        for (const code of due) attempt(() => sweepRoom(this.runtime, code, now));
        await this.flush(outs);
      } finally {
        // Whatever happened, the next deadline must still be armed. A deadline that
        // could not be served waits ALARM_RETRY_MS rather than firing in a loop.
        await this.scheduleAlarm(failed ? now : undefined);
      }
    });
  }

  /** Rooms whose host is connected, for GET /sangat/live. */
  liveRooms(): LiveRoom[] {
    this.reconcileNow();
    return this.store
      .openRooms()
      .filter(hostIsConnected)
      .map((room) => this.liveRoomOf(room));
  }

  /** One open room as GET /sangat/live lists it, whether or not its host is connected; null if there is none. */
  roomView(roomCode: string): LiveRoom | null {
    this.reconcileNow();
    const room = this.store.getOpenRoom(roomCode);
    return room ? this.liveRoomOf(room) : null;
  }

  private liveRoomOf(room: Room): LiveRoom {
    return {
      roomCode: room.code,
      hostName: this.store.member(room.hostUserId)?.username ?? "",
      hostDeviceId: room.hostDeviceId,
      carMode: room.carMode,
      memberCount: this.store.connectedCount(room.code),
      startedAt: room.createdAt,
      nowPlaying: nowPlayingOf(room),
    };
  }

  /** Car mode is the host device's to switch, for PUT /sangat/car. */
  setCarMode(roomCode: string, deviceId: string, carMode: boolean): "ok" | "not_found" | "not_host" {
    this.reconcileNow();
    const room = this.store.getOpenRoom(roomCode);
    if (!room) return "not_found";
    if (room.hostDeviceId !== deviceId) return "not_host";
    room.carMode = carMode;
    this.store.saveRoom(room);
    return "ok";
  }

  /**
   * Cuts a device off at once: its sockets close with `code`, it leaves every
   * room it was in (so its reconnect tokens die with its seats), and the rooms
   * it leaves behind are told.
   *
   * The sockets go first. Tombstoned, they make everything already queued from them
   * inert, so a revoked phone loses its authority even if the storage work below
   * fails (a room whose state cannot be read, say). Then its members stay marked
   * connected, and the close event and the grace expiry clean them up.
   *
   * The storage work is split so that one failure costs only its own part: the
   * device's join requests and suggestions, in every room, are one step, and each
   * seat's removal from its room is another.
   *
   * True when the device was the host of a room and its going ended that room (nobody left,
   * or a car room, which ends with its host); the caller can then tell the family.
   */
  closeDevice(deviceId: string, code: number, reason: string): boolean {
    // Members whose sockets died with an earlier instance are still marked connected;
    // settle that before a host is chosen from among them.
    this.reconcileNow();
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachmentOf(ws);
      if (att?.deviceId === deviceId) this.closeSocket(ws, att, code, reason);
    }
    const now = Date.now();
    const outs: Out[] = [];

    // What the phone asked for and suggested must not outlive it: the host should not be
    // asked to approve a revoked phone's pick. Plain deletes, so no room has to be readable.
    this.attempt("removing a closed device's join requests and suggestions", () =>
      this.store.transaction(() => {
        this.store.deletePendingJoinsOfDevice(deviceId);
        this.store.deleteSuggestionsOfDevice(deviceId);
      }),
    );

    const seats = this.attempt("listing a closed device's seats", () => this.store.membersOfDevice(deviceId)) ?? [];
    let endedARoom = false;
    for (const member of seats) {
      const removed = this.attempt("removing a closed device from a room", () =>
        this.store.transaction(() => {
          const room = this.store.getRoom(member.roomCode);
          if (!room || room.emptySince !== null) {
            this.store.deleteMember(member.userId);
            return { outs: [], ended: false };
          }
          const wasHost = member.isHost || room.hostUserId === member.userId;
          const outs = removeFromRoom(this.store, room, member, now);
          return { outs, ended: wasHost && room.emptySince !== null };
        }),
      );
      if (removed) {
        outs.push(...removed.outs);
        if (removed.ended) endedARoom = true;
      }
    }
    this.announce(outs);
    return endedARoom;
  }

  /** Runs one piece of storage work whose failure must not stop the rest: logs it and returns undefined. */
  private attempt<T>(what: string, work: () => T): T | undefined {
    try {
      return work();
    } catch (error) {
      console.error(`sangat: ${what} failed`, errorName(error));
      return undefined;
    }
  }

  /** Closes every socket with `code` and deletes every room. The sockets go first, as in closeDevice. */
  closeAll(code: number, reason: string): void {
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachmentOf(ws);
      if (att) this.closeSocket(ws, att, code, reason);
      else safeClose(ws, code, reason);
    }
    try {
      this.store.transaction(() => this.store.wipe());
    } catch (error) {
      console.error("sangat: clearing the rooms failed", errorName(error));
    }
    this.lastSyncAt.clear();
    this.announce([]);
  }

  // Serialization

  private enqueue<T>(job: () => Promise<T>): Promise<T> {
    const result = this.tail.then(() => {
      // After a restart or eviction, members whose sockets are gone are still
      // marked connected; the first job of the new instance sets that right.
      this.reconcileNow();
      return job();
    });
    // A failed job must not stop the ones behind it.
    this.tail = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  /** Sends `outs` and refreshes the alarm, in order with everything else. */
  private announce(outs: Out[]): void {
    this.enqueue(async () => {
      await this.flush(outs);
      await this.scheduleAlarm();
    }).catch((error) => console.error("sangat: announce failed", errorName(error)));
  }

  // Messages

  private async handleMessage(ws: WebSocket, data: ArrayBuffer | string, receivedAt: number): Promise<void> {
    if (this.dropClosed(ws)) return;
    const bytes = typeof data === "string" ? encoder.encode(data) : new Uint8Array(data);
    if (bytes.byteLength > MAX_READ_MESSAGE_BYTES) {
      safeClose(ws, CLOSE_MESSAGE_TOO_BIG, "message too big");
      return;
    }

    const decoded = await this.decode(bytes);
    // A few message types need the SHA-256 of a session token, and hashing
    // awaits; for the rest this resolves with {} and no further wait. Done
    // before the attachment below is read, not after, so that read is always
    // the freshest one: a write-back of a stale copy at the end of this
    // method would otherwise clobber rate-limit bookkeeping (lastSeen,
    // rateCount, ...) that a message on the same socket records while this
    // await is in flight.
    const pre = "error" in decoded ? {} : await this.prepareHash(decoded.type, decoded.payload);
    // The decode (and, for a few message types, the hash) may have waited a
    // while, and the server may have closed this socket meanwhile (revoked
    // device, takeover, switched off): then the message is dead and must not
    // act as the member it came from.
    const attachment = this.attachmentOf(ws);
    if (!attachment) {
      safeClose(ws, CLOSE_INTERNAL_ERROR, "no session");
      return;
    }
    if (attachment.closed) {
      this.dropClosed(ws);
      return;
    }
    const conn: Conn = { ws, att: attachment, dirty: false, receivedAt };
    const me = attachment.userId;

    let outs: Out[];
    let committed = false;
    if ("error" in decoded) {
      outs = [fail(me, "invalid_message", decoded.error)];
    } else {
      const handler = HANDLERS.get(decoded.type);
      if (!handler) {
        outs = [fail(me, "unknown_message_type", `Unknown message type: ${decoded.type}`)];
      } else {
        try {
          outs = this.store.transaction(() => handler(this.runtime, conn, decoded.payload, pre));
          committed = true;
        } catch (error) {
          console.error("sangat: handler failed", decoded.type, errorName(error));
          conn.dirty = false;
          outs = [fail(me, "server_error", "Internal error")];
        }
      }
    }
    // Side effects on other sockets (a takeover closes the older one) happen only
    // for a handler that committed, and before this socket takes the id over.
    const effects = this.effects.splice(0);
    if (committed) for (const effect of effects) effect();
    if (conn.dirty) ws.serializeAttachment(conn.att);
    await this.flush(outs);
    await this.scheduleAlarm();
  }

  /**
   * The SHA-256 a handler needs but cannot compute itself (hashing awaits, and
   * the handler's transaction cannot): a freshly minted token's hash for
   * create_room and approve_join, or the hash of the token a reconnect
   * presented. Empty for every other message type.
   */
  private async prepareHash(type: string, payload: Uint8Array): Promise<PreHashed> {
    if (type === "create_room" || type === "approve_join") {
      const token = newSessionToken();
      return { newToken: { token, hash: await sha256Hex(token) } };
    }
    if (type === "reconnect") {
      const p = decode(ReconnectPayloadSchema, payload);
      if (!p || p.sessionToken === "") return {};
      return { presentedTokenHash: await sha256Hex(p.sessionToken) };
    }
    return {};
  }

  private async decode(
    bytes: Uint8Array,
  ): Promise<{ type: string; payload: Uint8Array } | { error: string }> {
    if (bytes.byteLength === 0) return { error: "Invalid message format" };
    try {
      return await decodeEnvelope(bytes);
    } catch (error) {
      if (!(error instanceof CodecError)) throw error;
      // metroserver words an envelope with no type differently from the rest.
      return { error: error.kind === "empty_type" ? "Message type is required" : "Invalid message format" };
    }
  }

  // Sockets

  private attachmentOf(ws: WebSocket): Attachment | null {
    const value: unknown = ws.deserializeAttachment();
    if (typeof value !== "object" || value === null) return null;
    const { userId, deviceId } = value as Partial<Attachment>;
    return typeof userId === "string" && typeof deviceId === "string" ? (value as Attachment) : null;
  }

  private liveSockets(): Map<string, { ws: WebSocket; att: Attachment }> {
    const sockets = new Map<string, { ws: WebSocket; att: Attachment }>();
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachmentOf(ws);
      if (att) sockets.set(att.userId, { ws, att });
    }
    return sockets;
  }

  private liveUserIds(): Set<string> {
    return new Set(this.liveSockets().keys());
  }

  private unusedUserId(): string {
    const live = this.liveUserIds();
    for (;;) {
      const id = newUserId(Date.now());
      if (!live.has(id) && !this.store.member(id) && !this.store.pendingJoinOf(id)) return id;
    }
  }

  /** Writes a tombstone on the socket, so it stops counting as live and is not timed out again, and closes it. */
  private closeSocket(ws: WebSocket, att: Attachment, code: number, reason: string, patch: Partial<Attachment> = {}): void {
    try {
      ws.serializeAttachment({ ...att, ...patch, closed: true } satisfies Attachment);
    } catch {
      // already closed
    }
    safeClose(ws, code, reason);
  }

  /** Closes a socket that misbehaves (1008) and disconnects its member. */
  private cutOff(ws: WebSocket, att: Attachment | null, reason: string): void {
    if (att) this.closeSocket(ws, att, CLOSE_POLICY_VIOLATION, reason);
    else safeClose(ws, CLOSE_POLICY_VIOLATION, reason);
    this.onClose(ws).catch((error) => console.error("sangat: disconnect failed", errorName(error)));
  }

  /**
   * A message from a socket the server has already closed is not handled, and the
   * socket is closed again if the close has not taken hold. True when it was dropped.
   */
  private dropClosed(ws: WebSocket): boolean {
    if (!this.attachmentOf(ws)?.closed) return false;
    if (ws.readyState === WEBSOCKET_OPEN) safeClose(ws, CLOSE_POLICY_VIOLATION, "closed");
    return true;
  }

  private detachSocket(userId: string, code: number, reason: string): void {
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachmentOf(ws);
      if (att?.userId !== userId) continue;
      this.lastSyncAt.delete(userId);
      this.closeSocket(ws, att, code, reason, { userId: this.unusedUserId() });
    }
  }

  /**
   * Closes the sockets that have been silent for IDLE_TIMEOUT_MS. A phone that
   * lost its connection without a close frame is otherwise never noticed, and
   * its room would stay "live". Each gets a fresh id first, as in a takeover, so
   * its member is found socketless and disconnected (grace and user_disconnected
   * included) and the close event that may follow does nothing more.
   */
  private closeIdleSockets(now: number): void {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WEBSOCKET_OPEN) continue;
      const att = this.attachmentOf(ws);
      if (!att) continue;
      const deadline = idleDeadline(att);
      if (deadline === null || deadline > now) continue;
      this.lastSyncAt.delete(att.userId);
      this.closeSocket(ws, att, CLOSE_GOING_AWAY, CLOSE_REASON_IDLE, { userId: this.unusedUserId() });
    }
  }

  /** The earliest moment an open socket will count as idle, or null with no sockets to watch. */
  private nextIdleDeadline(): number | null {
    let earliest: number | null = null;
    for (const ws of this.ctx.getWebSockets()) {
      if (ws.readyState !== WEBSOCKET_OPEN) continue;
      const att = this.attachmentOf(ws);
      const deadline = att ? idleDeadline(att) : null;
      if (deadline !== null && (earliest === null || deadline < earliest)) earliest = deadline;
    }
    return earliest;
  }

  private allowSyncResponse(userId: string, now: number): boolean {
    const last = this.lastSyncAt.get(userId);
    if (last !== undefined && now - last < REQUEST_SYNC_MIN_INTERVAL_MS) return false;
    this.lastSyncAt.set(userId, now);
    return true;
  }

  /**
   * Once per instance: after a restart or redeploy the old sockets can be gone
   * without any close event, leaving members marked connected. They become
   * ordinary disconnects (grace and notices included), which their phones recover
   * from by reconnecting. Synchronous, so every way into the object can call it
   * before it reads or answers anything.
   */
  private reconcileNow(): void {
    if (this.reconciled) return;
    let outs: Out[];
    try {
      outs = this.store.transaction(() => disconnectOrphans(this.runtime, this.liveUserIds()));
    } catch (error) {
      // Not marked done, so the next way in tries again. What was asked of the hub goes on.
      console.error("sangat: reconciling after a restart failed", errorName(error));
      return;
    }
    this.reconciled = true;
    this.announce(outs);
  }

  // Output

  private async flush(outs: Out[]): Promise<void> {
    if (outs.length === 0) return;
    const sockets = this.liveSockets();
    // A broadcast is encoded once per (type, compressed or plain).
    const frames = new Map<Uint8Array, Map<string, Uint8Array>>();
    for (const out of outs) {
      const target = sockets.get(out.to);
      if (!target) continue;
      const compress = !target.att.plain;
      const key = `${out.type}|${compress}`;
      let byKey = frames.get(out.payload);
      if (!byKey) frames.set(out.payload, (byKey = new Map()));
      let frame = byKey.get(key);
      if (!frame) {
        frame = await this.frame(out.type, out.payload, compress);
        byKey.set(key, frame);
      }
      try {
        target.ws.send(frame);
      } catch {
        // the socket closed under us; its close event cleans up
      }
    }
  }

  private async frame(type: string, payload: Uint8Array, compress: boolean): Promise<Uint8Array> {
    if (compress) return encodeEnvelope(type, payload);
    return toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload, compressed: false }));
  }

  // The alarm

  /**
   * Keeps the one alarm at or before the earliest deadline, and clears it when there
   * is none. Deadlines are the expiries in storage and the moment the quietest socket
   * counts as idle.
   */
  private async scheduleAlarm(failedAt?: number): Promise<void> {
    let stored = this.store.nextDeadline();
    // A stored deadline that is already past after a failed sweep would fire again
    // at once; it is retried later instead.
    if (failedAt !== undefined && stored !== null && stored <= failedAt) stored = failedAt + ALARM_RETRY_MS;
    const idle = this.nextIdleDeadline();
    const wanted = stored === null ? idle : idle === null ? stored : Math.min(stored, idle);
    if (this.alarmAt === undefined) this.alarmAt = await this.ctx.storage.getAlarm();
    if (wanted === null) {
      if (this.alarmAt === null) return;
      await this.ctx.storage.deleteAlarm();
    } else {
      // An alarm already at or before the deadline stays: moving it later would be a
      // write for every message, and an early wake only finds nothing due and re-arms.
      if (this.alarmAt !== null && this.alarmAt <= wanted) return;
      await this.ctx.storage.setAlarm(wanted);
    }
    this.alarmAt = wanted;
  }
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.name : "unknown error";
}
