// Hub routes for Sangat: GET /sangat/live, PUT /sangat/car, and the two things that
// must cut live sockets at once, a revoke (4001) and the "sangat" kill switch (4003).

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { sha256Hex } from "./helpers";
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

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;

beforeEach(resetHub);
afterEach(closeAllClients);

// A reply with its body already read: a response left unread keeps the Durable Object
// busy, and the next test's eviction would wait for it.
type Reply = { status: number; body: any; contentType: string | null };

async function toReply(response: Response): Promise<Reply> {
  const text = await response.text();
  let body: unknown = text;
  try {
    body = JSON.parse(text);
  } catch {
    // not JSON; the text stays
  }
  return { status: response.status, body, contentType: response.headers.get("content-type") };
}

async function call(
  hub: Hub,
  method: string,
  path: string,
  opts: { token?: string | null; body?: unknown } = {},
): Promise<Reply> {
  const headers: Record<string, string> = {};
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let body: string | undefined;
  if (opts.body !== undefined) {
    headers["content-type"] = "application/json";
    body = typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body);
  }
  return toReply(await hub.fetch(`${HUB_ORIGIN}${path}`, { method, headers, body }));
}

/** GET /ws that is refused before the upgrade. */
async function refusedUpgrade(hub: Hub, token: string | null): Promise<Reply> {
  const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(token));
  expect(response.webSocket).toBeNull();
  return toReply(response);
}

const live = (hub: Hub, token: string | null) => call(hub, "GET", "/sangat/live", { token });
const setCar = (hub: Hub, token: string | null, body: unknown) =>
  call(hub, "PUT", "/sangat/car", { token, body });
const putSwitches = (hub: Hub, body: unknown) =>
  call(hub, "PUT", "/killswitches", { token: TEST_ADMIN_KEY, body });
const revoke = (hub: Hub, deviceId: string) =>
  call(hub, "POST", `/roster/${deviceId}/revoke`, { token: TEST_ADMIN_KEY });

async function liveCodes(hub: Hub, token: string): Promise<string[]> {
  const { body } = await live(hub, token);
  return (body.rooms as { roomCode: string }[]).map((room) => room.roomCode).sort();
}

async function until(check: () => Promise<boolean>, what: string, timeoutMs = 3000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!(await check())) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(25);
  }
}

const count = (hub: Hub, table: string) =>
  sql(hub, (db) => db.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM ${table}`).one().n);

const track = { id: "trk-001", title: "Raag Yaman", artist: "Pt. Ravi Shankar", duration: 240000 };

describe("GET /sangat/live", () => {
  it("lists only rooms whose host is connected, with hostName and memberCount", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a", "b"]);
    const away = await createRoom(hub, await seedDevice(hub, "c"), "Charu");
    expect(await liveCodes(hub, devices[1].token)).toEqual([host.code, away.code].sort());

    away.client.close();
    await until(async () => (await liveCodes(hub, devices[1].token)).length === 1, "the room to leave the list");

    const response = await live(hub, devices[1].token);
    expect(response.status).toBe(200);
    expect(response.contentType).toContain("application/json");
    const createdAt = await sql(hub, (db) =>
      db.exec<{ createdAt: number }>("SELECT createdAt FROM sangat_rooms WHERE code = ?", host.code).one().createdAt,
    );
    expect(response.body).toEqual({
      rooms: [
        {
          roomCode: host.code,
          hostName: "Asha",
          hostDeviceId: devices[0].deviceId,
          carMode: false,
          memberCount: 2,
          startedAt: new Date(createdAt).toISOString(),
        },
      ],
    });
    expect((await live(hub, devices[0].token)).body.rooms[0].startedAt).toMatch(ISO);
  });

  it("answers an admin as well, and an empty list when nothing is live", async () => {
    const hub = hubStub();
    const empty = await live(hub, TEST_ADMIN_KEY);
    expect(empty.status).toBe(200);
    expect(empty.body).toEqual({ rooms: [] });

    const { host } = await openFamily(hub, ["a"]);
    expect(await liveCodes(hub, TEST_ADMIN_KEY)).toEqual([host.code]);
  });

  it("is 401 without auth and 503 when sangat is off", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const revoked = await seedDevice(hub, "r", "revoked");
    const pending = await seedDevice(hub, "p", "pending");

    for (const token of [null, "cd".repeat(32), revoked.token, pending.token]) {
      const refused = await live(hub, token);
      expect(refused.status).toBe(401);
      expect(refused.body).toEqual({ error: "unauthorized" });
    }
    expect((await live(hub, device.token)).status).toBe(200);

    expect((await putSwitches(hub, { sangat: false })).status).toBe(200);
    for (const token of [device.token, TEST_ADMIN_KEY]) {
      const off = await live(hub, token);
      expect(off.status).toBe(503);
      expect(off.body).toEqual({ error: "feature disabled" });
    }
    // Auth is checked before the switch: a stranger learns nothing about it.
    expect((await live(hub, null)).status).toBe(401);

    expect((await putSwitches(hub, { sangat: true })).status).toBe(200);
    expect((await live(hub, device.token)).status).toBe(200);
  });

  it("counts against the admin budget for an admin, never for a device, and not for a bad bearer", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const budget = () =>
      sql(hub, (db) => db.exec<{ count: number }>("SELECT count FROM rate_limits WHERE key = 'admin'").toArray());

    for (let i = 0; i < 25; i++) expect((await live(hub, device.token)).status).toBe(200);
    expect((await live(hub, "cd".repeat(32))).status).toBe(401);
    expect((await live(hub, null)).status).toBe(401);
    expect(await budget()).toEqual([]);

    expect((await live(hub, TEST_ADMIN_KEY)).status).toBe(200);
    expect(await budget()).toEqual([{ count: 1 }]);
    for (let i = 1; i < 20; i++) expect((await live(hub, TEST_ADMIN_KEY)).status).toBe(200);
    expect((await live(hub, TEST_ADMIN_KEY)).status).toBe(429);
    expect((await live(hub, device.token)).status).toBe(200);
  });
});

describe("PUT /sangat/car", () => {
  it("car mode set by the host device shows carMode true in live", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a", "b"]);

    const response = await setCar(hub, devices[0].token, { roomCode: host.code, carMode: true });

    expect(response.status).toBe(200);
    const createdAt = await sql(hub, (db) =>
      db.exec<{ createdAt: number }>("SELECT createdAt FROM sangat_rooms WHERE code = ?", host.code).one().createdAt,
    );
    expect(response.body).toEqual({
      roomCode: host.code,
      hostName: "Asha",
      hostDeviceId: devices[0].deviceId,
      carMode: true,
      memberCount: 2,
      startedAt: new Date(createdAt).toISOString(),
    });
    const rooms = (await live(hub, devices[1].token)).body.rooms as { roomCode: string; carMode: boolean }[];
    expect(rooms.map((r) => [r.roomCode, r.carMode])).toEqual([[host.code, true]]);

    const off = await setCar(hub, devices[0].token, { roomCode: host.code, carMode: false });
    expect(off.body.carMode).toBe(false);
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
  });

  it("car mode from another device is 403 and changes nothing", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a", "b"]);

    const response = await setCar(hub, devices[1].token, { roomCode: host.code, carMode: true });

    expect(response.status).toBe(403);
    expect(response.body).toEqual({ error: "only the host device can change car mode" });
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
  });

  it("car mode for an unknown room is 404", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");

    const response = await setCar(hub, device.token, { roomCode: "ZZZZZZZZ", carMode: true });

    expect(response.status).toBe(404);
    expect(response.body).toEqual({ error: "room not found" });
  });

  it("car mode with a non-boolean is 400", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);
    const token = devices[0].token;
    const bad: unknown[] = [
      { roomCode: host.code, carMode: "true" },
      { roomCode: host.code, carMode: 1 },
      { roomCode: host.code, carMode: null },
      { roomCode: host.code },
      { carMode: true },
      { roomCode: "", carMode: true },
      { roomCode: 12345678, carMode: true },
      [],
      "not json",
      "[true]",
    ];

    for (const body of bad) {
      const response = await setCar(hub, token, body);
      expect(response.status, JSON.stringify(body)).toBe(400);
      expect(response.body).toEqual({ error: "roomCode and a boolean carMode are required" });
    }
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
  });

  it("is for devices only: no auth, a wrong bearer, a revoked token and the admin key are all 401", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);
    const revoked = await seedDevice(hub, "r", "revoked");
    const body = { roomCode: host.code, carMode: true };

    for (const token of [null, "cd".repeat(32), revoked.token, TEST_ADMIN_KEY]) {
      const response = await setCar(hub, token, body);
      expect(response.status).toBe(401);
      expect(response.body).toEqual({ error: "unauthorized" });
    }
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
    expect((await setCar(hub, devices[0].token, body)).status).toBe(200);
    // A device's calls are not admin traffic.
    expect(await sql(hub, (db) => db.exec("SELECT * FROM rate_limits WHERE key = 'admin'").toArray())).toEqual([]);
  });

  it("is 503 when sangat is off", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    await putSwitches(hub, { sangat: false });

    const response = await setCar(hub, device.token, { roomCode: "ZZZZZZZZ", carMode: true });

    expect(response.status).toBe(503);
    expect(response.body).toEqual({ error: "feature disabled" });
  });

  it("is switched off again when the room passes to another device's host", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    await setCar(hub, devices[0].token, { roomCode: host.code, carMode: true });

    await host.client.send("transfer_host", { new_host_id: guests[0].userId });
    await host.client.next("host_changed");

    const rooms = (await live(hub, devices[0].token)).body.rooms;
    expect(rooms).toEqual([expect.objectContaining({ hostDeviceId: devices[1].deviceId, carMode: false })]);
    // The old host's device can no longer change it; the new one can.
    expect((await setCar(hub, devices[0].token, { roomCode: host.code, carMode: true })).status).toBe(403);
    expect((await setCar(hub, devices[1].token, { roomCode: host.code, carMode: true })).status).toBe(200);
  });
});

describe("POST /roster/{id}/revoke", () => {
  it("revoking a device closes its socket with 4001 and removes it from its room", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;

    const response = await revoke(hub, devices[1].deviceId);

    expect(response.status).toBe(200);
    expect(response.body).toEqual({ deviceId: devices[1].deviceId, status: "revoked" });
    expect(await bharat.client.closed).toEqual({ code: 4001, reason: "revoked" });
    for (const other of [host.client, charu.client]) {
      expect((await other.next("user_left")).payload).toEqual({ user_id: bharat.userId, username: "Bharat" });
    }
    expect(
      await sql(hub, (db) => db.exec("SELECT userId FROM sangat_members ORDER BY rowid").toArray()),
    ).toEqual([{ userId: host.userId }, { userId: charu.userId }]);
    // The others carry on.
    await host.client.send("ping");
    await host.client.next("pong");
    expect(await liveCodes(hub, devices[0].token)).toEqual([host.code]);
  });

  it("takes the device's pending joins and suggestions with it, on every socket it has", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    const suggestion = await host.client.next("suggestion_received");
    // A second phone socket of the same device, still waiting at the door.
    const lobby = await SangatClient.connect(hub, devices[1].token);
    await lobby.send("join_room", { room_code: host.code, username: "Bharat again" });
    const request = await host.client.next("join_request");
    expect(await count(hub, "sangat_suggestions")).toBe(1);
    expect(await count(hub, "sangat_pending_joins")).toBe(1);

    await revoke(hub, devices[1].deviceId);

    expect(await bharat.client.closed).toEqual({ code: 4001, reason: "revoked" });
    expect(await lobby.closed).toEqual({ code: 4001, reason: "revoked" });
    expect(await count(hub, "sangat_suggestions")).toBe(0);
    expect(await count(hub, "sangat_pending_joins")).toBe(0);
    expect(await count(hub, "sangat_members")).toBe(1);
    // The host can no longer act on what the revoked phone left behind.
    await host.client.next("user_left");
    await host.client.send("approve_suggestion", { suggestion_id: suggestion.payload.suggestion_id });
    expect((await host.client.next("error")).payload.code).toBe("suggestion_not_found");
    await host.client.send("approve_join", { user_id: request.payload.user_id });
    expect((await host.client.next("error")).payload.code).toBe("join_request_not_found");
  });

  it("revoking the host's device passes the room on and says so", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await setCar(hub, devices[0].token, { roomCode: host.code, carMode: true });

    await revoke(hub, devices[0].deviceId);

    expect(await host.client.closed).toEqual({ code: 4001, reason: "revoked" });
    expect((await bharat.client.next("user_left")).payload.user_id).toBe(host.userId);
    expect((await bharat.client.next("host_changed")).payload).toEqual({
      new_host_id: bharat.userId,
      new_host_name: "Bharat",
    });
    const rooms = (await live(hub, devices[1].token)).body.rooms;
    expect(rooms).toEqual([
      expect.objectContaining({ hostName: "Bharat", hostDeviceId: devices[1].deviceId, carMode: false, memberCount: 1 }),
    ]);
  });

  it("a revoked device's reconnect token no longer works", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");
    // Bharat's phone drops; the seat is held for the reconnect grace.
    bharat.client.close();
    await host.client.next("user_disconnected");
    expect(await count(hub, "sangat_members")).toBe(2);

    await revoke(hub, b.deviceId);

    // The device's own session token is dead.
    expect((await refusedUpgrade(hub, b.token)).status).toBe(401);
    // Even if the owner approved the phone again later, the old seat is gone.
    const fresh = "ab".repeat(32);
    const freshHash = await sha256Hex(fresh);
    await sql(hub, (db) => {
      db.exec("UPDATE roster SET status = 'approved', sessionTokenHash = ? WHERE deviceId = ?", freshHash, b.deviceId);
    });
    const again = await SangatClient.connect(hub, fresh);
    await again.send("reconnect", { session_token: bharat.token });
    expect((await again.next("error")).payload.code).toBe("session_not_found");
    expect(await count(hub, "sangat_members")).toBe(1);
  });

  it("revoking a device that is in no room just revokes it, and an unknown device is 404 and cuts nobody", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);
    const idle = await seedDevice(hub, "z");
    const idleClient = await SangatClient.connect(hub, idle.token);

    expect((await revoke(hub, idle.deviceId)).status).toBe(200);
    expect(await idleClient.closed).toEqual({ code: 4001, reason: "revoked" });
    expect((await revoke(hub, "sg-device-unknown")).status).toBe(404);

    await host.client.send("ping");
    await host.client.next("pong");
    expect(await liveCodes(hub, devices[0].token)).toEqual([host.code]);
  });
});

describe("PUT /killswitches with sangat off", () => {
  it("switching sangat off closes every socket with 4003 and empties sangat_rooms", async () => {
    const hub = hubStub();
    const family = await openFamily(hub, ["a", "b"]);
    const other = await createRoom(hub, await seedDevice(hub, "c"), "Charu");
    const waiting = await SangatClient.connect(hub, family.devices[1].token);
    await waiting.send("join_room", { room_code: other.code, username: "Bharat again" });
    await other.client.next("join_request");
    await family.guests[0].client.send("suggest_track", { track_info: track });
    await family.host.client.next("suggestion_received");
    const lobby = await SangatClient.connect(hub, family.devices[0].token);
    expect(await count(hub, "sangat_rooms")).toBe(2);

    const response = await putSwitches(hub, { sangat: false });

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      sangat: false,
      parivaar_mix: true,
      family_playlists: true,
      cast_relay: true,
    });
    const closed = { code: 4003, reason: "sangat disabled" };
    for (const client of [family.host.client, family.guests[0].client, other.client, waiting, lobby]) {
      expect(await client.closed).toEqual(closed);
    }
    for (const table of ["sangat_rooms", "sangat_members", "sangat_pending_joins", "sangat_suggestions"]) {
      expect(await count(hub, table), table).toBe(0);
    }
    // Nothing gets back in while it is off.
    const upgrade = await refusedUpgrade(hub, family.devices[0].token);
    expect(upgrade.status).toBe(503);
    expect(upgrade.body).toEqual({ error: "feature disabled" });
  });

  it("a re-enable starts with no rooms", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    await putSwitches(hub, { sangat: false });
    await host.client.closed;

    expect((await putSwitches(hub, { sangat: true })).status).toBe(200);

    expect((await live(hub, devices[0].token)).body).toEqual({ rooms: [] });
    const rejoin = await SangatClient.connect(hub, devices[1].token);
    await rejoin.send("join_room", { room_code: host.code, username: "Bharat" });
    expect((await rejoin.next("error")).payload.code).toBe("room_not_found");
    await rejoin.send("reconnect", { session_token: guests[0].token });
    expect((await rejoin.next("error")).payload.code).toBe("session_not_found");
    const created = await createRoom(hub, devices[0], "Asha");
    expect(await liveCodes(hub, devices[0].token)).toEqual([created.code]);
  });

  it("leaves sockets alone when sangat stays on, another switch goes off, or the body is refused", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);

    expect((await putSwitches(hub, { sangat: true })).status).toBe(200);
    expect((await putSwitches(hub, { parivaar_mix: false, cast_relay: false })).status).toBe(200);
    expect((await putSwitches(hub, { sangat: false, bogus: true })).status).toBe(400);
    expect((await putSwitches(hub, { sangat: "no" })).status).toBe(400);
    const stranger = await call(hub, "PUT", "/killswitches", { token: "cd".repeat(32), body: { sangat: false } });
    expect(stranger.status).toBe(401);
    const asDevice = await call(hub, "PUT", "/killswitches", {
      token: (await seedDevice(hub, "x")).token,
      body: { sangat: false },
    });
    expect(asDevice.status).toBe(401);

    await sleep(100);
    await host.client.send("ping");
    await host.client.next("pong");
    expect(await count(hub, "sangat_rooms")).toBe(1);
  });
});
