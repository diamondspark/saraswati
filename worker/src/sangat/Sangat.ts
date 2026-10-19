// Sangat (listen together) inside the FamilyHub Durable Object.
//
// Sockets use the WebSocket Hibernation API, so the object can be evicted
// between messages: a socket's identity ({userId, deviceId, ...}) lives in its
// serialized attachment and every room, member, pending join and suggestion
// lives in SQLite. The only state kept in memory is a message queue, a
// per-socket throttle and a cache of the alarm time, all rebuildable.
//
// Every event that touches rooms (a message, a close, the alarm) runs through
// one promise chain. Decoding a gzipped message awaits, which opens the Durable
// Object's input gate; the chain keeps messages in the order they were sent,
// keeps room changes from interleaving, and means only one decode is in flight
// against the 128 MB limit. A handler itself is synchronous: it reads and
// writes storage inside one transaction and returns the messages to send.

import { create, toBinary } from "@bufbuild/protobuf";
import { EnvelopeSchema } from "../gen/listentogether_pb";
import { CodecError, decodeEnvelope, encodeEnvelope } from "./codec";
import { handleClientCapabilities, handlePing } from "./connection";
import { newUserId } from "./ids";
import { disconnectOrphans, disconnectUser, handleReconnect, sweep } from "./lifecycle";
import { MAX_READ_MESSAGE_BYTES, REQUEST_SYNC_MIN_INTERVAL_MS } from "./limits";
import { handlePlaybackAction, handleRequestSync } from "./playback";
import { fail, hostIsConnected, removeFromRoom } from "./roomOps";
import {
  handleApproveJoin,
  handleCreateRoom,
  handleJoinRoom,
  handleLeaveRoom,
  handleRejectJoin,
} from "./rooms";
import { Store } from "./store";
import { handleSuggestTrack } from "./suggestions";
import type { Attachment, Conn, Handler, Out, Runtime } from "./types";

export type LiveRoom = {
  roomCode: string;
  hostName: string;
  hostDeviceId: string;
  carMode: boolean;
  memberCount: number;
  /** Epoch milliseconds. */
  startedAt: number;
};

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
  ["client_capabilities", handleClientCapabilities],
]);

// What codec.ts says for an envelope with no type; metroserver words it differently.
const EMPTY_TYPE_ERROR = "empty envelope type";
const CLOSE_MESSAGE_TOO_BIG = 1009;
const CLOSE_INTERNAL_ERROR = 1011;

const encoder = new TextEncoder();

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

  constructor(private readonly ctx: DurableObjectState) {
    this.store = new Store(ctx.storage);
    this.runtime = {
      store: this.store,
      now: () => Date.now(),
      detachSocket: (userId, code, reason) => this.detachSocket(userId, code, reason),
      allowSyncResponse: (userId, now) => this.allowSyncResponse(userId, now),
    };
  }

  // The hub's entry points

  /** Upgrades an authenticated device's request. The caller has already checked auth and the kill switch. */
  accept(deviceId: string): Response {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    const attachment: Attachment = { userId: this.unusedUserId(), deviceId };
    server.serializeAttachment(attachment);
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
    return this.enqueue(() => this.handleMessage(ws, data, receivedAt));
  }

  onClose(ws: WebSocket): Promise<void> {
    return this.enqueue(async () => {
      await this.reconcileOnce();
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
      this.reconciled = true;
      const outs = this.store.transaction(() => [
        ...disconnectOrphans(this.runtime, this.liveUserIds()),
        ...sweep(this.runtime, Date.now()),
      ]);
      await this.flush(outs);
      await this.scheduleAlarm();
    });
  }

  /** Rooms whose host is connected, for GET /sangat/live. */
  liveRooms(): LiveRoom[] {
    return this.store
      .openRooms()
      .filter(hostIsConnected)
      .map((room) => ({
        roomCode: room.code,
        hostName: this.store.member(room.hostUserId)?.username ?? "",
        hostDeviceId: room.hostDeviceId,
        carMode: room.carMode,
        memberCount: this.store.connectedCount(room.code),
        startedAt: room.createdAt,
      }));
  }

  /** Car mode is the host device's to switch, for PUT /sangat/car. */
  setCarMode(roomCode: string, deviceId: string, carMode: boolean): "ok" | "not_found" | "not_host" {
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
   * it leaves behind are told. The storage changes are done when this returns.
   */
  closeDevice(deviceId: string, code: number, reason: string): void {
    const now = Date.now();
    const outs = this.store.transaction(() => {
      this.store.deletePendingJoinsOfDevice(deviceId);
      const result: Out[] = [];
      for (const member of this.store.membersOfDevice(deviceId)) {
        const room = this.store.getRoom(member.roomCode);
        if (!room || room.emptySince !== null) this.store.deleteMember(member.userId);
        else result.push(...removeFromRoom(this.store, room, member, now));
      }
      return result;
    });
    for (const ws of this.ctx.getWebSockets()) {
      if (this.attachmentOf(ws)?.deviceId === deviceId) safeClose(ws, code, reason);
    }
    this.announce(outs);
  }

  /** Closes every socket with `code` and deletes every room. */
  closeAll(code: number, reason: string): void {
    for (const ws of this.ctx.getWebSockets()) safeClose(ws, code, reason);
    this.store.wipe();
    this.lastSyncAt.clear();
    this.announce([]);
  }

  // Serialization

  private enqueue<T>(job: () => Promise<T>): Promise<T> {
    const result = this.tail.then(job);
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
    await this.reconcileOnce();
    const bytes = typeof data === "string" ? encoder.encode(data) : new Uint8Array(data);
    if (bytes.byteLength > MAX_READ_MESSAGE_BYTES) {
      safeClose(ws, CLOSE_MESSAGE_TOO_BIG, "message too big");
      return;
    }

    const decoded = await this.decode(bytes);
    const attachment = this.attachmentOf(ws);
    if (!attachment) {
      safeClose(ws, CLOSE_INTERNAL_ERROR, "no session");
      return;
    }
    const conn: Conn = { ws, att: attachment, dirty: false, receivedAt };
    const me = attachment.userId;

    let outs: Out[];
    if ("error" in decoded) {
      outs = [fail(me, "invalid_message", decoded.error)];
    } else {
      const handler = HANDLERS.get(decoded.type);
      if (!handler) {
        outs = [fail(me, "unknown_message_type", `Unknown message type: ${decoded.type}`)];
      } else {
        try {
          outs = this.store.transaction(() => handler(this.runtime, conn, decoded.payload));
        } catch (error) {
          console.error("sangat: handler failed", decoded.type, errorName(error));
          conn.dirty = false;
          outs = [fail(me, "server_error", "Internal error")];
        }
      }
    }
    if (conn.dirty) ws.serializeAttachment(conn.att);
    await this.flush(outs);
    await this.scheduleAlarm();
  }

  private async decode(
    bytes: Uint8Array,
  ): Promise<{ type: string; payload: Uint8Array } | { error: string }> {
    if (bytes.byteLength === 0) return { error: "Invalid message format" };
    try {
      return await decodeEnvelope(bytes);
    } catch (error) {
      if (!(error instanceof CodecError)) throw error;
      return { error: error.message === EMPTY_TYPE_ERROR ? "Message type is required" : "Invalid message format" };
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

  private detachSocket(userId: string, code: number, reason: string): void {
    for (const ws of this.ctx.getWebSockets()) {
      const att = this.attachmentOf(ws);
      if (att?.userId !== userId) continue;
      ws.serializeAttachment({ ...att, userId: this.unusedUserId() } satisfies Attachment);
      this.lastSyncAt.delete(userId);
      safeClose(ws, code, reason);
    }
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
   * ordinary disconnects, which their phones recover from by reconnecting.
   */
  private async reconcileOnce(): Promise<void> {
    if (this.reconciled) return;
    this.reconciled = true;
    const outs = this.store.transaction(() => disconnectOrphans(this.runtime, this.liveUserIds()));
    await this.flush(outs);
    await this.scheduleAlarm();
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

  /** Points the one alarm at the earliest deadline in storage, or clears it. */
  private async scheduleAlarm(): Promise<void> {
    const wanted = this.store.nextDeadline();
    if (this.alarmAt === undefined) this.alarmAt = await this.ctx.storage.getAlarm();
    if (wanted === this.alarmAt) return;
    if (wanted === null) await this.ctx.storage.deleteAlarm();
    else await this.ctx.storage.setAlarm(wanted);
    this.alarmAt = wanted;
  }
}

function errorName(error: unknown): string {
  return error instanceof Error ? error.name : "unknown error";
}
