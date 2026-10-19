import { fromBinary } from "@bufbuild/protobuf";
import { runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { RoomStateSchema, type RoomState } from "../src/gen/listentogether_pb";
import { Sangat } from "../src/sangat/Sangat";
import {
  closeAllClients,
  hubStub,
  openFamily,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
  type Hub,
} from "./sangat.helpers";

beforeEach(resetHub);
afterEach(closeAllClients);

type RoomRecord = Record<string, any> & { state: RoomState };

async function readRoom(hub: Hub, code: string): Promise<RoomRecord> {
  const row = await sql(hub, (db) => db.exec("SELECT * FROM sangat_rooms WHERE code = ?", code).toArray()[0]);
  return Object.assign({}, row, {
    state: fromBinary(RoomStateSchema, new Uint8Array(row.state as ArrayBuffer)),
  }) as RoomRecord;
}

const track = (n: number, extra: Record<string, unknown> = {}) => ({
  id: `trk-${String(n).padStart(3, "0")}`,
  title: `Raag Number ${n}`,
  artist: "Pt. Ravi Shankar",
  duration: 240000 + n,
  ...extra,
});

/** Everything the client is sent for the next `ms`. */
async function drain(client: SangatClient, ms = 120) {
  return client.quiet(ms);
}

describe("playback_action", () => {
  it("a guest playback_action answers not_host and changes nothing", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;

    await bharat.client.send("playback_action", { action: "change_track", track_info: track(1) });

    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can control playback",
    });
    expect(await drain(host.client)).toEqual([]);
    expect((await readRoom(hub, host.code)).state.currentTrack).toBeUndefined();
  });

  it("host play, pause and seek broadcast sync_playback with server_time and a rising revision", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const before = Date.now();

    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.send("playback_action", { action: "play", position: 5000, track_id: "trk-001" });
    await host.client.send("playback_action", { action: "seek", position: 60000 });
    await host.client.send("playback_action", { action: "pause", position: 61000 });

    const seen = [];
    for (const client of [host.client, bharat.client]) {
      const frames = [];
      for (let i = 0; i < 4; i++) frames.push((await client.next("sync_playback")).payload);
      seen.push(frames);
    }
    const [forHost, forGuest] = seen;
    expect(forGuest).toEqual(forHost);
    expect(forHost.map((p) => p.action)).toEqual(["change_track", "play", "seek", "pause"]);
    expect(forHost.map((p) => p.revision)).toEqual(["1", "2", "3", "4"]);
    expect(forHost.map((p) => p.position)).toEqual(["0", "5000", "60000", "61000"]);
    for (const p of forHost) {
      expect(Number(p.server_time)).toBeGreaterThanOrEqual(before);
      expect(Number(p.server_time)).toBeLessThanOrEqual(Date.now());
    }
    const state = (await readRoom(hub, host.code)).state;
    expect(state).toMatchObject({ isPlaying: false, position: 61000n, revision: 4n });
  });

  it("an action naming another track answers stale_track, and a negative position invalid_position", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.next("sync_playback");

    await host.client.send("playback_action", { action: "play", position: 0, track_id: "trk-other" });
    await host.client.send("playback_action", { action: "seek", position: -5 });

    expect((await host.client.next("error")).payload.code).toBe("stale_track");
    expect((await host.client.next("error")).payload.code).toBe("invalid_position");
  });

  it("queue_add past 1000 answers queue_full", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    const queue = Array.from({ length: 1000 }, (_, i) => track(i + 2, { duration: 1000 }));
    await host.client.send("playback_action", { action: "change_track", track_info: track(1), queue });
    expect((await host.client.next("sync_playback")).payload.queue).toHaveLength(1000);

    await host.client.send("playback_action", { action: "queue_add", track_info: track(5000) });
    await host.client.send("playback_action", { action: "queue_add", track_info: track(5001), insert_next: true });

    expect((await host.client.next("error")).payload).toEqual({ code: "queue_full", message: "Queue is full" });
    expect((await host.client.next("error")).payload.code).toBe("queue_full");
    expect((await readRoom(hub, host.code)).state.queue).toHaveLength(1000);
  });

  it("a duplicate queue_add answers duplicate_track, whether the track is queued or playing", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.send("playback_action", { action: "queue_add", track_info: track(2) });
    await host.client.next("sync_playback");
    await host.client.next("sync_playback");

    await host.client.send("playback_action", { action: "queue_add", track_info: track(2) });
    await host.client.send("playback_action", { action: "queue_add", track_info: track(1) });

    for (let i = 0; i < 2; i++) {
      expect((await host.client.next("error")).payload).toEqual({
        code: "duplicate_track",
        message: "Track is already playing or queued",
      });
    }
    expect((await readRoom(hub, host.code)).state.queue.map((t) => t.id)).toEqual(["trk-002"]);
  });

  it("track text is trimmed to the limits and a zero duration becomes 180000", async () => {
    const hub = hubStub();
    const { host } = await openFamily(hub, ["a"]);

    await host.client.send("playback_action", {
      action: "change_track",
      track_info: {
        id: "  trk-long  ",
        title: `  ${"T".repeat(250)}  `,
        artist: "A".repeat(250),
        album: "B".repeat(250),
        thumbnail: `https://example.test/${"x".repeat(2100)}`,
        duration: 0,
      },
    });
    await host.client.send("playback_action", {
      action: "queue_add",
      track_info: track(2, { duration: 99999999999 }),
    });

    const changed = (await host.client.next("sync_playback")).payload.track_info;
    expect(changed.id).toBe("trk-long");
    expect(changed.title).toBe("T".repeat(200));
    expect(changed.artist).toBe("A".repeat(200));
    expect(changed.album).toBe("B".repeat(200));
    expect(changed.thumbnail).toHaveLength(2048);
    expect(changed.duration).toBe("180000");
    expect((await host.client.next("sync_playback")).payload.track_info.duration).toBe("86400000");
  });
});

describe("request_sync", () => {
  it("answers sync_state at most once per second per connection", async () => {
    const hub = hubStub();
    const { guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;

    await bharat.client.send("request_sync");
    await bharat.client.send("request_sync");
    expect((await bharat.client.next("sync_state")).payload).toMatchObject({ is_playing: false, volume: 1 });
    expect(await drain(bharat.client, 200)).toEqual([]);

    await sleep(1000);
    await bharat.client.send("request_sync");
    await bharat.client.next("sync_state");
  });
});

describe("unknown messages", () => {
  it("an unknown type answers unknown_message_type naming it", async () => {
    const hub = hubStub();
    const device = await seedDevice(hub, "a");
    const client = await SangatClient.connect(hub, device.token);

    await client.send("frobnicate");

    expect((await client.next("error")).payload).toEqual({
      code: "unknown_message_type",
      message: "Unknown message type: frobnicate",
    });
  });
});

describe("suggestions", () => {
  it("an approved suggestion goes to the front of the queue with suggested_by set to the suggester's username", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", {
      action: "change_track",
      track_info: track(1),
      queue: [track(2), track(3)],
    });
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");

    await bharat.client.send("suggest_track", { track_info: track(9, { duration: 0, title: " Pyaar Hua " }) });
    const received = (await host.client.next("suggestion_received")).payload;
    expect(received).toMatchObject({ from_user_id: bharat.userId, from_username: "Bharat" });
    await host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    const hostSync = (await host.client.next("sync_playback")).payload;
    const guestSync = (await bharat.client.next("sync_playback")).payload;
    expect(guestSync).toEqual(hostSync);
    expect(hostSync).toMatchObject({ action: "queue_add", insert_next: true, revision: "2" });
    expect(hostSync.track_info).toMatchObject({
      id: "trk-009",
      title: "Pyaar Hua",
      duration: "180000",
      suggested_by: "Bharat",
    });
    expect(hostSync.queue.map((t: any) => t.id)).toEqual(["trk-009", "trk-002", "trk-003"]);
    expect(Number(hostSync.server_time)).toBeGreaterThan(0);

    const approved = (await bharat.client.next("suggestion_approved")).payload;
    expect(approved.suggestion_id).toBe(received.suggestion_id);
    expect(approved.track_info).toMatchObject({ id: "trk-009", suggested_by: "Bharat" });

    const room = await readRoom(hub, host.code);
    expect(room.state.queue.map((t) => [t.id, t.suggestedBy])).toEqual([
      ["trk-009", "Bharat"],
      ["trk-002", ""],
      ["trk-003", ""],
    ]);
    expect(room.state.revision).toBe(2n);
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_suggestions").one().n)).toBe(0);
  });

  it("answers the approval errors in upstream's order and keeps the suggestion when the track cannot be queued", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    await bharat.client.send("suggest_track", { track_info: track(1) });
    const received = (await host.client.next("suggestion_received")).payload;

    await bharat.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });
    await host.client.send("approve_suggestion", { suggestion_id: "" });
    await host.client.send("approve_suggestion", { suggestion_id: "sug_unknown" });
    await host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can approve suggestions",
    });
    expect((await host.client.next("error")).payload).toEqual({
      code: "missing_suggestion_id",
      message: "Suggestion ID is required",
    });
    expect((await host.client.next("error")).payload).toEqual({
      code: "suggestion_not_found",
      message: "Suggestion not found",
    });
    expect((await host.client.next("error")).payload).toEqual({
      code: "duplicate_track",
      message: "Track is already playing or queued",
    });
    expect(await sql(hub, (db) => db.exec("SELECT id FROM sangat_suggestions").toArray())).toEqual([
      { id: received.suggestion_id },
    ]);
  });

  it("approving into a full queue answers queue_full and keeps the suggestion", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const queue = Array.from({ length: 1000 }, (_, i) => track(i + 2, { duration: 1000 }));
    await host.client.send("playback_action", { action: "change_track", track_info: track(1), queue });
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    await bharat.client.send("suggest_track", { track_info: track(9000) });
    const received = (await host.client.next("suggestion_received")).payload;

    await host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    expect((await host.client.next("error")).payload).toEqual({ code: "queue_full", message: "Queue is full" });
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_suggestions").one().n)).toBe(1);
  });

  it("approval still queues the track when the suggester has gone, and tells nobody else", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track(9) });
    const received = (await host.client.next("suggestion_received")).payload;
    bharat.client.close();
    await host.client.next("user_disconnected");

    await host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    expect((await host.client.next("sync_playback")).payload.action).toBe("queue_add");
    expect((await readRoom(hub, host.code)).state.queue.map((t) => t.id)).toEqual(["trk-009"]);
  });

  it("reject_suggestion tells the suggester, with the sanitized reason and no default", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await bharat.client.send("suggest_track", { track_info: track(8) });
    await bharat.client.send("suggest_track", { track_info: track(7) });
    const first = (await host.client.next("suggestion_received")).payload;
    const second = (await host.client.next("suggestion_received")).payload;

    await host.client.send("reject_suggestion", { suggestion_id: first.suggestion_id, reason: `  Maybe later\u0001 ${"x".repeat(300)}` });
    await host.client.send("reject_suggestion", { suggestion_id: first.suggestion_id });
    await host.client.send("reject_suggestion", { suggestion_id: second.suggestion_id });
    await host.client.send("reject_suggestion", { suggestion_id: "" });

    const rejected = (await bharat.client.next("suggestion_rejected")).payload;
    expect(rejected.suggestion_id).toBe(first.suggestion_id);
    expect(rejected.reason).toBe(`Maybe later ${"x".repeat(188)}`);
    expect((await bharat.client.next("suggestion_rejected")).payload).toEqual({
      suggestion_id: second.suggestion_id,
      reason: "",
    });
    expect((await host.client.next("error")).payload).toEqual({
      code: "suggestion_not_found",
      message: "Suggestion not found",
    });
    expect((await host.client.next("error")).payload).toEqual({
      code: "missing_suggestion_id",
      message: "Suggestion ID is required",
    });
    await bharat.client.send("reject_suggestion", { suggestion_id: "sug_unknown" });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can reject suggestions",
    });
    expect(await sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_suggestions").one().n)).toBe(0);
  });

  it("a suggestion is the room's own: another room's host cannot approve it", async () => {
    const hub = hubStub();
    const one = await openFamily(hub, ["a", "b"]);
    const two = await openFamily(hub, ["c"], "Dev");
    await one.guests[0].client.send("suggest_track", { track_info: track(9) });
    const received = (await one.host.client.next("suggestion_received")).payload;

    await two.host.client.send("approve_suggestion", { suggestion_id: received.suggestion_id });

    expect((await two.host.client.next("error")).payload.code).toBe("suggestion_not_found");
  });
});

describe("buffer_ready", () => {
  it("answers the sender alone: sync_playback seek, then pause for a paused room, then buffer_complete", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");

    await bharat.client.send("buffer_ready", { track_id: "trk-001" });

    const seek = (await bharat.client.next("sync_playback")).payload;
    const pause = (await bharat.client.next("sync_playback")).payload;
    const complete = (await bharat.client.next("buffer_complete")).payload;
    expect(seek).toMatchObject({ action: "seek", track_id: "trk-001", position: "0", revision: "1" });
    expect(pause).toMatchObject({ action: "pause", track_id: "trk-001", position: "0", revision: "1" });
    expect(Number(seek.server_time)).toBeGreaterThan(0);
    expect(pause.server_time).toBe(seek.server_time);
    expect(complete).toEqual({ track_id: "trk-001" });
    expect(await drain(bharat.client)).toEqual([]);
    expect(await drain(host.client, 50)).toEqual([]);
  });

  it("seeks to the live position and resumes play for a playing room", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.send("playback_action", { action: "play", position: 5000 });
    await host.client.next("sync_playback");
    await host.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    const stored = (await readRoom(hub, host.code)).state;
    await sleep(150);

    await bharat.client.send("buffer_ready", { track_id: "trk-001" });

    const seek = (await bharat.client.next("sync_playback")).payload;
    const play = (await bharat.client.next("sync_playback")).payload;
    await bharat.client.next("buffer_complete");
    expect(seek.action).toBe("seek");
    expect(play.action).toBe("play");
    expect(play.position).toBe(seek.position);
    const elapsed = Number(seek.server_time) - Number(stored.lastUpdate);
    expect(elapsed).toBeGreaterThanOrEqual(140);
    expect(Number(seek.position)).toBe(5000 + elapsed);
    expect(play.revision).toBe("2");
  });

  it("never sends buffer_wait: upstream does not either", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b", "c"]);
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await host.client.next("sync_playback");
    for (const guest of guests) await guest.client.next("sync_playback");

    for (const guest of guests) await guest.client.send("buffer_ready", { track_id: "trk-001" });
    for (const guest of guests) {
      await guest.client.next("sync_playback");
      await guest.client.next("sync_playback");
      await guest.client.next("buffer_complete");
    }

    for (const client of [host.client, ...guests.map((g) => g.client)]) {
      expect((await drain(client, 50)).map((f) => f.type)).toEqual([]);
    }
  });

  it("answers missing_track_id, stale_track and not_in_room", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const outsider = await SangatClient.connect(hub, (await seedDevice(hub, "z")).token);

    await bharat.client.send("buffer_ready", { track_id: "trk-001" });
    await bharat.client.send("buffer_ready", {});
    await outsider.send("buffer_ready", { track_id: "trk-001" });

    expect((await bharat.client.next("error")).payload).toEqual({
      code: "stale_track",
      message: "Buffer readiness targets a stale track",
    });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "missing_track_id",
      message: "Track ID is required",
    });
    expect((await outsider.next("error")).payload).toEqual({
      code: "not_in_room",
      message: "You are not in a room",
    });
    await host.client.send("playback_action", { action: "change_track", track_info: track(1) });
    await bharat.client.next("sync_playback");
    await bharat.client.send("buffer_ready", { track_id: "trk-other" });
    expect((await bharat.client.next("error")).payload.code).toBe("stale_track");
  });
});

describe("kick_user", () => {
  it("kicks a member: kicked to them, user_left to everyone else, the seat and its token are gone", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;

    await host.client.send("kick_user", { user_id: bharat.userId, reason: "Too loud" });

    expect((await bharat.client.next("kicked")).payload).toEqual({ reason: "Too loud" });
    for (const client of [host.client, charu.client]) {
      expect((await client.next("user_left")).payload).toEqual({ user_id: bharat.userId, username: "Bharat" });
    }
    expect(await sql(hub, (db) => db.exec("SELECT userId FROM sangat_members ORDER BY rowid").toArray())).toEqual([
      { userId: host.userId },
      { userId: charu.userId },
    ]);
    expect((await readRoom(hub, host.code)).state.users.map((u) => u.username)).toEqual(["Asha", "Charu"]);
    // The kicked phone cannot slip back in with its old token.
    const again = await SangatClient.connect(hub, devices[1].token);
    await again.send("reconnect", { session_token: bharat.token });
    expect((await again.next("error")).payload.code).toBe("session_not_found");
    // It is no longer in the room, so it cannot act in it.
    await bharat.client.send("playback_action", { action: "queue_clear" });
    expect((await bharat.client.next("error")).payload.code).toBe("not_in_room");
  });

  it("uses a default reason, sanitizes a given one, and lets the kicked user ask to join again", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;

    await host.client.send("kick_user", { user_id: bharat.userId });
    await host.client.send("kick_user", { user_id: charu.userId, reason: `${"y".repeat(250)}` });

    expect((await bharat.client.next("kicked")).payload).toEqual({ reason: "You have been kicked from the room" });
    expect((await charu.client.next("user_left")).payload.user_id).toBe(bharat.userId);
    expect((await charu.client.next("kicked")).payload).toEqual({ reason: "y".repeat(200) });

    await bharat.client.send("join_room", { room_code: host.code, username: "Bharat" });
    expect((await host.client.next("user_left")).payload.user_id).toBe(bharat.userId);
    expect((await host.client.next("user_left")).payload.user_id).toBe(charu.userId);
    expect((await host.client.next("join_request")).payload.user_id).toBe(bharat.userId);
  });

  it("answers cannot_kick_self, user_not_found, missing_user_id and not_host", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    const other = await openFamily(hub, ["x", "y"], "Dev");

    await host.client.send("kick_user", { user_id: host.userId });
    await host.client.send("kick_user", { user_id: "user_unknown" });
    await host.client.send("kick_user", { user_id: other.guests[0].userId });
    await host.client.send("kick_user", { user_id: "" });
    await bharat.client.send("kick_user", { user_id: host.userId });

    expect((await host.client.next("error")).payload).toEqual({
      code: "cannot_kick_self",
      message: "You cannot kick yourself",
    });
    for (let i = 0; i < 2; i++) {
      expect((await host.client.next("error")).payload).toEqual({
        code: "user_not_found",
        message: "User not found in room",
      });
    }
    expect((await host.client.next("error")).payload).toEqual({
      code: "missing_user_id",
      message: "User ID is required",
    });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can kick users",
    });
  });

  it("a member inside their reconnect grace is not in the room's clients, so kicking them answers user_not_found", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    bharat.client.close();
    await host.client.next("user_disconnected");

    await host.client.send("kick_user", { user_id: bharat.userId });

    expect((await host.client.next("error")).payload.code).toBe("user_not_found");
  });
});

describe("transfer_host", () => {
  it("sends host_changed to everyone and moves control, join requests and the stored host to the new host", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;

    await host.client.send("transfer_host", { new_host_id: bharat.userId });

    for (const client of [host.client, bharat.client, charu.client]) {
      expect((await client.next("host_changed")).payload).toEqual({
        new_host_id: bharat.userId,
        new_host_name: "Bharat",
      });
    }
    const room = await readRoom(hub, host.code);
    expect(room.hostUserId).toBe(bharat.userId);
    expect(room.hostDeviceId).toBe(devices[1].deviceId);
    expect(room.state.hostId).toBe(bharat.userId);
    expect(room.state.users.map((u) => [u.username, u.isHost])).toEqual([
      ["Asha", false],
      ["Bharat", true],
      ["Charu", false],
    ]);
    expect(
      await sql(hub, (db) => db.exec("SELECT username, isHost FROM sangat_members ORDER BY rowid").toArray()),
    ).toEqual([
      { username: "Asha", isHost: 0 },
      { username: "Bharat", isHost: 1 },
      { username: "Charu", isHost: 0 },
    ]);

    await host.client.send("playback_action", { action: "queue_clear" });
    expect((await host.client.next("error")).payload.code).toBe("not_host");
    await bharat.client.send("playback_action", { action: "change_track", track_info: track(1) });
    expect((await bharat.client.next("sync_playback")).payload.action).toBe("change_track");
    // Join requests now go to the new host.
    const dev = await SangatClient.connect(hub, (await seedDevice(hub, "d")).token);
    await dev.send("join_room", { room_code: host.code, username: "Dev" });
    expect((await bharat.client.next("join_request")).payload.username).toBe("Dev");
    expect((await drain(host.client, 50)).map((f) => f.type)).not.toContain("join_request");
  });

  it("goes through setHost: car mode belongs to the old host's phone and is switched off", async () => {
    const hub = hubStub();
    const { devices, host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await runInDurableObject(hub, (_instance, state) => {
      new Sangat(state).setCarMode(host.code, devices[0].deviceId, true);
    });
    expect((await readRoom(hub, host.code)).carMode).toBe(1);

    await host.client.send("transfer_host", { new_host_id: bharat.userId });
    await bharat.client.next("host_changed");

    expect((await readRoom(hub, host.code)).carMode).toBe(0);
  });

  it("answers cannot_transfer_to_self, user_not_found, missing_user_id and not_host", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b", "c"]);
    const [bharat, charu] = guests;
    charu.client.close();
    await host.client.next("user_disconnected");
    await bharat.client.next("user_disconnected");

    await host.client.send("transfer_host", { new_host_id: host.userId });
    await host.client.send("transfer_host", { new_host_id: "user_unknown" });
    await host.client.send("transfer_host", { new_host_id: charu.userId });
    await host.client.send("transfer_host", { new_host_id: "" });
    await bharat.client.send("transfer_host", { new_host_id: bharat.userId });

    expect((await host.client.next("error")).payload).toEqual({
      code: "cannot_transfer_to_self",
      message: "You are already the host",
    });
    for (let i = 0; i < 2; i++) {
      expect((await host.client.next("error")).payload).toEqual({
        code: "user_not_found",
        message: "Target user not found in room",
      });
    }
    expect((await host.client.next("error")).payload).toEqual({
      code: "missing_user_id",
      message: "New host user ID is required",
    });
    expect((await bharat.client.next("error")).payload).toEqual({
      code: "not_host",
      message: "Only the host can transfer ownership",
    });
    expect((await readRoom(hub, host.code)).hostUserId).toBe(host.userId);
  });
});
