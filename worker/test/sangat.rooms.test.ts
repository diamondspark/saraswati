import { fromBinary } from "@bufbuild/protobuf";
import { runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { RoomStateSchema, type RoomState } from "../src/gen/listentogether_pb";
import { Sangat } from "../src/sangat/Sangat";
import {
  closeAllClients,
  createRoom,
  HUB_ORIGIN,
  hubStub,
  joinAndApprove,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
  wsRequest,
} from "./sangat.helpers";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const ROOM_CODE_ALPHABET = "1234567890QWERTYUPASDFGHJLKZXCVBNM";

beforeEach(resetHub);
afterEach(closeAllClients);

type RoomRecord = Record<string, any> & { state: RoomState };

async function readRoom(hub: ReturnType<typeof hubStub>, code: string): Promise<RoomRecord | null> {
  const row = await sql(hub, (db) => db.exec("SELECT * FROM sangat_rooms WHERE code = ?", code).toArray()[0]);
  if (!row) return null;
  return Object.assign({}, row, {
    state: fromBinary(RoomStateSchema, new Uint8Array(row.state as ArrayBuffer)),
  }) as RoomRecord;
}

describe("GET /ws guards", () => {
  it("426 without an Upgrade header", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, {
      headers: { Authorization: `Bearer ${device.token}` },
    });

    await response.text();
    expect(response.status).toBe(426);
  });

  it("401 without a bearer", async () => {
    const hub = hubStub();

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(null));

    await response.text();
    expect(response.status).toBe(401);
    expect(response.webSocket).toBeNull();
  });

  it("401 for a pending device", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "pending", "pending");

    // A pending phone has no session token yet, so it can only guess one.
    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(device.token));

    await response.text();
    expect(response.status).toBe(401);
  });

  it("401 for a revoked device", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "revoked");
    await sql(hub, (db) =>
      db.exec("UPDATE roster SET status = 'revoked', sessionToken = NULL WHERE deviceId = ?", device.deviceId),
    );

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(device.token));

    await response.text();
    expect(response.status).toBe(401);
  });

  it("401 for the admin key", async () => {
    const hub = hubStub();

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(TEST_ADMIN_KEY));

    await response.text();
    expect(response.status).toBe(401);
  });

  it("503 when sangat is off", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    await sql(hub, (db) => db.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'"));

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest(device.token));

    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: "feature disabled" });
  });

  it("401 is checked before the kill switch, so a stranger learns nothing about it", async () => {
    const hub = hubStub();
    await sql(hub, (db) => db.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'"));

    const response = await hub.fetch(`${HUB_ORIGIN}/ws`, wsRequest("ab".repeat(32)));

    await response.text();
    expect(response.status).toBe(401);
  });
});

describe("rooms", () => {
  it("create_room answers room_created with an 8-char code from the metroserver alphabet and a 64-hex session token", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");

    const { code, userId, token } = await createRoom(hub, device, "Asha");

    expect(code).toHaveLength(8);
    for (const ch of code) expect(ROOM_CODE_ALPHABET).toContain(ch);
    expect(token).toMatch(/^[0-9a-f]{64}$/);
    expect(userId).toMatch(/^user_\d+_\d{1,4}$/);
  });

  it("create_room validates the username and refuses a second room on the same socket", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    await client.send("create_room", { username: "" });
    expect((await client.next("error")).payload).toEqual({
      code: "missing_username",
      message: "Username is required",
    });
    await client.send("create_room", { username: " \u0001 " });
    expect((await client.next("error")).payload).toEqual({
      code: "invalid_username",
      message: "Username is invalid",
    });
    await client.send("create_room", { username: "Asha" });
    await client.next("room_created");
    await client.send("create_room", { username: "Asha" });
    expect((await client.next("error")).payload).toEqual({
      code: "already_in_room",
      message: "Leave the current room before creating another",
    });
  });

  it("trims a username to 50 bytes without splitting a character", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    // 20 three-byte characters = 60 bytes; the cut lands on a character boundary.
    await client.send("create_room", { username: "स".repeat(20) });
    const created = await client.next("room_created");

    const room = await readRoom(hub, created.payload.room_code);
    expect(room?.state.users[0].username).toBe("स".repeat(16));
  });

  it("join_room for an unknown code answers error room_not_found", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "b");
    const client = await SangatClient.connect(hub, device.token);

    await client.send("join_room", { room_code: "OOOOOOOO", username: "Bharat" });

    expect((await client.next("error")).payload).toEqual({
      code: "room_not_found",
      message: "Room not found",
    });
  });

  it("join_room sends join_request to the host; approve_join sends join_approved with the full RoomState and user_joined to the others", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a, "Asha");

    const bharat = await SangatClient.connect(hub, b.token);
    // Codes are matched case-insensitively.
    await bharat.send("join_room", { room_code: host.code.toLowerCase(), username: "Bharat" });
    const request = await host.client.next("join_request");
    expect(request.payload.username).toBe("Bharat");
    await host.client.send("approve_join", { user_id: request.payload.user_id });

    const approved = await bharat.next("join_approved");
    expect(approved.payload.room_code).toBe(host.code);
    expect(approved.payload.user_id).toBe(request.payload.user_id);
    expect(approved.payload.session_token).toMatch(/^[0-9a-f]{64}$/);
    expect(approved.payload.state).toMatchObject({
      room_code: host.code,
      host_id: host.userId,
      users: [
        { user_id: host.userId, username: "Asha", is_host: true, is_connected: true },
        { user_id: request.payload.user_id, username: "Bharat", is_host: false, is_connected: true },
      ],
      is_playing: false,
      position: "0",
      volume: 1,
      queue: [],
      revision: "0",
    });
    expect(Number(approved.payload.state.last_update)).toBeGreaterThan(0);
    expect(await host.client.next("user_joined")).toMatchObject({
      payload: { user_id: request.payload.user_id, username: "Bharat" },
    });

    const charu = await joinAndApprove(hub, host.client, host.code, c, "Charu");
    expect((await host.client.next("user_joined")).payload.username).toBe("Charu");
    expect((await bharat.next("user_joined")).payload.username).toBe("Charu");
    expect(await charu.client.quiet()).toEqual([]);
    expect(await bharat.quiet(0)).toEqual([]);
  });

  it("answers already_pending, join_request_not_found and missing_user_id on the approval path", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);
    const bharat = await SangatClient.connect(hub, b.token);

    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    const request = await host.client.next("join_request");
    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    expect((await bharat.next("error")).payload).toEqual({
      code: "already_pending",
      message: "You already have a pending join request",
    });
    await host.client.send("approve_join", { user_id: "" });
    expect((await host.client.next("error")).payload.code).toBe("missing_user_id");
    await host.client.send("approve_join", { user_id: "user_nobody" });
    expect((await host.client.next("error")).payload).toEqual({
      code: "join_request_not_found",
      message: "Join request not found",
    });
    await bharat.send("approve_join", { user_id: request.payload.user_id });
    expect((await bharat.next("error")).payload.code).toBe("not_in_room");
    await host.client.send("approve_join", { user_id: request.payload.user_id });
    await bharat.next("join_approved");
    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    expect((await bharat.next("error")).payload).toEqual({
      code: "already_in_room",
      message: "Leave the current room before joining another",
    });
  });

  it("only the host approves or rejects a join request", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a);
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");
    const charu = await SangatClient.connect(hub, c.token);
    await charu.send("join_room", { room_code: host.code, username: "Charu" });
    const request = await host.client.next("join_request");

    await bharat.client.send("approve_join", { user_id: request.payload.user_id });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can approve join requests",
    });
    await bharat.client.send("reject_join", { user_id: request.payload.user_id });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can reject join requests",
    });
  });

  it("reject_join sends join_rejected", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a);
    const bharat = await SangatClient.connect(hub, b.token);
    const charu = await SangatClient.connect(hub, c.token);

    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    const first = await host.client.next("join_request");
    await host.client.send("reject_join", { user_id: first.payload.user_id, reason: "Not tonight" });
    expect((await bharat.next("join_rejected")).payload).toEqual({ reason: "Not tonight" });

    await charu.send("join_room", { room_code: host.code, username: "Charu" });
    const second = await host.client.next("join_request");
    await host.client.send("reject_join", { user_id: second.payload.user_id });
    expect((await charu.next("join_rejected")).payload).toEqual({ reason: "Join request rejected by host" });

    await host.client.send("reject_join", { user_id: second.payload.user_id });
    expect((await host.client.next("error")).payload.code).toBe("join_request_not_found");
    // A rejected phone can ask again.
    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    await host.client.next("join_request");
  });

  it("leave_room sends user_left", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a);
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");
    const charu = await joinAndApprove(hub, host.client, host.code, c, "Charu");
    await host.client.next("user_joined");
    await bharat.client.next("user_joined");

    await bharat.client.send("leave_room");

    expect((await host.client.next("user_left")).payload).toEqual({
      user_id: bharat.userId,
      username: "Bharat",
    });
    expect((await charu.client.next("user_left")).payload.user_id).toBe(bharat.userId);
    expect(await bharat.client.quiet()).toEqual([]);
    // Leaving twice, or leaving with nothing to leave, is silent.
    await bharat.client.send("leave_room");
    expect(await bharat.client.quiet()).toEqual([]);
  });

  it("when the host leaves, the earliest-joined member becomes host: user_left, then host_changed", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a, "Asha");
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");
    const charu = await joinAndApprove(hub, host.client, host.code, c, "Charu");
    await host.client.next("user_joined");
    await bharat.client.next("user_joined");

    await host.client.send("leave_room");

    for (const guest of [bharat, charu]) {
      expect((await guest.client.next("user_left")).payload.user_id).toBe(host.userId);
      expect((await guest.client.next("host_changed")).payload).toEqual({
        new_host_id: bharat.userId,
        new_host_name: "Bharat",
      });
    }
    const room = await readRoom(hub, host.code);
    expect(room?.hostUserId).toBe(bharat.userId);
    expect(room?.hostDeviceId).toBe(b.deviceId);
    expect(room?.state.hostId).toBe(bharat.userId);
    expect(room?.state.users.map((u) => [u.username, u.isHost])).toEqual([
      ["Bharat", true],
      ["Charu", false],
    ]);
  });

  it("the last member leaving rejects pending joiners and makes the room unfindable at once", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a);
    const bharat = await SangatClient.connect(hub, b.token);
    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    await host.client.next("join_request");

    await host.client.send("leave_room");

    expect((await bharat.next("join_rejected")).payload).toEqual({ reason: "Room is no longer available" });
    const charu = await SangatClient.connect(hub, c.token);
    await charu.send("join_room", { room_code: host.code, username: "Charu" });
    expect((await charu.next("error")).payload.code).toBe("room_not_found");
    // The token died with the membership.
    await host.client.send("reconnect", { session_token: host.token });
    expect((await host.client.next("error")).payload.code).toBe("session_not_found");
  });

  it("a socket keeps its user id across leave_room and a later join_room", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");

    await bharat.client.send("leave_room");
    await host.client.next("user_left");
    await bharat.client.send("join_room", { room_code: host.code, username: "Bharat" });

    expect((await host.client.next("join_request")).payload.user_id).toBe(bharat.userId);
  });

  it("answers room_full at 100 connected members and too_many_pending at 100 requests", async () => {
    const hub = hubStub();
    const [a, b, c] = [await seedDevice(hub, "a"), await seedDevice(hub, "b"), await seedDevice(hub, "c")];
    const host = await createRoom(hub, a);
    await sql(hub, (db) => {
      for (let i = 0; i < 99; i++) {
        db.exec(
          "INSERT INTO sangat_members (userId, roomCode, deviceId, username, sessionToken, isHost, connected, disconnectedAt) VALUES (?, ?, ?, ?, ?, 0, 1, NULL)",
          `filler_${i}`,
          host.code,
          `filler-device-${i}`,
          `Filler ${i}`,
          `${i}`.padStart(64, "0"),
        );
      }
    });
    const bharat = await SangatClient.connect(hub, b.token);

    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    expect((await bharat.next("error")).payload).toEqual({ code: "room_full", message: "Room is full" });

    await sql(hub, (db) => {
      db.exec("DELETE FROM sangat_members WHERE userId LIKE 'filler\\_%' ESCAPE '\\'");
      for (let i = 0; i < 100; i++) {
        db.exec(
          "INSERT INTO sangat_pending_joins (roomCode, userId, username, deviceId) VALUES (?, ?, ?, ?)",
          host.code,
          `pending_${i}`,
          `Pending ${i}`,
          `pending-device-${i}`,
        );
      }
    });
    const charu = await SangatClient.connect(hub, c.token);
    await charu.send("join_room", { room_code: host.code, username: "Charu" });
    expect((await charu.next("error")).payload).toEqual({
      code: "too_many_pending",
      message: "Too many pending join requests",
    });
  });

  it("ping answers pong echoing client_time and sequence", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);
    const before = Date.now();

    await client.send("ping", { client_time: 1700000000000, sequence: 7 });
    const pong = (await client.next("pong")).payload;

    expect(pong.client_time).toBe("1700000000000");
    expect(pong.sequence).toBe("7");
    expect(Number(pong.server_receive_time)).toBeGreaterThanOrEqual(before);
    expect(Number(pong.server_send_time)).toBeGreaterThanOrEqual(Number(pong.server_receive_time));
  });

  it("client_capabilities answers server_capabilities once, and only before a room is touched", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const client = await SangatClient.connect(hub, a.token);

    await client.send("client_capabilities", { supports_protobuf: false });
    expect((await client.next("error")).payload.code).toBe("unsupported_client");
    await client.send("client_capabilities", { supports_protobuf: true, supports_compression: false });
    expect((await client.next("server_capabilities")).payload).toEqual({
      supports_protobuf: true,
      supports_compression: true,
      server_version: "1",
    });
    await client.send("client_capabilities", { supports_protobuf: true });
    expect((await client.next("error")).payload.code).toBe("capabilities_already_set");

    const late = await SangatClient.connect(hub, b.token);
    await late.send("create_room", { username: "Bharat" });
    await late.next("room_created");
    await late.send("client_capabilities", { supports_protobuf: true });
    expect((await late.next("error")).payload.code).toBe("capabilities_too_late");
  });

  it("gzips big frames unless the client declined compression", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);
    const guest = await SangatClient.connect(hub, b.token);
    await guest.send("client_capabilities", { supports_protobuf: true, supports_compression: false });
    await guest.next("server_capabilities");
    await guest.send("join_room", { room_code: host.code, username: "Bharat" });
    const request = await host.client.next("join_request");
    await host.client.send("approve_join", { user_id: request.payload.user_id });
    await guest.next("join_approved");
    await host.client.next("user_joined");
    const track = (n: number) => ({ id: `trk-${n}`, title: `Raag Number ${n}`, artist: "Pt. Ravi Shankar", duration: 240000 });

    await host.client.send("playback_action", {
      action: "change_track",
      track_info: track(1),
      queue: [track(2), track(3), track(4), track(5), track(6), track(7)],
    });

    const toHost = await host.client.next("sync_playback");
    const toGuest = await guest.next("sync_playback");
    expect(toHost.compressed).toBe(true);
    expect(toGuest.compressed).toBe(false);
    expect(toGuest.payload).toEqual(toHost.payload);
  });

  it("answers invalid_message to empty, garbage and text frames, and unknown_message_type to the rest", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    client.sendRaw(new Uint8Array(0));
    expect((await client.next("error")).payload).toEqual({
      code: "invalid_message",
      message: "Invalid message format",
    });
    client.sendRaw(new Uint8Array([0xff, 0xff, 0xff]));
    expect((await client.next("error")).payload.code).toBe("invalid_message");
    client.sendText("hello");
    expect((await client.next("error")).payload.code).toBe("invalid_message");
    await client.send("no_such_message_type");
    expect((await client.next("error")).payload).toEqual({
      code: "unknown_message_type",
      message: "Unknown message type: no_such_message_type",
    });
  });

  it("a frame over 512 KiB closes the socket with 1009 and is never decoded", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    client.sendRaw(new Uint8Array(524289));

    expect((await client.closed).code).toBe(1009);
  });

  it("every room change is written to sangat_rooms before the next message is handled", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a, "Asha");

    let room = await readRoom(hub, host.code);
    expect(room).toMatchObject({ hostUserId: host.userId, hostDeviceId: a.deviceId, carMode: 0 });
    expect(room?.state.users).toHaveLength(1);
    const members = await sql(hub, (db) => db.exec("SELECT * FROM sangat_members").toArray());
    expect(members).toMatchObject([
      { userId: host.userId, roomCode: host.code, deviceId: a.deviceId, username: "Asha", sessionToken: host.token, isHost: 1, connected: 1 },
    ]);

    const bharat = await SangatClient.connect(hub, b.token);
    await bharat.send("join_room", { room_code: host.code, username: "Bharat" });
    const request = await host.client.next("join_request");
    expect(await sql(hub, (db) => db.exec("SELECT userId, username, deviceId FROM sangat_pending_joins").toArray())).toEqual([
      { userId: request.payload.user_id, username: "Bharat", deviceId: b.deviceId },
    ]);

    await host.client.send("approve_join", { user_id: request.payload.user_id });
    await host.client.next("user_joined");
    await bharat.next("join_approved");
    room = await readRoom(hub, host.code);
    expect(room?.state.users.map((u) => u.username)).toEqual(["Asha", "Bharat"]);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_pending_joins").one().n)).toBe(0);

    // Two messages back to back: the second must see the first one's write.
    await host.client.send("playback_action", {
      action: "change_track",
      track_info: { id: "trk-001", title: "Raag Yaman", artist: "Pt. Ravi Shankar", duration: 240000 },
    });
    await bharat.send("request_sync");
    expect((await bharat.next("sync_playback")).payload.revision).toBe("1");
    const sync = (await bharat.next("sync_state")).payload;
    expect(sync.current_track.id).toBe("trk-001");
    room = await readRoom(hub, host.code);
    expect(room?.state.currentTrack?.id).toBe("trk-001");
    expect(room?.state.revision).toBe(1n);

    await bharat.send("leave_room");
    await host.client.next("sync_playback");
    await host.client.next("user_left");
    room = await readRoom(hub, host.code);
    expect(room?.state.users.map((u) => u.username)).toEqual(["Asha"]);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_members").one().n)).toBe(1);
  });
});

describe("Sangat methods the hub routes call", () => {
  it("liveRooms lists rooms whose host is connected, with hostName, hostDeviceId and memberCount", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a, "Asha");
    await joinAndApprove(hub, host.client, host.code, b, "Bharat");

    const rooms = await runInDurableObject(hub, (_instance, state) => new Sangat(state).liveRooms());

    expect(rooms).toEqual([
      {
        roomCode: host.code,
        hostName: "Asha",
        hostDeviceId: a.deviceId,
        carMode: false,
        memberCount: 2,
        startedAt: expect.any(Number),
      },
    ]);
  });

  it("setCarMode is ok for the host device, not_host for another device and not_found for an unknown room", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);

    const results = await runInDurableObject(hub, (_instance, state) => {
      const sangat = new Sangat(state);
      return [
        sangat.setCarMode(host.code, b.deviceId, true),
        sangat.setCarMode("OOOOOOOO", a.deviceId, true),
        sangat.setCarMode(host.code, a.deviceId, true),
      ];
    });

    expect(results).toEqual(["not_host", "not_found", "ok"]);
    expect((await readRoom(hub, host.code))?.carMode).toBe(1);
  });

  it("closeDevice closes that device's sockets with the code and removes it from its room", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const host = await createRoom(hub, a);
    const bharat = await joinAndApprove(hub, host.client, host.code, b, "Bharat");
    await host.client.next("user_joined");

    await runInDurableObject(hub, (_instance, state) => {
      new Sangat(state).closeDevice(b.deviceId, 4001, "revoked");
    });

    expect(await bharat.client.closed).toEqual({ code: 4001, reason: "revoked" });
    expect((await host.client.next("user_left")).payload.user_id).toBe(bharat.userId);
    expect(await sql(hub, (db) => db.exec("SELECT userId FROM sangat_members").toArray())).toEqual([
      { userId: host.userId },
    ]);
    // Its reconnect token is dead as well.
    const again = await SangatClient.connect(hub, b.token);
    await again.send("reconnect", { session_token: bharat.token });
    expect((await again.next("error")).payload.code).toBe("session_not_found");
  });

  it("closeAll closes every socket with the code and deletes every room", async () => {
    const hub = hubStub();
    const [a, b] = [await seedDevice(hub, "a"), await seedDevice(hub, "b")];
    const one = await createRoom(hub, a);
    const two = await createRoom(hub, b);

    await runInDurableObject(hub, (_instance, state) => {
      new Sangat(state).closeAll(4003, "sangat disabled");
    });

    expect(await one.client.closed).toEqual({ code: 4003, reason: "sangat disabled" });
    expect(await two.client.closed).toEqual({ code: 4003, reason: "sangat disabled" });
    await sleep(50);
    for (const table of ["sangat_rooms", "sangat_members", "sangat_pending_joins", "sangat_suggestions"]) {
      expect(await sql(hub, (db) => db.exec(`SELECT COUNT(*) AS n FROM ${table}`).one().n)).toBe(0);
    }
  });
});
