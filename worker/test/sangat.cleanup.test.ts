// What a Sangat member leaves behind when they go: their suggestions must go with them, in
// every way a seat ends (leave, kick, expired grace, revoke), and a revoke must clean up
// everything its device holds in every room, even when one room cannot be read.

import { runDurableObjectAlarm, runInDurableObject, evictAllDurableObjects } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Sangat } from "../src/sangat/Sangat";
import { call, revoke, setSwitch, streamedCall } from "./registry.helpers";
import {
  closeAllClients,
  createRoom,
  hubStub,
  joinAndApprove,
  openFamily,
  resetHub,
  seedDevice,
  sql,
  SangatClient,
  type Hub,
} from "./sangat.helpers";

beforeEach(resetHub);
afterEach(closeAllClients);

const MINUTE = 60_000;
const GRACE = 15 * MINUTE;
const track = { id: "trk-001", title: "Raag Yaman", artist: "Pt. Ravi Shankar", duration: 240000 };

const count = (hub: Hub, table: string) =>
  sql(hub, (db) => db.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM ${table}`).one().n);

const liveSangat = (hub: Hub) =>
  runInDurableObject(hub, (instance) => (instance as unknown as { sangat: Sangat }).sangat);

describe("a member's suggestions end with their seat", () => {
  it("leave_room takes them away, so the host cannot approve them", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    const suggestion = await host.client.next("suggestion_received");
    expect(await count(hub, "sangat_suggestions")).toBe(1);

    await bharat.client.send("leave_room");
    await host.client.next("user_left");

    expect(await count(hub, "sangat_suggestions")).toBe(0);
    await host.client.send("approve_suggestion", { suggestion_id: suggestion.payload.suggestion_id });
    expect((await host.client.next("error")).payload.code).toBe("suggestion_not_found");
  });

  it("only the leaver's go: the others' stay", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    await host.client.next("suggestion_received");
    await charu.client.send("suggest_track", { track_info: { ...track, id: "trk-002" } });
    await host.client.next("suggestion_received");

    await bharat.client.send("leave_room");
    await host.client.next("user_left");

    expect(
      await sql(hub, (db) => db.exec<{ fromUserId: string }>("SELECT fromUserId FROM sangat_suggestions").toArray()),
    ).toEqual([{ fromUserId: charu.userId }]);
  });

  it("a kick takes them away", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    await host.client.next("suggestion_received");

    await host.client.send("kick_user", { user_id: bharat.userId });
    await host.client.next("user_left");

    expect(await count(hub, "sangat_suggestions")).toBe(0);
  });

  it("they stay through the reconnect grace and go when it runs out", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    await host.client.next("suggestion_received");

    bharat.client.close();
    await host.client.next("user_disconnected");
    expect(await count(hub, "sangat_suggestions")).toBe(1);

    await sql(hub, (db) =>
      db.exec("UPDATE sangat_members SET disconnectedAt = ? WHERE userId = ?", Date.now() - GRACE - MINUTE, bharat.userId),
    );
    expect(await runDurableObjectAlarm(hub)).toBe(true);
    await host.client.next("user_left");

    expect(await count(hub, "sangat_suggestions")).toBe(0);
  });
});

describe("revoking a device", () => {
  it("leaves no suggestion of a member who had already left", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track });
    const suggestion = await host.client.next("suggestion_received");
    await bharat.client.send("leave_room");
    await host.client.next("user_left");

    expect((await revoke(hub, devices[1].deviceId)).status).toBe(200);

    expect(await count(hub, "sangat_suggestions")).toBe(0);
    await host.client.send("approve_suggestion", { suggestion_id: suggestion.payload.suggestion_id });
    expect((await host.client.next("error")).payload.code).toBe("suggestion_not_found");
  });

  it("clears what its device holds in every room: seats, suggestions, a leaver's suggestion and a join request elsewhere", async () => {
    const hub = hubStub();
    const [a, b, c, d, e] = await Promise.all(["a", "b", "c", "d", "e"].map((n) => seedDevice(hub, n)));
    const roomA = await createRoom(hub, a, "Asha");
    const roomB = await createRoom(hub, c, "Chitra");
    const roomC = await createRoom(hub, d, "Dev");

    // Device b sits in A and B and suggests in both, then leaves B; it also knocks on C.
    const inA = await joinAndApprove(hub, roomA.client, roomA.code, b, "Bharat");
    await roomA.client.next("user_joined");
    const inB = await joinAndApprove(hub, roomB.client, roomB.code, b, "Bharat");
    await roomB.client.next("user_joined");
    await inA.client.send("suggest_track", { track_info: track });
    await roomA.client.next("suggestion_received");
    await inB.client.send("suggest_track", { track_info: { ...track, id: "trk-002" } });
    await roomB.client.next("suggestion_received");
    await inB.client.send("leave_room");
    await roomB.client.next("user_left");
    const knocking = await SangatClient.connect(hub, b.token);
    await knocking.send("join_room", { room_code: roomC.code, username: "Bharat" });
    await roomC.client.next("join_request");
    // A second suggestion in B, from a seat that is still there, so that room has both kinds.
    const againInB = await joinAndApprove(hub, roomB.client, roomB.code, b, "Bharat");
    await roomB.client.next("user_joined");
    await againInB.client.send("suggest_track", { track_info: { ...track, id: "trk-003" } });
    await roomB.client.next("suggestion_received");
    // Device e is another guest in B, whose suggestion sits beside b's and must outlive the revoke.
    const guestInB = await joinAndApprove(hub, roomB.client, roomB.code, e, "Esha");
    await roomB.client.next("user_joined");
    await guestInB.client.send("suggest_track", { track_info: { ...track, id: "trk-004" } });
    await roomB.client.next("suggestion_received");
    // b's in A and in B (the one it left B with is already gone), and e's.
    expect(await count(hub, "sangat_suggestions")).toBe(3);
    expect(await count(hub, "sangat_pending_joins")).toBe(1);

    expect((await revoke(hub, b.deviceId)).status).toBe(200);

    for (const socket of [inA.client, againInB.client, knocking]) {
      expect(await socket.closed).toEqual({ code: 4001, reason: "revoked" });
    }
    // Everything of b's is gone; the other guest's suggestion in the same room is not.
    expect(
      await sql(hub, (db) => db.exec<{ fromUserId: string }>("SELECT fromUserId FROM sangat_suggestions").toArray()),
    ).toEqual([{ fromUserId: guestInB.userId }]);
    expect(await count(hub, "sangat_pending_joins")).toBe(0);
    // The three hosts and e; b's two seats and the other seat it held are gone.
    expect(await count(hub, "sangat_members")).toBe(4);
    await roomA.client.next("user_left");
    await roomB.client.next("user_left");
    expect(await roomC.client.quiet(100)).toEqual([]);
  });

  it("one room that cannot be read does not undo the cleanup in the others", async () => {
    const hub = hubStub();
    const [a, b, c, d] = await Promise.all(["a", "b", "c", "d"].map((n) => seedDevice(hub, n)));
    const roomA = await createRoom(hub, a, "Asha");
    const roomB = await createRoom(hub, c, "Chitra");
    const roomC = await createRoom(hub, d, "Dev");
    const inA = await joinAndApprove(hub, roomA.client, roomA.code, b, "Bharat");
    await roomA.client.next("user_joined");
    const inB = await joinAndApprove(hub, roomB.client, roomB.code, b, "Bharat");
    await roomB.client.next("user_joined");
    await inA.client.send("suggest_track", { track_info: track });
    await roomA.client.next("suggestion_received");
    await inB.client.send("suggest_track", { track_info: { ...track, id: "trk-002" } });
    await roomB.client.next("suggestion_received");
    const knocking = await SangatClient.connect(hub, b.token);
    await knocking.send("join_room", { room_code: roomC.code, username: "Bharat" });
    await roomC.client.next("join_request");
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_rooms SET state = ? WHERE code = ?", new Uint8Array([0xff, 0xff, 0xff, 0xff]).buffer, roomA.code),
    );
    const sangat = await liveSangat(hub);

    const after = await runInDurableObject(hub, (_instance, state) => {
      let threw = false;
      try {
        sangat.closeDevice(b.deviceId, 4001, "revoked");
      } catch {
        threw = true;
      }
      const seen = {
        threw,
        suggestions: state.storage.sql.exec("SELECT COUNT(*) AS n FROM sangat_suggestions").one().n,
        pending: state.storage.sql.exec("SELECT COUNT(*) AS n FROM sangat_pending_joins").one().n,
        seats: state.storage.sql
          .exec<{ userId: string }>("SELECT userId FROM sangat_members WHERE deviceId = ?", b.deviceId)
          .toArray()
          .map((row) => row.userId),
      };
      // The unreadable room would make later close events throw; clear it away.
      state.storage.sql.exec("DELETE FROM sangat_members WHERE roomCode = ?", roomA.code);
      state.storage.sql.exec("DELETE FROM sangat_rooms WHERE code = ?", roomA.code);
      return seen;
    });

    expect(after.threw).toBe(false);
    expect(after.suggestions).toBe(0);
    expect(after.pending).toBe(0);
    // Only the seat in the room that could not be read is left (its close event and the grace finish it).
    expect(after.seats).toEqual([inA.userId]);
    expect(await inB.client.closed).toEqual({ code: 4001, reason: "revoked" });
    expect((await roomB.client.next("user_left")).payload).toEqual({ user_id: inB.userId, username: "Bharat" });
  });

  it("settles a hub that restarted first: nobody whose socket is gone is promoted to host", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    await evictAllDurableObjects({ webSockets: "close" });
    const sangat = await liveSangat(hub);

    const after = await runInDurableObject(hub, (_instance, state) => {
      sangat.closeDevice(devices[0].deviceId, 4001, "revoked");
      return {
        hostUserId: state.storage.sql
          .exec<{ hostUserId: string }>("SELECT hostUserId FROM sangat_rooms WHERE code = ?", host.code)
          .one().hostUserId,
        members: state.storage.sql
          .exec<{ userId: string; connected: number }>("SELECT userId, connected FROM sangat_members")
          .toArray(),
      };
    });

    // The guest's socket went with the old instance, so there is nobody to hand the room to.
    expect(after).toEqual({ hostUserId: "", members: [{ userId: guests[0].userId, connected: 0 }] });
  });
});

describe("PUT /sangat/car", () => {
  const setCar = (hub: Hub, token: string, body: unknown) => call(hub, "PUT", "/sangat/car", { token, body });

  it("reads the room code as join_room does: upper-cased, trimmed, cut to 10 characters", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);

    const lower = await setCar(hub, devices[0].token, { roomCode: ` ${host.code.toLowerCase()}  `, carMode: true });
    expect(lower.status).toBe(200);
    expect(lower.body.roomCode).toBe(host.code);
    expect(lower.body.carMode).toBe(true);

    const padded = await setCar(hub, devices[0].token, { roomCode: `${host.code}${" ".repeat(24)}`, carMode: false });
    expect(padded.status).toBe(200);
    expect(padded.body.carMode).toBe(false);

    // Past 10 characters nothing can match, however long it is (up to the 32 that are read at all).
    const long = await setCar(hub, devices[0].token, { roomCode: `${host.code}ZZ${"Q".repeat(22)}`, carMode: true });
    expect(long.status).toBe(404);
    // A code of nothing but blanks or control characters is no code.
    for (const roomCode of ["   ", "\u0000\u0001"]) {
      const blank = await setCar(hub, devices[0].token, { roomCode, carMode: true });
      expect(blank.status, JSON.stringify(roomCode)).toBe(400);
    }
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
  });

  // A body of exactly `bytes` bytes: valid JSON with a pad the route ignores.
  const bodyOfSize = (roomCode: string, bytes: number) => {
    const head = JSON.stringify({ roomCode, carMode: true, pad: "" });
    return JSON.stringify({ roomCode, carMode: true, pad: "p".repeat(bytes - head.length) });
  };

  it("takes a body of 1024 bytes and refuses 1025 with 413 before parsing", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);

    const refused = await setCar(hub, devices[0].token, bodyOfSize(host.code, 1025));
    expect(refused.status).toBe(413);
    expect(refused.body).toEqual({ error: "too large" });
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);

    expect(bodyOfSize(host.code, 1024)).toHaveLength(1024);
    expect((await setCar(hub, devices[0].token, bodyOfSize(host.code, 1024))).status).toBe(200);
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(1);
  });

  it("is 413 for a streamed body past the cap even though it never declared a length", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);
    const streamed = streamedCall(hub, "PUT", "/sangat/car", devices[0].token);
    streamed.send(`{"roomCode":"${host.code}","carMode":true,"pad":"`);
    for (let i = 0; i < 3; i++) streamed.send("p".repeat(500));
    streamed.send('"}');
    streamed.end();

    const reply = await streamed.reply;
    expect(reply.status).toBe(413);
    expect(reply.body).toEqual({ error: "too large" });
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(0);
  });

  it("checks auth, then the switch, then size, then JSON", async () => {
    const hub = hubStub();
    const { devices } = await openFamily(hub, ["a"]);
    const notJson = "x".repeat(2000);

    expect((await setCar(hub, devices[0].token, notJson)).status).toBe(413);
    expect((await setCar(hub, devices[0].token, "x".repeat(100))).status).toBe(400);
    expect((await setCar(hub, "ab".repeat(32), notJson)).status).toBe(401);
    expect((await setSwitch(hub, "sangat", false)).status).toBe(200);
    expect((await setCar(hub, devices[0].token, notJson)).status).toBe(503);
  });

  it("reads at most 32 characters of roomCode: 32 are read, 33 are 400 before normalizing", async () => {
    const hub = hubStub();
    const { devices, host } = await openFamily(hub, ["a"]);

    const atLimit = await setCar(hub, devices[0].token, { roomCode: `${host.code}${" ".repeat(24)}`, carMode: true });
    expect(atLimit.status).toBe(200);
    expect(`${host.code}${" ".repeat(24)}`).toHaveLength(32);

    for (const roomCode of [`${host.code}${" ".repeat(25)}`, "Q".repeat(500), `${" ".repeat(40)}${host.code}`]) {
      const reply = await setCar(hub, devices[0].token, { roomCode, carMode: false });
      expect(reply.status, roomCode.length.toString()).toBe(400);
      expect(reply.body).toEqual({ error: expect.any(String) });
    }
    expect(await sql(hub, (db) => db.exec("SELECT carMode FROM sangat_rooms").one().carMode)).toBe(1);
  });
});
