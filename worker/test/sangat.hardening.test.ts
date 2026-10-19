// Regressions found in review of the Sangat rooms and lifecycle work: sockets
// the server has closed, a hub that restarted under live rooms, per-device caps,
// a backed-up socket, an alarm that must survive a bad room, an oversized
// RoomState, and a reconnect that fails after taking a socket over.

import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import {
  evictAllDurableObjects,
  runDurableObjectAlarm,
  runInDurableObject,
} from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  CreateRoomPayloadSchema,
  EnvelopeSchema,
  JoinRoomPayloadSchema,
  PingPayloadSchema,
  RoomStateSchema,
  TrackInfoSchema,
} from "../src/gen/listentogether_pb";
import {
  MAX_PENDING_BYTES_PER_SOCKET,
  MAX_PENDING_BYTES_TOTAL,
  MAX_ROOM_STATE_BYTES,
  MAX_ROOMS_PER_DEVICE,
  MAX_SOCKETS_PER_DEVICE,
  MAX_PENDING_MESSAGES_PER_SOCKET,
  QUEUE_STATE_HEADROOM_BYTES,
  RATE_LIMIT_STRIKES_TO_CLOSE,
} from "../src/sangat/limits";
import { createSangatTables } from "../src/sangat/schema";
import { Sangat } from "../src/sangat/Sangat";
import { Store } from "../src/sangat/store";
import {
  closeAllClients,
  createRoom,
  HUB_ORIGIN,
  hubStub,
  joinAndApprove,
  openFamily,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
  wsRequest,
  type Hub,
} from "./sangat.helpers";

beforeEach(resetHub);
afterEach(closeAllClients);

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const MINUTE = 60_000;
const GRACE = 15 * MINUTE;

async function readRoom(hub: Hub, code: string) {
  const row = await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms WHERE code = ?", code).one());
  return { state: fromBinary(RoomStateSchema, new Uint8Array(row.state as ArrayBuffer)) };
}

const liveSangat = (hub: Hub) =>
  runInDurableObject(hub, (instance) => (instance as unknown as { sangat: Sangat }).sangat);

const getAlarm = (hub: Hub) => runInDurableObject(hub, (_instance, state) => state.storage.getAlarm());

async function until(check: () => Promise<boolean>, what: string, timeoutMs = 5000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(5);
  }
}

// The bytes of messages the hub has received and not finished with (Sangat counts them from arrival).
const heldBytes = (hub: Hub) =>
  runInDurableObject(hub, (instance) => (instance as unknown as { sangat: { totalWaitingBytes: number } }).sangat.totalWaitingBytes);

/** Waits until the hub holds at least `bytes` of received messages: the frame has arrived and is being handled. */
const received = (hub: Hub, bytes: number) =>
  until(async () => (await heldBytes(hub)) >= bytes, "the hub to receive the frame");

/** Waits until every message the hub received has been handled or dropped. */
const drained = (hub: Hub) => until(async () => (await heldBytes(hub)) === 0, "the queued messages to be dealt with");

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

async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
  return new Uint8Array(await new Response(source.pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
}

/**
 * A valid message whose payload carries random unknown-field padding. Random
 * bytes do not shrink, so the codec, which inflates in 4 KiB slices and waits a
 * turn after each, needs dozens of turns to decode it.
 */
async function slowFrame(type: string, payload: Uint8Array, padding = 400_000): Promise<Uint8Array> {
  const header = new Uint8Array([0x7a, ...varint(padding)]);
  const body = new Uint8Array(payload.length + header.length + padding);
  body.set(payload, 0);
  body.set(header, payload.length);
  for (let offset = payload.length + header.length; offset < body.length; offset += 65536) {
    crypto.getRandomValues(body.subarray(offset, Math.min(offset + 65536, body.length)));
  }
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload: await gzip(body), compressed: true }));
}

const slowPing = () => slowFrame("ping", toBinary(PingPayloadSchema, create(PingPayloadSchema, { sequence: 1n })));

describe("a socket the server has closed", () => {
  it("jobs already queued from a revoked device are dropped: no room is created", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    await client.send("ping");
    await client.next("pong");

    const queued = await slowFrame("create_room", toBinary(CreateRoomPayloadSchema, create(CreateRoomPayloadSchema, { username: "Asha" })), 1000);
    client.sendRaw(await slowPing());
    client.sendRaw(queued);
    await received(hub, queued.byteLength);
    const sangat = await liveSangat(hub);
    await runInDurableObject(hub, () => sangat.closeDevice(device.deviceId, 4001, "revoked"));
    expect(await client.closed).toEqual({ code: 4001, reason: "revoked" });
    await drained(hub);

    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms").one().n)).toBe(0);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_members").one().n)).toBe(0);
  });

  it("a queued join_room from a revoked device reaches nobody", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    const intruder = await seedDevice(hub, "z");
    const client = await SangatClient.connect(hub, intruder.token);
    await client.send("ping");
    await client.next("pong");

    const queued = await slowFrame(
      "join_room",
      toBinary(JoinRoomPayloadSchema, create(JoinRoomPayloadSchema, { roomCode: host.code, username: "Zed" })),
      1000,
    );
    client.sendRaw(await slowPing());
    client.sendRaw(queued);
    await received(hub, queued.byteLength);
    const sangat = await liveSangat(hub);
    await runInDurableObject(hub, () => sangat.closeDevice(intruder.deviceId, 4001, "revoked"));
    await drained(hub);

    expect(await host.client.quiet(0)).toEqual([]);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_pending_joins").one().n)).toBe(0);
  });

  it("a message still being decoded when its device is revoked does not act: no room is created", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    await client.send("ping");
    await client.next("pong");

    // Its own decode takes a while; the revoke lands in the middle of it.
    const slow = await slowFrame("create_room", toBinary(CreateRoomPayloadSchema, create(CreateRoomPayloadSchema, { username: "Asha" })), 400_000);
    client.sendRaw(slow);
    await received(hub, slow.byteLength);
    const sangat = await liveSangat(hub);
    await runInDurableObject(hub, () => sangat.closeDevice(device.deviceId, 4001, "revoked"));
    expect(await client.closed).toEqual({ code: 4001, reason: "revoked" });
    await drained(hub);

    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms").one().n)).toBe(0);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_members WHERE deviceId = ?", device.deviceId).one().n)).toBe(0);
  });

  it("a revoke over HTTP leaves a message still being decoded inert: no room is created", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    await client.send("ping");
    await client.next("pong");

    const slow = await slowFrame("create_room", toBinary(CreateRoomPayloadSchema, create(CreateRoomPayloadSchema, { username: "Asha" })), 400_000);
    client.sendRaw(slow);
    await received(hub, slow.byteLength);
    const revoked = await hub.fetch(`${HUB_ORIGIN}/roster/${device.deviceId}/revoke`, {
      method: "POST",
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });
    expect(revoked.status).toBe(200);
    await revoked.text();
    expect(await client.closed).toEqual({ code: 4001, reason: "revoked" });
    await drained(hub);

    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms").one().n)).toBe(0);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_members").one().n)).toBe(0);
  });

  it("closeDevice closes the sockets before it touches storage: an unreadable room cannot leave a revoked phone connected", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_rooms SET state = ? WHERE code = ?", new Uint8Array([0xff, 0xff, 0xff, 0xff]).buffer, host.code),
    );
    const sangat = await liveSangat(hub);

    const outcome = await runInDurableObject(hub, (_instance, state) => {
      let threw = false;
      try {
        sangat.closeDevice(devices[1].deviceId, 4001, "revoked");
      } catch {
        threw = true;
      }
      const stamps = state
        .getWebSockets()
        .map((ws) => ws.deserializeAttachment() as { deviceId: string; closed?: boolean })
        .map((att) => [att.deviceId, att.closed ?? false] as const);
      // The unreadable room would make every later close event throw; clear it away.
      state.storage.sql.exec("DELETE FROM sangat_members");
      state.storage.sql.exec("DELETE FROM sangat_rooms");
      return { threw, stamps };
    });

    expect(outcome.threw).toBe(false);
    expect(outcome.stamps).toEqual(expect.arrayContaining([[devices[1].deviceId, true], [devices[0].deviceId, false]]));
    expect(await guests[0].client.closed).toEqual({ code: 4001, reason: "revoked" });
  });

  it("closeAll closes every socket even when clearing the rooms fails", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    await sql(hub, (db) => db.exec("DROP TABLE sangat_suggestions"));
    const sangat = await liveSangat(hub);

    const threw = await runInDurableObject(hub, (_instance, state) => {
      let failed = false;
      try {
        sangat.closeAll(4003, "sangat disabled");
      } catch {
        failed = true;
      }
      createSangatTables(state.storage.sql);
      return failed;
    });

    expect(threw).toBe(false);
    expect(await host.client.closed).toEqual({ code: 4003, reason: "sangat disabled" });
    expect(await guests[0].client.closed).toEqual({ code: 4003, reason: "sangat disabled" });
  });

  it("closeDevice and closeAll write the tombstone on the sockets they close", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    const first = await SangatClient.connect(hub, a.token);
    const second = await SangatClient.connect(hub, b.token);
    await first.send("ping");
    await first.next("pong");
    await second.send("ping");
    await second.next("pong");

    const stamps = await runInDurableObject(hub, (_instance, state) => {
      const sangat = new Sangat(state);
      const read = () =>
        state.getWebSockets().map((ws) => {
          const att = ws.deserializeAttachment() as { deviceId: string; closed?: boolean };
          return [att.deviceId, att.closed ?? false] as const;
        });
      sangat.closeDevice(a.deviceId, 4001, "revoked");
      const afterRevoke = read();
      sangat.closeAll(4003, "sangat disabled");
      return { afterRevoke, afterAll: read() };
    });

    expect(stamps.afterRevoke).toEqual(expect.arrayContaining([[a.deviceId, true], [b.deviceId, false]]));
    expect(stamps.afterAll.every(([, closed]) => closed)).toBe(true);
    expect(await first.closed).toEqual({ code: 4001, reason: "revoked" });
    expect(await second.closed).toEqual({ code: 4003, reason: "sangat disabled" });
  });
});

describe("a hub that restarted under live rooms", () => {
  async function evictedHub() {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    await evictAllDurableObjects({ webSockets: "close" });
    return { hub, devices, host, guests };
  }

  it("liveRooms leaves out a room whose host socket is gone, and starts that host's grace", async () => {
    const { hub, host } = await evictedHub();

    const rooms = await runInDurableObject(hub, (instance) =>
      (instance as unknown as { sangat: Sangat }).sangat.liveRooms(),
    );
    await sleep(100);

    expect(rooms).toEqual([]);
    const row = await sql(hub, (db) => db.exec("SELECT * FROM sangat_members WHERE userId = ?", host.userId).one());
    expect(row.connected).toBe(0);
    // An alarm that is already earlier is left in place; it only has to come no later than the grace.
    expect(await getAlarm(hub)).toBeLessThanOrEqual((row.disconnectedAt as number) + GRACE);
    expect(await getAlarm(hub)).not.toBeNull();
  });

  it("setCarMode does the same before it answers", async () => {
    const { hub, devices, host } = await evictedHub();

    await runInDurableObject(hub, (instance) =>
      (instance as unknown as { sangat: Sangat }).sangat.setCarMode(host.code, devices[0].deviceId, true),
    );
    await sleep(100);

    expect((await sql(hub, (db) => db.exec("SELECT connected FROM sangat_members WHERE userId = ?", host.userId).one())).connected).toBe(0);
    expect(await getAlarm(hub)).not.toBeNull();
  });

  it("a reconcile that fails is tried again on the next call", async () => {
    const { hub, host } = await evictedHub();
    const original = (await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms WHERE code = ?", host.code).one())).state as ArrayBuffer;
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_rooms SET state = ? WHERE code = ?", new Uint8Array([0xff, 0xff, 0xff, 0xff]).buffer, host.code),
    );

    const result = await runInDurableObject(hub, (_instance, state) => {
      const sangat = new Sangat(state);
      let first = "ok";
      try {
        sangat.liveRooms();
      } catch {
        first = "threw";
      }
      state.storage.sql.exec("UPDATE sangat_rooms SET state = ? WHERE code = ?", original, host.code);
      const second = sangat.liveRooms();
      const connected = state.storage.sql.exec<{ connected: number }>("SELECT connected FROM sangat_members").toArray();
      return { first, second, connected: connected.map((row) => row.connected) };
    });

    expect(result.first).toBe("threw");
    expect(result.second).toEqual([]);
    expect(result.connected).toEqual([0, 0]);
  });

  it("a new connection finds it done, without anyone sending a message", async () => {
    const { hub, devices, host, guests } = await evictedHub();

    await SangatClient.connect(hub, devices[1].token);
    await sleep(100);

    for (const userId of [host.userId, guests[0].userId]) {
      expect((await sql(hub, (db) => db.exec("SELECT connected FROM sangat_members WHERE userId = ?", userId).one())).connected).toBe(0);
    }
    expect(await getAlarm(hub)).not.toBeNull();
  });
});

describe("per-device caps", () => {
  it("a device with 4 live sockets is refused a fifth before the upgrade", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const sockets: SangatClient[] = [];
    for (let i = 0; i < MAX_SOCKETS_PER_DEVICE; i++) sockets.push(await SangatClient.connect(hub, device.token));
    expect(MAX_SOCKETS_PER_DEVICE).toBe(4);

    const refused = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(device.token));
    expect(refused.status).toBe(429);
    expect(await refused.json()).toEqual({ error: "too many connections" });

    // Another device is unaffected, and a freed slot can be used again.
    const other = await seedDevice(hub, "b");
    await SangatClient.connect(hub, other.token);
    sockets[0].close();
    await sleep(150);
    await SangatClient.connect(hub, device.token);
  });

  it("a device hosts at most 2 open rooms: the third create_room answers room_limit_reached", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    expect(MAX_ROOMS_PER_DEVICE).toBe(2);
    for (let i = 0; i < 2; i++) await createRoom(hub, device, `Asha${i}`);

    const third = await SangatClient.connect(hub, device.token);
    await third.send("create_room", { username: "Asha2" });

    expect((await third.next("error")).payload.code).toBe("room_limit_reached");
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms").one().n)).toBe(2);
    // Another device may still host.
    await createRoom(hub, await seedDevice(hub, "b"), "Bharat");
  });

  it("a create/leave loop is bounded: emptied rooms awaiting deletion count, and stop counting once deleted", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    for (let i = 0; i < 2; i++) {
      await client.send("create_room", { username: "Asha" });
      await client.next("room_created");
      await client.send("leave_room");
      await sleep(30);
    }

    await client.send("create_room", { username: "Asha" });
    expect((await client.next("error")).payload.code).toBe("room_limit_reached");

    await sql(hub, (db) => db.exec("UPDATE sangat_rooms SET emptySince = ?", Date.now() - 6 * MINUTE));
    await runDurableObjectAlarm(hub);
    await client.send("create_room", { username: "Asha" });
    await client.next("room_created");
  });

  describe("transfer_host", () => {
    async function hostOf(hub: Hub, code: string) {
      return sql(hub, (db) =>
        db.exec<{ hostUserId: string; hostDeviceId: string }>("SELECT hostUserId, hostDeviceId FROM sangat_rooms WHERE code = ?", code).one(),
      );
    }

    it("refuses a target whose device already hosts 2 rooms, with room_limit_reached, and changes nothing", async () => {
      const hub = hubStub();
      const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
      const host = await createRoom(hub, a);
      const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
      await host.client.next("user_joined");
      // Bharat's device hosts two rooms of its own, on other sockets.
      const own = [await createRoom(hub, b, "B1"), await createRoom(hub, b, "B2")];
      expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_rooms WHERE hostDeviceId = ?", b.deviceId).one().n)).toBe(MAX_ROOMS_PER_DEVICE);

      await host.client.send("transfer_host", { new_host_id: bharat.userId });

      expect((await host.client.next("error")).payload).toEqual({
        code: "room_limit_reached",
        message: "That device is hosting as many rooms as it may",
      });
      expect(await hostOf(hub, host.code)).toEqual({ hostUserId: host.userId, hostDeviceId: a.deviceId });
      expect(await host.client.quiet(100)).toEqual([]);
      expect(await bharat.client.quiet(0)).toEqual([]);

      // Once one of those rooms is gone for good, the handover goes through.
      await own[0].client.send("leave_room");
      await sleep(30);
      await sql(hub, (db) => db.exec("UPDATE sangat_rooms SET emptySince = ? WHERE emptySince IS NOT NULL", Date.now() - 6 * MINUTE));
      await runDurableObjectAlarm(hub);
      await host.client.send("transfer_host", { new_host_id: bharat.userId });
      expect((await host.client.next("host_changed")).payload.new_host_id).toBe(bharat.userId);
      expect(await hostOf(hub, host.code)).toEqual({ hostUserId: bharat.userId, hostDeviceId: b.deviceId });
    });

    it("counts rooms awaiting deletion, as create_room does", async () => {
      const hub = hubStub();
      const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
      const host = await createRoom(hub, a);
      const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
      await host.client.next("user_joined");
      for (let i = 0; i < MAX_ROOMS_PER_DEVICE; i++) {
        const own = await createRoom(hub, b, `B${i}`);
        await own.client.send("leave_room");
        await sleep(30);
      }

      await host.client.send("transfer_host", { new_host_id: bharat.userId });

      expect((await host.client.next("error")).payload.code).toBe("room_limit_reached");
    });

    it("lets a device that is under the cap receive a room, and one at the cap hand it to its own other socket", async () => {
      const hub = hubStub();
      const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
      const host = await createRoom(hub, a);
      await createRoom(hub, a, "Asha2"); // device a is now at the cap
      const sameDevice = await joinAndApprove(hub, host.client, host.code, a, "Asha's tablet");
      await host.client.next("user_joined");
      const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
      await host.client.next("user_joined");
      await sameDevice.client.next("user_joined");
      await createRoom(hub, b, "B1"); // device b has one room: one below the cap

      await host.client.send("transfer_host", { new_host_id: bharat.userId });
      expect((await host.client.next("host_changed")).payload.new_host_id).toBe(bharat.userId);
      await bharat.client.next("host_changed");
      expect((await hostOf(hub, host.code)).hostDeviceId).toBe(b.deviceId);

      // Device b is now at the cap too; its other socket may still take over from it.
      const second = await joinAndApprove(hub, bharat.client, host.code, b, "Bharat's tablet");
      await bharat.client.next("user_joined");
      await bharat.client.send("transfer_host", { new_host_id: second.userId });
      expect((await bharat.client.next("host_changed")).payload.new_host_id).toBe(second.userId);
      expect((await hostOf(hub, host.code)).hostDeviceId).toBe(b.deviceId);
    });
  });
});

const frameBytes = (type: string): ArrayBuffer => {
  const frame = toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload: new Uint8Array() }));
  return frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength) as ArrayBuffer;
};

describe("backpressure", () => {
  // A stand-in socket: the real ones cannot be made to fall behind on demand.
  function fakeSocket(deviceId: string, userId = "user_1_1") {
    let attachment: Record<string, unknown> = { userId, deviceId, lastSeen: Date.now() };
    const closes: [number, string][] = [];
    const ws = {
      readyState: 1,
      deserializeAttachment: () => structuredClone(attachment),
      serializeAttachment: (value: Record<string, unknown>) => {
        attachment = structuredClone(value);
      },
      send: () => undefined,
      close: (code: number, reason: string) => {
        closes.push([code, reason]);
        ws.readyState = 3;
      },
    };
    return {
      ws: ws as unknown as WebSocket,
      closes,
      resetRate: () => delete attachment.rateCount,
      // What closeDevice and cutOff write on a socket they close, and its undoing.
      tombstone: () => {
        attachment.closed = true;
      },
      reopen: () => delete attachment.closed,
      // Makes the rate-limit window look over, the next one following straight on.
      endWindow: () => {
        attachment.rateStart = Date.now() - 1100;
      },
    };
  }

  const junk = (bytes: number) => new ArrayBuffer(bytes);

  it("closes a socket with 1008 'backpressure' once 64 of its messages are waiting", async () => {
    const hub = hubStub();
    const frame = toBinary(EnvelopeSchema, create(EnvelopeSchema, { type: "ping", payload: new Uint8Array() }));
    const bytes = frame.buffer.slice(frame.byteOffset, frame.byteOffset + frame.byteLength) as ArrayBuffer;

    const closes = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes, resetRate } = fakeSocket("sg-device-fake");
      expect(MAX_PENDING_MESSAGES_PER_SOCKET).toBe(64);
      const jobs: Promise<void>[] = [];
      // 64 fit; the rate limiter (60 per second) is sidestepped by resetting its count.
      for (let i = 0; i < 64; i++) {
        if (i % 50 === 0) resetRate();
        jobs.push(sangat.onMessage(ws, bytes));
      }
      expect(closes).toEqual([]);
      resetRate();
      jobs.push(sangat.onMessage(ws, bytes));
      const afterCap = [...closes];
      await Promise.all(jobs);
      return afterCap;
    });

    expect(closes).toEqual([[1008, "backpressure"]]);
  });

  it("closes a socket with 1008 'backpressure' once 2 MB of its messages are waiting", async () => {
    const hub = hubStub();
    expect(MAX_PENDING_BYTES_PER_SOCKET).toBe(2 * 1024 * 1024);

    const result = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes } = fakeSocket("sg-device-fake");
      const jobs: Promise<void>[] = [];
      for (let i = 0; i < 5; i++) jobs.push(sangat.onMessage(ws, junk(400_000)));
      const withinBudget = [...closes];
      jobs.push(sangat.onMessage(ws, junk(400_000)));
      const afterCap = [...closes];
      await Promise.all(jobs);
      // What was waiting has been handled, so the budget is whole again for the next socket.
      const other = fakeSocket("sg-device-fake", "user_2_2");
      const more = [0, 1, 2, 3, 4].map(() => sangat.onMessage(other.ws, junk(400_000)));
      await Promise.all(more);
      return { withinBudget, afterCap, otherCloses: other.closes };
    });

    expect(result.withinBudget).toEqual([]);
    expect(result.afterCap).toEqual([[1008, "backpressure"]]);
    expect(result.otherCloses).toEqual([]);
  });

  it("closes a socket with 1008 'backpressure' once 8 MB are waiting across all sockets, whoever sent them", async () => {
    const hub = hubStub();
    expect(MAX_PENDING_BYTES_TOTAL).toBe(8 * 1024 * 1024);

    const result = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const jobs: Promise<void>[] = [];
      // Four sockets each hold 2,000,000 bytes: inside their own 2 MB, 8,000,000 of 8 MB together.
      const holders = [1, 2, 3, 4].map((n) => fakeSocket(`sg-device-fake${n}`, `user_${n}_${n}`));
      for (const holder of holders) {
        for (let i = 0; i < 5; i++) jobs.push(sangat.onMessage(holder.ws, junk(400_000)));
      }
      const latecomer = fakeSocket("sg-device-fake5", "user_5_5");
      jobs.push(sangat.onMessage(latecomer.ws, junk(400_000)));
      const afterCap = { holders: holders.map((h) => [...h.closes]), latecomer: [...latecomer.closes] };
      await Promise.all(jobs);
      const next = fakeSocket("sg-device-fake6", "user_6_6");
      await sangat.onMessage(next.ws, junk(400_000));
      return { afterCap, nextCloses: next.closes };
    });

    expect(result.afterCap.holders).toEqual([[], [], [], []]);
    expect(result.afterCap.latecomer).toEqual([[1008, "backpressure"]]);
    expect(result.nextCloses).toEqual([]);
  });

  const fiveBig = (sangat: Sangat, ws: WebSocket) => [0, 1, 2, 3, 4].map(() => sangat.onMessage(ws, junk(400_000)));

  it("a message whose handler throws still gives its bytes back: the same socket has its whole budget afterwards", async () => {
    const hub = hubStub();

    const result = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes } = fakeSocket("sg-device-fake");
      const first = fiveBig(sangat, ws);
      // The last thing each handler does is read the deadlines; with the table gone it throws.
      state.storage.sql.exec("DROP TABLE sangat_rooms");
      const settled = await Promise.allSettled(first);
      createSangatTables(state.storage.sql);
      const again = fiveBig(sangat, ws);
      const afterAgain = [...closes];
      await Promise.all(again);
      return { rejected: settled.filter((outcome) => outcome.status === "rejected").length, afterAgain };
    });

    expect(result.rejected).toBe(5);
    expect(result.afterAgain).toEqual([]);
  });

  it("a socket whose close event is queued behind its messages has its whole budget once they are done", async () => {
    const hub = hubStub();

    const closes = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes } = fakeSocket("sg-device-fake");
      await Promise.all([...fiveBig(sangat, ws), sangat.onClose(ws)]);
      await Promise.all(fiveBig(sangat, ws));
      return closes;
    });

    expect(closes).toEqual([]);
  });

  it("a socket the server closed while messages were waiting has its whole budget once they are dropped", async () => {
    const hub = hubStub();

    const backpressure = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes, tombstone, reopen } = fakeSocket("sg-device-fake");
      const waiting = fiveBig(sangat, ws);
      tombstone();
      await Promise.all(waiting);
      // Looked at again as if it were open: nothing of the dropped messages is still counted against it.
      reopen();
      await Promise.all(fiveBig(sangat, ws));
      return closes.filter(([, reason]) => reason === "backpressure");
    });

    expect(backpressure).toEqual([]);
  });

  it("closes a socket with 1008 'rate limited' after 5 consecutive windows of refused messages", async () => {
    const hub = hubStub();
    expect(RATE_LIMIT_STRIKES_TO_CLOSE).toBe(5);
    const ping = frameBytes("ping");

    const result = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes, endWindow } = fakeSocket("sg-device-fake");
      const afterWindow: [number, string][][] = [];
      for (let window = 1; window <= RATE_LIMIT_STRIKES_TO_CLOSE; window++) {
        const jobs: Promise<void>[] = [];
        for (let i = 0; i < 70; i++) jobs.push(sangat.onMessage(ws, ping));
        afterWindow.push([...closes]);
        await Promise.all(jobs);
        endWindow();
      }
      return afterWindow;
    });

    expect(result.slice(0, 4)).toEqual([[], [], [], []]);
    expect(result[4]).toEqual([[1008, "rate limited"]]);
  });

  it("a window without a refusal between flooded ones starts the count again", async () => {
    const hub = hubStub();
    const ping = frameBytes("ping");

    const closes = await runInDurableObject(hub, async (_instance, state) => {
      const sangat = new Sangat(state);
      const { ws, closes, endWindow } = fakeSocket("sg-device-fake");
      const flood = async (count: number) => {
        const jobs: Promise<void>[] = [];
        for (let i = 0; i < count; i++) jobs.push(sangat.onMessage(ws, ping));
        await Promise.all(jobs);
        endWindow();
      };
      for (let round = 0; round < 3; round++) {
        for (let i = 0; i < RATE_LIMIT_STRIKES_TO_CLOSE - 1; i++) await flood(70);
        await flood(10); // a clean window
      }
      return closes;
    });

    expect(closes).toEqual([]);
  });

  it("counts only messages still waiting: a socket that keeps up is never cut off", async () => {
    const hub = hubStub();
    const client = await SangatClient.connect(hub, (await seedDevice(hub, "a")).token);
    for (let i = 1; i <= 150; i++) {
      await client.send("ping", { sequence: i });
      await client.next("pong");
      if (i % 50 === 0) await sleep(1000);
    }
    await client.send("ping", { sequence: 999 });
    expect((await client.next("pong")).payload.sequence).toBe("999");
  }, 30_000);
});

describe("the alarm", () => {
  it("one room that cannot be swept does not roll back the others' expiries, and the alarm is re-armed without a hot loop", async () => {
    const hub = hubStub();
    const one = await openFamily(hub, ["a", "b"]);
    const two = await openFamily(hub, ["c", "d"], "Dev");
    for (const guest of [one.guests[0], two.guests[0]]) guest.client.close();
    await one.host.client.next("user_disconnected");
    await two.host.client.next("user_disconnected");
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_members SET disconnectedAt = ? WHERE connected = 0", Date.now() - GRACE - MINUTE),
    );
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_rooms SET state = ? WHERE code = ?", new Uint8Array([0xff, 0xff, 0xff, 0xff]).buffer, two.host.code),
    );

    expect(await runDurableObjectAlarm(hub)).toBe(true);

    expect(await sql(hub, (db) => db.exec("SELECT userId FROM sangat_members WHERE userId = ?", one.guests[0].userId).toArray())).toEqual([]);
    expect((await one.host.client.next("user_left")).payload.user_id).toBe(one.guests[0].userId);
    // The broken room's member is still there, and the alarm comes back later, not at once.
    expect(await sql(hub, (db) => db.exec("SELECT userId FROM sangat_members WHERE userId = ?", two.guests[0].userId).toArray())).toHaveLength(1);
    const alarm = (await getAlarm(hub)) as number;
    expect(alarm).toBeGreaterThan(Date.now() + 50_000);

    // The unreadable room would make every later close event throw; clear it away.
    await sql(hub, (db) => {
      db.exec("DELETE FROM sangat_members");
      db.exec("DELETE FROM sangat_rooms");
    });
  });
});

describe("RoomState size", () => {
  const big = (n: number) =>
    create(TrackInfoSchema, {
      id: `big-${n}`,
      title: "T".repeat(200),
      artist: "A".repeat(200),
      album: "B".repeat(200),
      thumbnail: `https://example.test/${"x".repeat(1500)}`,
      duration: 1000n,
    });

  // Fills the stored queue with big tracks, then tiny ones, until the state is
  // `headroom` bytes short of the cap.
  async function fill(hub: Hub, code: string, headroom: number): Promise<number> {
    return runInDurableObject(hub, (_instance, state) => {
      const store = new Store(state.storage);
      const room = store.getRoom(code);
      if (!room) throw new Error("no room");
      const size = () => toBinary(RoomStateSchema, room.state).byteLength;
      const perTrack = toBinary(TrackInfoSchema, big(0)).byteLength + 3;
      const target = MAX_ROOM_STATE_BYTES - headroom;
      let n = 0;
      for (let count = Math.floor((target - size()) / perTrack) - 2; count > 0; count--) room.state.queue.push(big(n++));
      while (size() + perTrack <= target) room.state.queue.push(big(n++));
      for (let pad = 0; size() < target; pad++) {
        room.state.queue.push(create(TrackInfoSchema, { id: `pad-${pad}`, title: "p", duration: 1000n }));
      }
      store.saveRoom(room);
      return room.state.queue.length;
    });
  }

  it("a queue change that would pass 1,483,616 bytes answers queue_full and changes nothing", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    await host.client.send("playback_action", { action: "change_track", track_info: { id: "t0", title: "Now" } });
    await host.client.next("sync_playback");
    // Queue growth stops QUEUE_STATE_HEADROOM_BYTES under the cap; the state starts 1,500 bytes under that.
    const count = await fill(hub, host.code, QUEUE_STATE_HEADROOM_BYTES + 1500);
    expect(MAX_ROOM_STATE_BYTES).toBe(1_500_000);
    expect(QUEUE_STATE_HEADROOM_BYTES).toBe(16_384);
    expect(count).toBeLessThanOrEqual(1000);

    await host.client.send("playback_action", {
      action: "queue_add",
      track_info: { id: "huge", title: "T".repeat(200), artist: "A".repeat(200), album: "B".repeat(200), thumbnail: `https://example.test/${"y".repeat(2000)}` },
    });
    expect((await host.client.next("error")).payload).toEqual({ code: "queue_full", message: "Queue is full" });
    expect((await readRoom(hub, host.code)).state.queue).toHaveLength(count);

    // A small one still fits, and the stored state stays under the cap.
    await host.client.send("playback_action", { action: "queue_add", track_info: { id: "small", title: "Small" } });
    expect((await host.client.next("sync_playback")).payload.action).toBe("queue_add");
    const saved = await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms").one());
    expect((saved.state as ArrayBuffer).byteLength).toBeLessThanOrEqual(MAX_ROOM_STATE_BYTES);
  });

  it("inside the headroom, queue growth is refused but approve_join, reconnect and pause still fit", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", { action: "change_track", track_info: { id: "t0", title: "Now" } });
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    await fill(hub, host.code, 4000);
    const stored = () => sql(hub, (db) => db.exec("SELECT length(state) AS n FROM sangat_rooms").one().n);
    expect(await stored()).toBeGreaterThan(MAX_ROOM_STATE_BYTES - QUEUE_STATE_HEADROOM_BYTES);

    await host.client.send("playback_action", { action: "queue_add", track_info: { id: "small", title: "Small" } });
    expect((await host.client.next("error")).payload.code).toBe("queue_full");
    await host.client.send("playback_action", { action: "pause", position: 1000 });
    expect((await host.client.next("sync_playback")).payload.action).toBe("pause");
    await bharat.client.next("sync_playback");

    const newcomer = await SangatClient.connect(hub, (await seedDevice(hub, "c")).token);
    await newcomer.send("join_room", { room_code: host.code, username: "Charu" });
    const request = await host.client.next("join_request");
    await host.client.send("approve_join", { user_id: request.payload.user_id });
    await newcomer.next("join_approved");

    bharat.client.close();
    await host.client.next("user_joined");
    await host.client.next("user_disconnected");
    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: bharat.token });
    expect((await back.next("reconnected")).payload.room_code).toBe(host.code);
    expect(await stored()).toBeLessThanOrEqual(MAX_ROOM_STATE_BYTES);
  });

  it("approving a suggestion that would pass the cap answers queue_full and keeps the suggestion", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await fill(hub, host.code, 1500);
    await bharat.client.send("suggest_track", {
      track_info: { id: "huge", title: "T".repeat(200), artist: "A".repeat(200), album: "B".repeat(200), thumbnail: `https://example.test/${"y".repeat(2000)}` },
    });
    const received = (await host.client.next("suggestion_received")).payload;

    await host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    expect((await host.client.next("error")).payload.code).toBe("queue_full");
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_suggestions").one().n)).toBe(1);
  });
});

describe("a reconnect that fails after taking a socket over", () => {
  it("leaves the older socket open and its identity alone", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a", "b"]);
    const newer = await SangatClient.connect(hub, devices[0].token);
    await sql(hub, (db) => db.exec("DROP TABLE sangat_suggestions"));
    try {
      await newer.send("reconnect", { session_token: host.token });
      expect((await newer.next("error")).payload).toEqual({ code: "server_error", message: "Internal error" });
      await sleep(150);

      let closed = false;
      host.client.closed.then(() => {
        closed = true;
      });
      await sleep(50);
      expect(closed).toBe(false);
      await host.client.send("ping", { sequence: 5 });
      expect((await host.client.next("pong")).payload.sequence).toBe("5");
      expect((await sql(hub, (db) => db.exec("SELECT connected FROM sangat_members WHERE userId = ?", host.userId).one())).connected).toBe(1);
    } finally {
      await sql(hub, (db) => createSangatTables(db));
    }
  });
});
