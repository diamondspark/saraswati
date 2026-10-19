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
  MAX_ROOM_STATE_BYTES,
  MAX_ROOMS_PER_DEVICE,
  MAX_SOCKETS_PER_DEVICE,
  MAX_PENDING_MESSAGES_PER_SOCKET,
} from "../src/sangat/limits";
import { createSangatTables } from "../src/sangat/schema";
import { Sangat } from "../src/sangat/Sangat";
import { Store } from "../src/sangat/store";
import {
  closeAllClients,
  createRoom,
  HUB_ORIGIN,
  hubStub,
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

const MINUTE = 60_000;
const GRACE = 15 * MINUTE;

async function readRoom(hub: Hub, code: string) {
  const row = await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms WHERE code = ?", code).one());
  return { state: fromBinary(RoomStateSchema, new Uint8Array(row.state as ArrayBuffer)) };
}

const liveSangat = (hub: Hub) =>
  runInDurableObject(hub, (instance) => (instance as unknown as { sangat: Sangat }).sangat);

const getAlarm = (hub: Hub) => runInDurableObject(hub, (_instance, state) => state.storage.getAlarm());

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

    client.sendRaw(await slowPing());
    client.sendRaw(await slowFrame("create_room", toBinary(CreateRoomPayloadSchema, create(CreateRoomPayloadSchema, { username: "Asha" })), 1000));
    await sleep(30);
    const sangat = await liveSangat(hub);
    await runInDurableObject(hub, () => sangat.closeDevice(device.deviceId, 4001, "revoked"));
    expect(await client.closed).toEqual({ code: 4001, reason: "revoked" });
    await sleep(700);

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

    client.sendRaw(await slowPing());
    client.sendRaw(
      await slowFrame(
        "join_room",
        toBinary(JoinRoomPayloadSchema, create(JoinRoomPayloadSchema, { roomCode: host.code, username: "Zed" })),
        1000,
      ),
    );
    await sleep(30);
    const sangat = await liveSangat(hub);
    await runInDurableObject(hub, () => sangat.closeDevice(intruder.deviceId, 4001, "revoked"));
    await sleep(700);

    expect(await host.client.quiet(0)).toEqual([]);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_pending_joins").one().n)).toBe(0);
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
    expect(await getAlarm(hub)).toBe((row.disconnectedAt as number) + GRACE);
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
});

describe("backpressure", () => {
  // A stand-in socket: the real ones cannot be made to fall behind on demand.
  function fakeSocket(deviceId: string) {
    let attachment: Record<string, unknown> = { userId: "user_1_1", deviceId, lastSeen: Date.now() };
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
      },
    };
    return { ws: ws as unknown as WebSocket, closes, resetRate: () => delete attachment.rateCount };
  }

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

  it("a queue change that would pass 1,500,000 bytes answers queue_full and changes nothing", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    await host.client.send("playback_action", { action: "change_track", track_info: { id: "t0", title: "Now" } });
    await host.client.next("sync_playback");
    const count = await fill(hub, host.code, 1500);
    expect(MAX_ROOM_STATE_BYTES).toBe(1_500_000);
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
