import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import {
  evictAllDurableObjects,
  evictDurableObject,
  runDurableObjectAlarm,
  runInDurableObject,
} from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { EnvelopeSchema, PingPayloadSchema, RoomStateSchema, type RoomState } from "../src/gen/listentogether_pb";
import { IDLE_TIMEOUT_MS } from "../src/sangat/limits";
import { createSangatTables } from "../src/sangat/schema";
import { Store } from "../src/sangat/store";
import {
  closeAllClients,
  createRoom,
  hubStub,
  joinAndApprove,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
} from "./sangat.helpers";

const MINUTE = 60_000;
const GRACE = 15 * MINUTE;
const EMPTY_CLEANUP = 5 * MINUTE;

beforeEach(resetHub);
afterEach(closeAllClients);

type Hub = ReturnType<typeof hubStub>;

type RoomRecord = Record<string, any> & { state: RoomState };

async function readRoom(hub: Hub, code: string): Promise<RoomRecord | null> {
  const row = await sql(hub, (db) => db.exec("SELECT * FROM sangat_rooms WHERE code = ?", code).toArray()[0]);
  if (!row) return null;
  return Object.assign({}, row, { state: fromBinary(RoomStateSchema, new Uint8Array(row.state as ArrayBuffer)) }) as RoomRecord;
}

async function member(hub: Hub, userId: string) {
  return sql(hub, (db) => db.exec("SELECT * FROM sangat_members WHERE userId = ?", userId).toArray()[0] ?? null);
}

async function age(hub: Hub, table: "sangat_members" | "sangat_rooms", column: string, key: string, ms: number) {
  const keyColumn = table === "sangat_members" ? "userId" : "code";
  await sql(hub, (db) =>
    db.exec(`UPDATE ${table} SET ${column} = ? WHERE ${keyColumn} = ?`, Date.now() - ms, key),
  );
}

const getAlarm = (hub: Hub) => runInDurableObject(hub, (_instance, state) => state.storage.getAlarm());

/**
 * The stored deadline (a member's grace or a room's cleanup) is `stored`, and the
 * alarm is no later than the earlier of that and the moment the quietest open
 * socket would count as idle.
 */
async function expectAlarm(hub: Hub, stored: number | null) {
  const { actual, idle } = await runInDurableObject(hub, (_instance, state) => {
    let earliest: number | null = null;
    for (const ws of state.getWebSockets()) {
      const att = ws.deserializeAttachment() as { lastSeen?: number; closed?: boolean };
      if (ws.readyState !== 1 || att.closed || att.lastSeen === undefined) continue;
      const at = att.lastSeen + IDLE_TIMEOUT_MS;
      if (earliest === null || at < earliest) earliest = at;
    }
    return { actual: new Store(state.storage).nextDeadline(), idle: earliest };
  });
  expect(actual).toBe(stored);
  const wanted = stored === null ? idle : idle === null ? stored : Math.min(stored, idle);
  // The alarm is never later than the earliest deadline. One that is already earlier
  // is left alone (a spurious early wake only re-arms), so it need not be equal.
  const alarm = await getAlarm(hub);
  if (wanted === null) expect(alarm).toBeNull();
  else {
    expect(alarm).not.toBeNull();
    expect(alarm).toBeLessThanOrEqual(wanted);
  }
}

const track = (n: number) => ({
  id: `trk-${n}`,
  title: `Raag Number ${n}`,
  artist: "Pt. Ravi Shankar",
  duration: 240000,
});

/** Host plus two guests in join order Bharat, Charu. */
async function family(hub: Hub, names = ["a", "b", "c"]) {
  const devices = await Promise.all(names.map((name) => seedDevice(hub, name)));
  const host = await createRoom(hub, devices[0], "Asha");
  const bharat = await joinAndApprove(hub, host.client, host.code, devices[1], "Bharat");
  await host.client.next("user_joined");
  const charu = devices[2] ? await joinAndApprove(hub, host.client, host.code, devices[2], "Charu") : undefined;
  if (charu) {
    await host.client.next("user_joined");
    await bharat.client.next("user_joined");
  }
  return { devices, host, bharat, charu };
}

describe("disconnects", () => {
  it("a socket close marks the member disconnected and sends user_disconnected", async () => {
    const hub = hubStub();
    const { host, bharat } = await family(hub, ["a", "b"]);

    bharat.client.close();

    expect((await host.client.next("user_disconnected")).payload).toEqual({
      user_id: bharat.userId,
      username: "Bharat",
    });
    const row = await member(hub, bharat.userId);
    expect(row).toMatchObject({ connected: 0, sessionToken: bharat.token });
    expect(Math.abs((row?.disconnectedAt as number) - Date.now())).toBeLessThan(5000);
    const room = await readRoom(hub, host.code);
    expect(room?.state.users.map((u) => [u.username, u.isConnected])).toEqual([
      ["Asha", true],
      ["Bharat", false],
    ]);
    await expectAlarm(hub, (row?.disconnectedAt as number) + GRACE);
  });

  it("a host's close is recorded on the room, and join requests wait for the host", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    const charuDevice = await seedDevice(hub, "c");

    host.client.close();
    await bharat.client.next("user_disconnected");
    expect((await readRoom(hub, host.code))?.hostDisconnectedAt).not.toBeNull();
    const charu = await SangatClient.connect(hub, charuDevice.token);
    await charu.send("join_room", { room_code: host.code, username: "Charu" });
    await sleep(100);
    expect(await bharat.client.quiet(0)).toEqual([]);

    const back = await SangatClient.connect(hub, devices[0].token);
    await back.send("reconnect", { session_token: host.token });
    expect((await back.next("reconnected")).payload).toMatchObject({ user_id: host.userId, is_host: true });
    expect((await back.next("join_request")).payload.username).toBe("Charu");
    expect((await readRoom(hub, host.code))?.hostDisconnectedAt).toBeNull();
  });
});

describe("reconnect", () => {
  it("reconnect within 15 minutes answers reconnected with is_host and the current state", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    bharat.client.close();
    await host.client.next("user_disconnected");
    await host.client.send("playback_action", { action: "change_track", track_info: track(1), queue: [track(2)] });
    await host.client.next("sync_playback");

    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: bharat.token });

    const reconnected = (await back.next("reconnected")).payload;
    expect(reconnected).toMatchObject({
      room_code: host.code,
      user_id: bharat.userId,
      is_host: false,
      state: {
        room_code: host.code,
        host_id: host.userId,
        current_track: { id: "trk-1" },
        queue: [{ id: "trk-2" }],
        revision: "1",
        users: [
          { username: "Asha", is_host: true, is_connected: true },
          { username: "Bharat", is_host: false, is_connected: true },
        ],
      },
    });
    expect((await host.client.next("user_reconnected")).payload).toEqual({
      user_id: bharat.userId,
      username: "Bharat",
    });
    expect(await member(hub, bharat.userId)).toMatchObject({ connected: 1, disconnectedAt: null });
    // Nothing left to expire.
    await expectAlarm(hub, null);
  });

  it("reconnect after 15 minutes answers session_expired", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    bharat.client.close();
    await host.client.next("user_disconnected");
    await age(hub, "sangat_members", "disconnectedAt", bharat.userId, GRACE + MINUTE);

    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: bharat.token });

    expect((await back.next("error")).payload).toEqual({
      code: "session_expired",
      message: "Session has expired",
    });
    // The expiry is carried out then and there: the room hears the member left.
    expect((await host.client.next("user_left")).payload).toEqual({
      user_id: bharat.userId,
      username: "Bharat",
    });
    expect(await member(hub, bharat.userId)).toBeNull();
    await back.send("reconnect", { session_token: bharat.token });
    expect((await back.next("error")).payload.code).toBe("session_not_found");
  });

  it("the alarm carries out an expiry before anyone reconnects: user_left, and the late token is session_not_found", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    bharat.client.close();
    await host.client.next("user_disconnected");
    await age(hub, "sangat_members", "disconnectedAt", bharat.userId, GRACE + MINUTE);

    expect(await runDurableObjectAlarm(hub)).toBe(true);

    expect((await host.client.next("user_left")).payload.user_id).toBe(bharat.userId);
    expect((await readRoom(hub, host.code))?.state.users.map((u) => u.username)).toEqual(["Asha"]);
    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: bharat.token });
    expect((await back.next("error")).payload).toEqual({
      code: "session_not_found",
      message: "Session not found or expired",
    });
    await expectAlarm(hub, null);
  });

  it("a token is bound to its device: another device cannot take the session over", async () => {
    const hub = hubStub();
    const { host, bharat } = await family(hub, ["a", "b"]);
    const other = await seedDevice(hub, "z");
    bharat.client.close();
    await host.client.next("user_disconnected");

    const thief = await SangatClient.connect(hub, other.token);
    await thief.send("reconnect", { session_token: bharat.token });

    expect((await thief.next("error")).payload.code).toBe("session_not_found");
    expect(await member(hub, bharat.userId)).toMatchObject({ connected: 0, deviceId: "sg-device-b" });
    expect(await host.client.quiet()).toEqual([]);
  });

  it("the same device reconnecting takes over a stale live socket without a disconnect being announced", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);

    const replacement = await SangatClient.connect(hub, devices[1].token);
    await replacement.send("reconnect", { session_token: bharat.token });

    expect((await replacement.next("reconnected")).payload.user_id).toBe(bharat.userId);
    expect(await bharat.client.closed).toMatchObject({ code: 1000 });
    expect((await host.client.next("user_reconnected")).payload.user_id).toBe(bharat.userId);
    await sleep(100);
    expect(await host.client.quiet(0)).toEqual([]);
    expect(await member(hub, bharat.userId)).toMatchObject({ connected: 1 });
    // The new socket really is the member.
    await replacement.send("request_sync");
    await replacement.next("sync_state");
  });
});

describe("a fresh Sangat instance over the same storage", () => {
  it("restores the room for a reconnecting host", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    await host.client.send("playback_action", { action: "change_track", track_info: track(1), queue: [track(2), track(3)] });
    await bharat.client.next("sync_playback");
    host.client.close();
    await bharat.client.next("user_disconnected");

    await evictDurableObject(hub);

    const back = await SangatClient.connect(hub, devices[0].token);
    await back.send("reconnect", { session_token: host.token });
    const reconnected = (await back.next("reconnected")).payload;
    expect(reconnected).toMatchObject({
      room_code: host.code,
      user_id: host.userId,
      is_host: true,
      state: {
        host_id: host.userId,
        current_track: { id: "trk-1" },
        queue: [{ id: "trk-2" }, { id: "trk-3" }],
        revision: "1",
      },
    });
    expect((await bharat.client.next("user_reconnected")).payload.user_id).toBe(host.userId);
  });

  it("restores the room when the old sockets were dropped without a close event", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await bharat.client.next("sync_playback");
    await host.client.next("sync_playback");

    // Every socket is torn down with the instance; nothing tells the new one.
    await evictAllDurableObjects({ webSockets: "close" });

    const hostAgain = await SangatClient.connect(hub, devices[0].token);
    await hostAgain.send("reconnect", { session_token: host.token });
    expect((await hostAgain.next("reconnected")).payload).toMatchObject({
      user_id: host.userId,
      is_host: true,
      state: { current_track: { id: "trk-1" }, users: [{ username: "Asha" }, { username: "Bharat" }] },
    });
    const bharatAgain = await SangatClient.connect(hub, devices[1].token);
    await bharatAgain.send("reconnect", { session_token: bharat.token });
    expect((await bharatAgain.next("reconnected")).payload).toMatchObject({ user_id: bharat.userId, is_host: false });
    expect((await hostAgain.next("user_reconnected")).payload.user_id).toBe(bharat.userId);
  });

  it("marks members whose sockets vanished with the old instance as disconnected, and schedules their grace", async () => {
    const hub = hubStub();
    const { host, bharat } = await family(hub, ["a", "b"]);
    const prober = await seedDevice(hub, "z");

    // Closing the sockets with the instance delivers no close events to anyone.
    await evictAllDurableObjects({ webSockets: "close" });
    expect(await member(hub, host.userId)).toMatchObject({ connected: 1 });
    expect(await member(hub, bharat.userId)).toMatchObject({ connected: 1 });

    const probe = await SangatClient.connect(hub, prober.token);
    await probe.send("ping", {});
    await probe.next("pong");

    for (const who of [host, bharat]) {
      expect(await member(hub, who.userId)).toMatchObject({ connected: 0 });
    }
    const disconnectedAt = (await member(hub, host.userId))?.disconnectedAt as number;
    expect(await getAlarm(hub)).toBeLessThanOrEqual(disconnectedAt + GRACE);
    expect((await readRoom(hub, host.code))?.hostDisconnectedAt).not.toBeNull();
  });

  it("keeps hibernated sockets working: the user id comes back from the socket attachment", async () => {
    const hub = hubStub();
    const { host, bharat } = await family(hub, ["a", "b"]);

    await evictDurableObject(hub, { webSockets: "hibernate" });

    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    expect((await bharat.client.next("sync_playback")).payload.action).toBe("change_track");
    await bharat.client.send("leave_room");
    expect((await host.client.next("sync_playback")).payload.action).toBe("change_track");
    expect((await host.client.next("user_left")).payload.user_id).toBe(bharat.userId);
  });
});

describe("expiry and the alarm", () => {
  it("an empty room is deleted 5 minutes after its last member leaves", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const host = await createRoom(hub, device);

    await host.client.send("leave_room");
    await sleep(50);

    const emptied = await readRoom(hub, host.code);
    expect(emptied?.emptySince).toBeGreaterThan(0);
    await expectAlarm(hub, (emptied?.emptySince as number) + EMPTY_CLEANUP);
    // Still there, but not a room anyone can reach.
    const stranger = await SangatClient.connect(hub, device.token);
    await stranger.send("join_room", { room_code: host.code, username: "Asha" });
    expect((await stranger.next("error")).payload.code).toBe("room_not_found");

    await age(hub, "sangat_rooms", "emptySince", host.code, EMPTY_CLEANUP + MINUTE);
    expect(await runDurableObjectAlarm(hub)).toBe(true);

    expect(await readRoom(hub, host.code)).toBeNull();
    await expectAlarm(hub, null);
  });

  it("a room whose last member expires is emptied the same way", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const host = await createRoom(hub, device);
    host.client.close();
    await sleep(100);
    await age(hub, "sangat_members", "disconnectedAt", host.userId, GRACE + MINUTE);

    await runDurableObjectAlarm(hub);

    const emptied = await readRoom(hub, host.code);
    expect(emptied?.emptySince).toBeGreaterThan(0);
    expect(await member(hub, host.userId)).toBeNull();
    await expectAlarm(hub, (emptied?.emptySince as number) + EMPTY_CLEANUP);
  });

  it("the alarm is always the earliest pending deadline", async () => {
    const hub = hubStub();
    const [a, b, d] = await Promise.all(["a", "b", "d"].map((n) => seedDevice(hub, n)));
    await expectAlarm(hub, null);
    const one = await createRoom(hub, a, "Asha");
    const bharat = await joinAndApprove(hub, one.client, one.code, b, "Bharat");
    await one.client.next("user_joined");

    // First deadline: Bharat's grace.
    bharat.client.close();
    await one.client.next("user_disconnected");
    const first = (await member(hub, bharat.userId))?.disconnectedAt as number;
    await expectAlarm(hub, first + GRACE);

    // A later disconnect must not push the alarm back.
    await sleep(25);
    one.client.close();
    await sleep(100);
    const hostRow = (await member(hub, one.userId))?.disconnectedAt as number;
    expect(hostRow).toBeGreaterThan(first);
    await expectAlarm(hub, first + GRACE);

    // When the earliest member is back, the next one becomes the deadline.
    const bharatAgain = await SangatClient.connect(hub, b.token);
    await bharatAgain.send("reconnect", { session_token: bharat.token });
    await bharatAgain.next("reconnected");
    await expectAlarm(hub, hostRow + GRACE);

    // A room that has just been emptied (5 minutes) comes before a 15 minute grace.
    const dev = await createRoom(hub, d, "Dev");
    await dev.client.send("leave_room");
    await sleep(50);
    const emptySince = (await readRoom(hub, dev.code))?.emptySince as number;
    await expectAlarm(hub, Math.min(emptySince + EMPTY_CLEANUP, hostRow + GRACE));
    expect(emptySince + EMPTY_CLEANUP).toBeLessThan(hostRow + GRACE);

    // Once that room is gone, back to the member's grace.
    await age(hub, "sangat_rooms", "emptySince", dev.code, EMPTY_CLEANUP + MINUTE);
    await runDurableObjectAlarm(hub);
    await expectAlarm(hub, hostRow + GRACE);

    // And with no deadlines left there is no alarm at all.
    const hostAgain = await SangatClient.connect(hub, a.token);
    await hostAgain.send("reconnect", { session_token: one.token });
    await hostAgain.next("reconnected");
    await expectAlarm(hub, null);
  });

  it("when the host's 15-minute grace expires, the earliest-joined connected member becomes host and everyone gets host_changed", async () => {
    const hub = hubStub();
    const { host, bharat, charu } = await family(hub, ["a", "b", "c"]);
    const dev = await joinAndApprove(hub, host.client, host.code, await seedDevice(hub, "d"), "Dev");
    await host.client.next("user_joined");
    await bharat.client.next("user_joined");
    await charu!.client.next("user_joined");

    // Bharat joined first but is away; Charu is the earliest-joined member who is connected.
    bharat.client.close();
    await charu!.client.next("user_disconnected");
    await dev.client.next("user_disconnected");
    host.client.close();
    await charu!.client.next("user_disconnected");
    await dev.client.next("user_disconnected");
    await age(hub, "sangat_members", "disconnectedAt", host.userId, GRACE + MINUTE);

    await runDurableObjectAlarm(hub);

    for (const guest of [charu!, dev]) {
      expect((await guest.client.next("user_left")).payload.user_id).toBe(host.userId);
      expect((await guest.client.next("host_changed")).payload).toEqual({
        new_host_id: charu!.userId,
        new_host_name: "Charu",
      });
    }
    const room = await readRoom(hub, host.code);
    expect(room?.hostUserId).toBe(charu!.userId);
    expect(room?.hostDeviceId).toBe("sg-device-c");
    expect(room?.hostDisconnectedAt).toBeNull();
    expect(room?.state.hostId).toBe(charu!.userId);
    expect(room?.state.users.map((u) => [u.username, u.isHost, u.isConnected])).toEqual([
      ["Bharat", false, false],
      ["Charu", true, true],
      ["Dev", false, true],
    ]);
    // Bharat is still inside his own grace and can come back as a guest.
    expect(await member(hub, bharat.userId)).toMatchObject({ connected: 0, isHost: 0 });
  });

  it("a host who comes back after their grace expired gets session_expired, with the room already handed over", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    host.client.close();
    await bharat.client.next("user_disconnected");
    await age(hub, "sangat_members", "disconnectedAt", host.userId, GRACE + MINUTE);

    const back = await SangatClient.connect(hub, devices[0].token);
    await back.send("reconnect", { session_token: host.token });

    expect((await back.next("error")).payload.code).toBe("session_expired");
    expect((await bharat.client.next("user_left")).payload.user_id).toBe(host.userId);
    expect((await bharat.client.next("host_changed")).payload.new_host_id).toBe(bharat.userId);
  });

  it("a room left without a connected member has no host until someone reconnects", async () => {
    const hub = hubStub();
    const { devices, host, bharat } = await family(hub, ["a", "b"]);
    bharat.client.close();
    await host.client.next("user_disconnected");
    host.client.close();
    await sleep(100);
    await age(hub, "sangat_members", "disconnectedAt", host.userId, GRACE + MINUTE);

    await runDurableObjectAlarm(hub);

    let room = await readRoom(hub, host.code);
    expect(room?.hostUserId).toBe("");
    expect(room?.state.hostId).toBe("");
    expect(room?.state.users.map((u) => [u.username, u.isHost])).toEqual([["Bharat", false]]);

    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: bharat.token });
    expect((await back.next("reconnected")).payload).toMatchObject({ user_id: bharat.userId, is_host: true });
    room = await readRoom(hub, host.code);
    expect(room?.hostUserId).toBe(bharat.userId);
    expect(room?.state.users[0].isHost).toBe(true);
  });
});

describe("message order", () => {
  async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(bytes);
        controller.close();
      },
    });
    return new Uint8Array(await new Response(source.pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  }

  // A valid ping carrying 200 KB of random unknown-field padding. Random bytes
  // do not shrink, so the compressed frame is ~200 KB and the codec, which
  // inflates in 4 KiB slices and yields to the event loop after each, needs
  // dozens of turns: far slower than decoding a plain ping.
  async function slowPingFrame(sequence: number): Promise<Uint8Array> {
    const head = toBinary(PingPayloadSchema, create(PingPayloadSchema, { sequence: BigInt(sequence) }));
    const padding = 200_000;
    const header = new Uint8Array([0x7a, ...varint(padding)]);
    const payload = new Uint8Array(head.length + header.length + padding);
    payload.set(head, 0);
    payload.set(header, head.length);
    for (let offset = head.length + header.length; offset < payload.length; offset += 65536) {
      crypto.getRandomValues(payload.subarray(offset, Math.min(offset + 65536, payload.length)));
    }
    return toBinary(
      EnvelopeSchema,
      create(EnvelopeSchema, { type: "ping", payload: await gzip(payload), compressed: true }),
    );
  }

  function varint(value: number): number[] {
    const out: number[] = [];
    let rest = value;
    while (rest >= 0x80) {
      out.push((rest & 0x7f) | 0x80);
      rest = Math.floor(rest / 128);
    }
    out.push(rest);
    return out;
  }

  it("a compressed (slow) message followed immediately by a plain one on the same socket is handled in send order", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    client.sendRaw(await slowPingFrame(1));
    await client.send("ping", { sequence: 2 });
    await client.send("ping", { sequence: 3 });

    const sequences = [
      (await client.next("pong")).payload.sequence,
      (await client.next("pong")).payload.sequence,
      (await client.next("pong")).payload.sequence,
    ];
    expect(sequences).toEqual(["1", "2", "3"]);
  });

  it("a message that fails to decode does not stall the ones behind it", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    const notGzip = toBinary(
      EnvelopeSchema,
      create(EnvelopeSchema, { type: "ping", payload: new Uint8Array([1, 2, 3, 4]), compressed: true }),
    );

    client.sendRaw(notGzip);
    await client.send("ping", { sequence: 9 });

    expect((await client.next("error")).payload.code).toBe("invalid_message");
    expect((await client.next("pong")).payload.sequence).toBe("9");
  });

  it("a handler that throws is answered server_error and does not stall the messages behind it", async () => {
    const hub = hubStub();
    const { host, bharat } = await family(hub, ["a", "b"]);
    await sql(hub, (db) => db.exec("DROP TABLE sangat_suggestions"));
    try {
      await bharat.client.send("suggest_track", { track_info: track(1) });
      await bharat.client.send("ping", { sequence: 5 });

      expect((await bharat.client.next("error")).payload).toEqual({
        code: "server_error",
        message: "Internal error",
      });
      expect((await bharat.client.next("pong")).payload.sequence).toBe("5");
      expect(await host.client.quiet()).toEqual([]);
    } finally {
      await sql(hub, (db) => createSangatTables(db));
    }
  });
});
