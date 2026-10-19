import { fromBinary } from "@bufbuild/protobuf";
import {
  evictDurableObject,
  runDurableObjectAlarm,
  runInDurableObject,
} from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { RoomStateSchema } from "../src/gen/listentogether_pb";
import { Sangat } from "../src/sangat/Sangat";
import { IDLE_TIMEOUT_MS, RATE_LIMIT_MESSAGES_PER_SECOND } from "../src/sangat/limits";
import { recordMessage } from "../src/sangat/liveness";
import type { Attachment } from "../src/sangat/types";
import {
  closeAllClients,
  createRoom,
  hubStub,
  openFamily,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
  type Hub,
} from "./sangat.helpers";
import { encodeFrame } from "./traces/wire.ts";

beforeEach(resetHub);
afterEach(closeAllClients);

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const GRACE = 15 * MINUTE;

const getAlarm = (hub: Hub) => runInDurableObject(hub, (_instance, state) => state.storage.getAlarm());

type Stamp = { userId: string; lastSeen?: number; rateStart?: number; rateCount?: number };

/** What the hub's open sockets have in their attachments. */
async function attachments(hub: Hub): Promise<Stamp[]> {
  return runInDurableObject(hub, (_instance, state) =>
    state.getWebSockets().map((ws) => ws.deserializeAttachment() as Stamp),
  );
}

async function stampOf(hub: Hub, userId: string): Promise<Stamp> {
  const found = (await attachments(hub)).find((a) => a.userId === userId);
  if (!found) throw new Error(`no open socket for ${userId}`);
  return found;
}

/** Makes the socket holding `userId` look like it last spoke at `lastSeen`. */
async function setLastSeen(hub: Hub, userId: string, lastSeen: number): Promise<void> {
  await runInDurableObject(hub, (_instance, state) => {
    for (const ws of state.getWebSockets()) {
      const att = ws.deserializeAttachment() as Stamp;
      if (att.userId === userId) ws.serializeAttachment({ ...att, lastSeen });
    }
  });
}

const silentFor = (hub: Hub, userId: string, ms: number) => setLastSeen(hub, userId, Date.now() - ms);

async function memberRow(hub: Hub, userId: string) {
  return sql(hub, (db) => db.exec("SELECT * FROM sangat_members WHERE userId = ?", userId).toArray()[0] ?? null);
}

describe("recordMessage (the per-connection rate limit)", () => {
  const fresh = (): Attachment => ({ userId: "user_1_1", deviceId: "d" });

  it("lets 60 messages through in a second and refuses the 61st", () => {
    const att = fresh();
    const verdicts = Array.from({ length: 61 }, (_, i) => recordMessage(att, 1_000_000 + i));
    expect(verdicts.slice(0, 60).every(Boolean)).toBe(true);
    expect(verdicts[60]).toBe(false);
    expect(RATE_LIMIT_MESSAGES_PER_SECOND).toBe(60);
  });

  it("starts a new window 1000 ms after the first message of the last one", () => {
    const att = fresh();
    for (let i = 0; i < 60; i++) recordMessage(att, 1_000_000);
    expect(recordMessage(att, 1_000_999)).toBe(false);
    expect(recordMessage(att, 1_001_000)).toBe(true);
    for (let i = 0; i < 59; i++) expect(recordMessage(att, 1_001_500)).toBe(true);
    expect(recordMessage(att, 1_001_999)).toBe(false);
  });

  it("starts over if the clock went backwards", () => {
    const att = fresh();
    for (let i = 0; i < 60; i++) recordMessage(att, 1_000_000);
    expect(recordMessage(att, 999_000)).toBe(true);
  });

  it("notes when the socket was last heard from, even for a refused message", () => {
    const att = fresh();
    for (let i = 0; i < 60; i++) recordMessage(att, 1_000_000);
    expect(att.lastSeen).toBe(1_000_000);
    expect(recordMessage(att, 1_000_400)).toBe(false);
    expect(att.lastSeen).toBe(1_000_400);
  });

  it("keeps all of its state in the attachment, so a structured clone carries it over", () => {
    const att = fresh();
    for (let i = 0; i < 60; i++) recordMessage(att, 1_000_000);
    const revived: Attachment = structuredClone(att);
    expect(recordMessage(revived, 1_000_100)).toBe(false);
  });
});

describe("the 60 messages per second limit on a live socket", () => {
  async function burst(client: SangatClient, count: number): Promise<void> {
    // Encoded first and sent in one synchronous loop, so the burst lands inside a single window.
    const frames = await Promise.all(
      Array.from({ length: count }, (_, i) => encodeFrame("ping", { client_time: 1, sequence: i + 1 })),
    );
    for (const frame of frames) client.sendRaw(frame);
  }

  async function collect(client: SangatClient, count: number) {
    const frames = [];
    for (let i = 0; i < count; i++) frames.push(await client.next());
    return frames;
  }

  it("the 61st message within a second answers rate_limited, and is not handled", async () => {
    const hub = hubStub();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);

    await burst(client, 61);

    const frames = await collect(client, 61);
    const errors = frames.filter((f) => f.type === "error");
    expect(errors.map((f) => f.payload)).toEqual([{ code: "rate_limited", message: "Too many messages" }]);
    const pongs = frames.filter((f) => f.type === "pong").map((f) => Number(f.payload.sequence));
    expect(pongs.sort((a, b) => a - b)).toEqual(Array.from({ length: 60 }, (_, i) => i + 1));
    expect(await client.quiet(100)).toEqual([]);
  });

  it("answers every refused message, and handles messages again once the window has passed", async () => {
    const hub = hubStub();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);

    await burst(client, 65);
    const frames = await collect(client, 65);
    expect(frames.filter((f) => f.type === "error")).toHaveLength(5);

    await sleep(1100);
    await client.send("ping", { sequence: 99 });
    expect((await client.next("pong")).payload.sequence).toBe("99");
  });

  it("is per connection: a flooding socket does not slow another one, even from the same device", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const flooder = await SangatClient.connect(hub, device.token);
    const calm = await SangatClient.connect(hub, device.token);

    await burst(flooder, 70);
    await calm.send("ping", { sequence: 7 });

    expect((await calm.next("pong")).payload.sequence).toBe("7");
    expect((await collect(flooder, 70)).filter((f) => f.type === "error")).toHaveLength(10);
  });

  it("a refused message never reaches the queue: a refused create_room creates nothing", async () => {
    const hub = hubStub();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);
    await burst(client, 60);
    await collect(client, 60);

    await client.send("create_room", { username: "Asha" });

    expect((await client.next("error")).payload.code).toBe("rate_limited");
    await sleep(50);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms").one().n)).toBe(0);
  });

  it("the window survives hibernation", async () => {
    const hub = hubStub();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);
    await burst(client, 60);
    await collect(client, 60);

    await evictDurableObject(hub, { webSockets: "hibernate" });
    await client.send("ping", { sequence: 61 });

    expect((await client.next()).payload).toEqual({ code: "rate_limited", message: "Too many messages" });
  });
});

describe("idle sockets", () => {
  it("a new socket starts its idle clock on connecting, and the alarm waits for it", async () => {
    const hub = hubStub();
    const before = Date.now();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);
    await client.send("ping");
    await client.next("pong");

    const [stamp] = await attachments(hub);
    expect(stamp.lastSeen).toBeGreaterThanOrEqual(before);
    expect(stamp.lastSeen).toBeLessThanOrEqual(Date.now());
    expect(await getAlarm(hub)).toBe((stamp.lastSeen as number) + IDLE_TIMEOUT_MS);
    expect(IDLE_TIMEOUT_MS).toBe(75_000);
  });

  it("the alarm is set at connect time, before the socket has said anything", async () => {
    const hub = hubStub();
    await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);
    await sleep(100);

    const [stamp] = await attachments(hub);
    expect(await getAlarm(hub)).toBe((stamp.lastSeen as number) + IDLE_TIMEOUT_MS);
  });

  it("every message refreshes lastSeen and the alarm follows the oldest silent socket", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const hostSeen = Date.now() - 40 * SECOND;
    const guestSeen = Date.now() - 20 * SECOND;
    await setLastSeen(hub, host.userId, hostSeen);
    await setLastSeen(hub, bharat.userId, guestSeen);

    await bharat.client.send("ping");
    await bharat.client.next("pong");

    expect((await stampOf(hub, bharat.userId)).lastSeen).toBeGreaterThan(guestSeen + 15 * SECOND);
    expect(await getAlarm(hub)).toBe(hostSeen + IDLE_TIMEOUT_MS);

    await host.client.send("ping");
    await host.client.next("pong");
    const newest = Math.min(
      ...(await attachments(hub)).map((a) => a.lastSeen as number),
    );
    expect(await getAlarm(hub)).toBe(newest + IDLE_TIMEOUT_MS);
  });

  it("a socket silent for more than 75 s is closed with 1001 'idle' and marked disconnected, with the grace running", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await silentFor(hub, bharat.userId, IDLE_TIMEOUT_MS + SECOND);

    expect(await runDurableObjectAlarm(hub)).toBe(true);

    expect(await bharat.client.closed).toEqual({ code: 1001, reason: "idle" });
    expect((await host.client.next("user_disconnected")).payload).toEqual({
      user_id: bharat.userId,
      username: "Bharat",
    });
    const row = await memberRow(hub, bharat.userId);
    expect(row).toMatchObject({ connected: 0, sessionToken: bharat.token });
    expect(Math.abs((row?.disconnectedAt as number) - Date.now())).toBeLessThan(5000);
    const room = await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms WHERE code = ?", host.code).one());
    const state = fromBinary(RoomStateSchema, new Uint8Array(room.state as ArrayBuffer));
    expect(state.users.map((u) => [u.username, u.isConnected])).toEqual([
      ["Asha", true],
      ["Bharat", false],
    ]);
    // The host's own idle deadline is the earlier one; the 15 minute grace is the next.
    const hostSeen = (await stampOf(hub, host.userId)).lastSeen as number;
    expect(await getAlarm(hub)).toBe(Math.min(hostSeen + IDLE_TIMEOUT_MS, (row?.disconnectedAt as number) + GRACE));
  });

  it("an active socket is not closed, even when its neighbour is", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await silentFor(hub, host.userId, IDLE_TIMEOUT_MS + SECOND);
    await silentFor(hub, bharat.userId, IDLE_TIMEOUT_MS + SECOND);
    await host.client.send("ping", { sequence: 1 });
    await host.client.next("pong");

    await runDurableObjectAlarm(hub);

    expect(await bharat.client.closed).toEqual({ code: 1001, reason: "idle" });
    await host.client.next("user_disconnected");
    await host.client.send("ping", { sequence: 2 });
    expect((await host.client.next("pong")).payload.sequence).toBe("2");
    expect((await memberRow(hub, host.userId))?.connected).toBe(1);
  });

  it("a socket silent for less than 75 s is left alone", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await silentFor(hub, bharat.userId, IDLE_TIMEOUT_MS - 2 * SECOND);

    await runDurableObjectAlarm(hub);

    expect(await host.client.quiet(100)).toEqual([]);
    expect((await memberRow(hub, bharat.userId))?.connected).toBe(1);
    await bharat.client.send("ping");
    await bharat.client.next("pong");
  });

  it("a dead host phone stops being live, and the host can come back inside the grace", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    expect(await runInDurableObject(hub, (_i, state) => new Sangat(state).liveRooms().length)).toBe(1);
    await silentFor(hub, host.userId, IDLE_TIMEOUT_MS + SECOND);

    await runDurableObjectAlarm(hub);

    expect(await host.client.closed).toEqual({ code: 1001, reason: "idle" });
    expect((await bharat.client.next("user_disconnected")).payload.user_id).toBe(host.userId);
    expect(await runInDurableObject(hub, (_i, state) => new Sangat(state).liveRooms())).toEqual([]);

    const back = await SangatClient.connect(hub, devices[0].token);
    await back.send("reconnect", { session_token: host.token });
    expect((await back.next("reconnected")).payload).toMatchObject({ user_id: host.userId, is_host: true });
    expect((await bharat.client.next("user_reconnected")).payload.user_id).toBe(host.userId);
  });

  it("a socket that never joined a room is closed too, and the close event that follows disconnects nobody twice", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const lobby = await SangatClient.connect(hub, (await seedDevice(hub, "z")).token);
    await lobby.send("ping");
    await lobby.next("pong");
    const lobbyId = (await attachments(hub)).find((a) => a.userId !== host.userId && a.userId !== bharat.userId)
      ?.userId as string;
    await silentFor(hub, lobbyId, IDLE_TIMEOUT_MS + SECOND);
    await silentFor(hub, bharat.userId, IDLE_TIMEOUT_MS + SECOND);

    await runDurableObjectAlarm(hub);
    expect(await lobby.closed).toEqual({ code: 1001, reason: "idle" });
    expect(await bharat.client.closed).toEqual({ code: 1001, reason: "idle" });
    await host.client.next("user_disconnected");
    const first = (await memberRow(hub, bharat.userId))?.disconnectedAt;
    await sleep(150);

    expect(await host.client.quiet(100)).toEqual([]);
    expect((await memberRow(hub, bharat.userId))?.disconnectedAt).toBe(first);
  });

  it("the idle clock is in the attachment, so it survives hibernation", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await silentFor(hub, bharat.userId, IDLE_TIMEOUT_MS + SECOND);

    await evictDurableObject(hub, { webSockets: "hibernate" });
    await runDurableObjectAlarm(hub);

    expect(await bharat.client.closed).toEqual({ code: 1001, reason: "idle" });
    expect((await host.client.next("user_disconnected")).payload.user_id).toBe(bharat.userId);
  });

  it("with no sockets left and no deadlines, there is no alarm", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const room = await createRoom(hub, device);
    await room.client.send("leave_room");
    await sleep(50);
    room.client.close();
    await sleep(100);
    // The emptied room still has its 5 minute cleanup; age it out and nothing is left.
    await sql(hub, (db) => db.exec("UPDATE sangat_rooms SET emptySince = ?", Date.now() - 6 * MINUTE));

    await runDurableObjectAlarm(hub);

    expect(await getAlarm(hub)).toBeNull();
  });
});
