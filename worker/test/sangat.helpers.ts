// Shared helpers for the Sangat room and lifecycle tests: seed approved devices,
// open real hibernatable WebSockets to the one FamilyHub, and read what each
// client receives as decoded proto3 JSON. The client side is traces/wire.ts,
// which shares no code with src/sangat/codec.ts on purpose.

import { fromBinary } from "@bufbuild/protobuf";
import { env, evictAllDurableObjects, runInDurableObject } from "cloudflare:test";
import { EnvelopeSchema } from "../src/gen/listentogether_pb";
import { decodeFrame, encodeFrame } from "./traces/wire.ts";

export const HUB_ORIGIN = "https://hub.example";
export const DEVICE_PREFIX = "sg-device-";

// `compressed` is the Envelope flag as the hub sent it.
export type Frame = { type: string; payload: Record<string, any>; compressed: boolean };
export type Hub = ReturnType<typeof hubStub>;

export function hubStub() {
  return env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName("family"));
}

function randomHex(bytes: number): string {
  const raw = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("");
}

export const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

export async function sql<T>(hub: Hub, fn: (sql: SqlStorage) => T): Promise<T> {
  return runInDurableObject(hub, (_instance, state) => fn(state.storage.sql));
}

/** Evicts the hub (sockets closed, storage kept) and removes everything a test left behind. */
export async function resetHub(): Promise<void> {
  const hub = hubStub();
  await evictAllDurableObjects({ webSockets: "close" });
  await runInDurableObject(hub, async (_instance, state) => {
    const rows = state.storage.sql
      .exec<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'sangat\\_%' ESCAPE '\\'",
      )
      .toArray();
    for (const { name } of rows) state.storage.sql.exec(`DELETE FROM ${name}`);
    state.storage.sql.exec("DELETE FROM roster WHERE deviceId LIKE ?", `${DEVICE_PREFIX}%`);
    state.storage.sql.exec("UPDATE killswitches SET enabled = 1");
    await state.storage.deleteAlarm();
  });
}

export type Device = { deviceId: string; token: string };

export async function seedDevice(
  hub: Hub,
  name: string,
  status: "approved" | "pending" | "revoked" = "approved",
): Promise<Device> {
  const deviceId = `${DEVICE_PREFIX}${name}`;
  const token = randomHex(32);
  await sql(hub, (db) => {
    db.exec(
      "INSERT OR REPLACE INTO roster (deviceId, deviceName, status, sessionToken, approvedAt, claimSecret, role) VALUES (?, ?, ?, ?, ?, NULL, 'adult')",
      deviceId,
      `Test phone ${name}`,
      status,
      status === "approved" ? token : null,
      status === "approved" ? Date.now() : null,
    );
  });
  return { deviceId, token };
}

export function wsRequest(token: string | null, extra: Record<string, string> = {}): RequestInit {
  const headers: Record<string, string> = { Upgrade: "websocket", ...extra };
  if (token !== null) headers.Authorization = `Bearer ${token}`;
  return { headers };
}

const live = new Set<SangatClient>();

export function closeAllClients(): void {
  for (const client of live) client.close();
  live.clear();
}

export class SangatClient {
  readonly received: Frame[] = [];
  readonly closed: Promise<{ code: number; reason: string }>;
  private queue: Frame[] = [];
  private waiter: (() => void) | null = null;
  private decoding: Promise<void> = Promise.resolve();
  private problem: unknown;

  private constructor(private readonly socket: WebSocket) {
    this.closed = new Promise((resolve) => {
      socket.addEventListener("close", (event) => resolve({ code: event.code, reason: event.reason }));
    });
    socket.addEventListener("message", (event) => {
      if (typeof event.data === "string") {
        this.problem = new Error("hub sent a text frame");
        this.waiter?.();
        return;
      }
      const bytes = new Uint8Array(event.data as ArrayBuffer);
      this.decoding = this.decoding.then(async () => {
        try {
          const { type, payload } = await decodeFrame(bytes);
          const frame: Frame = {
            type,
            payload,
            compressed: fromBinary(EnvelopeSchema, bytes).compressed,
          };
          this.received.push(frame);
          this.queue.push(frame);
        } catch (error) {
          this.problem = error;
        }
        this.waiter?.();
      });
    });
  }

  static async connect(hub: Hub, token: string): Promise<SangatClient> {
    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(token));
    const socket = response.webSocket;
    if (response.status !== 101 || !socket) {
      throw new Error(`GET /ws answered ${response.status}: ${await response.text()}`);
    }
    socket.accept();
    socket.binaryType = "arraybuffer";
    const client = new SangatClient(socket);
    live.add(client);
    return client;
  }

  async send(type: string, payload: object = {}): Promise<void> {
    this.socket.send(await encodeFrame(type, payload));
  }

  sendRaw(bytes: Uint8Array): void {
    this.socket.send(bytes);
  }

  sendText(text: string): void {
    this.socket.send(text);
  }

  close(code = 1000): void {
    try {
      this.socket.close(code, "test done");
    } catch {
      // already closed
    }
  }

  /** Takes the next frame the hub sent; if `type` is given, it must be that type. */
  async next(type?: string, timeoutMs = 3000): Promise<Frame> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      if (this.problem) throw this.problem;
      const frame = this.queue.shift();
      if (frame) {
        if (type !== undefined && frame.type !== type) {
          throw new Error(`expected ${type}, got ${frame.type}: ${JSON.stringify(frame.payload)}`);
        }
        return frame;
      }
      let timer: ReturnType<typeof setTimeout> | undefined;
      const timedOut = await Promise.race([
        new Promise<false>((resolve) => {
          this.waiter = () => resolve(false);
        }),
        new Promise<true>((resolve) => {
          timer = setTimeout(() => resolve(true), Math.max(1, deadline - Date.now()));
        }),
      ]);
      if (timer !== undefined) clearTimeout(timer);
      this.waiter = null;
      if (timedOut) {
        throw new Error(`timed out waiting for ${type ?? "a frame"}; got so far: ${this.describe()}`);
      }
    }
  }

  /** Asserts the hub sends nothing more for `ms`; returns what arrived if it did. */
  async quiet(ms = 150): Promise<Frame[]> {
    await sleep(ms);
    const extra = this.queue.splice(0);
    return extra;
  }

  private describe(): string {
    return this.received.map((f) => f.type).join(", ") || "(nothing)";
  }
}

/** Opens a socket for `device`, creates a room as `name` and returns the room_created payload. */
export async function createRoom(
  hub: Hub,
  device: Device,
  name = "Asha",
): Promise<{ client: SangatClient; code: string; userId: string; token: string }> {
  const client = await SangatClient.connect(hub, device.token);
  await client.send("create_room", { username: name });
  const created = await client.next("room_created");
  return {
    client,
    code: created.payload.room_code,
    userId: created.payload.user_id,
    token: created.payload.session_token,
  };
}

/** Joins `code` as `name` from `device` and has `host` approve; returns the joiner. */
export async function joinAndApprove(
  hub: Hub,
  host: SangatClient,
  code: string,
  device: Device,
  name: string,
): Promise<{ client: SangatClient; userId: string; token: string }> {
  const client = await SangatClient.connect(hub, device.token);
  await client.send("join_room", { room_code: code, username: name });
  const request = await host.next("join_request");
  await host.send("approve_join", { user_id: request.payload.user_id });
  const approved = await client.next("join_approved");
  return { client, userId: approved.payload.user_id, token: approved.payload.session_token };
}

/**
 * A host plus guests in join order, each on their own approved device. Frames
 * announcing the joins (user_joined) are consumed, so the next frame any client
 * reads is whatever the test provokes.
 */
export async function openFamily(hub: Hub, names = ["a", "b", "c"], hostName = "Asha") {
  const guestNames = ["Bharat", "Charu", "Dev"];
  const devices = await Promise.all(names.map((name) => seedDevice(hub, name)));
  const host = await createRoom(hub, devices[0], hostName);
  const guests: { client: SangatClient; userId: string; token: string; name: string }[] = [];
  for (let i = 1; i < devices.length; i++) {
    const name = guestNames[i - 1];
    const guest = await joinAndApprove(hub, host.client, host.code, devices[i], name);
    await host.client.next("user_joined");
    for (const earlier of guests) await earlier.client.next("user_joined");
    guests.push({ ...guest, name });
  }
  return { devices, host, guests };
}
