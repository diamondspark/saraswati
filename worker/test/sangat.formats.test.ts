// The formats and arithmetic the trace normalization hides: it replaces ids,
// room codes, tokens and times with placeholders, so a wrong shape would still
// match a golden. These tests pin them to metroserver's.

import { create } from "@bufbuild/protobuf";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { RoomStateSchema, TrackInfoSchema } from "../src/gen/listentogether_pb";
import { newRoomCode, newSessionToken, newSuggestionId, newUserId } from "../src/sangat/ids";
import { ROOM_CODE_ALPHABET, ROOM_CODE_LENGTH } from "../src/sangat/limits";
import { livePosition } from "../src/sangat/roomOps";
import { sha256Hex } from "../src/secrets";
import {
  closeAllClients,
  createRoom,
  hubStub,
  openFamily,
  resetHub,
  seedDevice,
  sleep,
  sql,
} from "./sangat.helpers";
import { fromBinary } from "@bufbuild/protobuf";

beforeEach(resetHub);
afterEach(closeAllClients);

describe("user ids", () => {
  it("are user_<ms>_<0-9999>", () => {
    const seen = new Set<number>();
    for (let i = 0; i < 3000; i++) {
      const id = newUserId(1_760_000_000_123);
      const match = /^user_(\d+)_(\d+)$/.exec(id);
      expect(match, id).not.toBeNull();
      expect(match?.[1]).toBe("1760000000123");
      const suffix = Number(match?.[2]);
      expect(match?.[2]).toBe(String(suffix));
      expect(suffix).toBeGreaterThanOrEqual(0);
      expect(suffix).toBeLessThanOrEqual(9999);
      seen.add(suffix);
    }
    // Uniform over 10000 values: 3000 draws repeat a little, but cover well over 2000 of them.
    expect(seen.size).toBeGreaterThan(2000);
  });

  it("a connected socket gets one stamped with the time it connected", async () => {
    const hub = hubStub();
    const before = Date.now();
    const { userId } = await createRoom(hub, await seedDevice(hub, "a"));
    const match = /^user_(\d{13})_(\d{1,4})$/.exec(userId);
    expect(match, userId).not.toBeNull();
    expect(Number(match?.[1])).toBeGreaterThanOrEqual(before);
    expect(Number(match?.[1])).toBeLessThanOrEqual(Date.now());
  });

  it("suggestion ids follow the same shape with a sug_ prefix", () => {
    expect(newSuggestionId(1_760_000_000_123)).toMatch(/^sug_1760000000123_\d{1,4}$/);
  });
});

describe("room codes", () => {
  it("are 8 characters from the metroserver alphabet, and every character of it turns up", () => {
    expect(ROOM_CODE_ALPHABET).toBe("1234567890QWERTYUPASDFGHJLKZXCVBNM");
    expect(ROOM_CODE_LENGTH).toBe(8);
    const seen = new Set<string>();
    for (let i = 0; i < 3000; i++) {
      const code = newRoomCode();
      expect(code).toHaveLength(8);
      for (const ch of code) {
        expect(ROOM_CODE_ALPHABET).toContain(ch);
        seen.add(ch);
      }
    }
    expect(seen.size).toBe(ROOM_CODE_ALPHABET.length);
  });

  it("never contain the letter O or I, which metroserver leaves out", () => {
    for (let i = 0; i < 500; i++) expect(newRoomCode()).not.toMatch(/[OI]/);
  });
});

describe("session tokens", () => {
  it("are 64 lowercase hex characters, all different, and never equal to a user id", () => {
    const tokens = new Set<string>();
    for (let i = 0; i < 1000; i++) {
      const token = newSessionToken();
      expect(token).toMatch(/^[0-9a-f]{64}$/);
      expect(token).not.toBe(newUserId(Date.now()));
      tokens.add(token);
    }
    expect(tokens.size).toBe(1000);
  });

  it("the token a room hands out is not the user id it belongs to, and only its hash is stored beside it", async () => {
    const hub = hubStub();
    const room = await createRoom(hub, await seedDevice(hub, "a"));
    expect(room.token).toMatch(/^[0-9a-f]{64}$/);
    expect(room.token).not.toBe(room.userId);
    const row = await sql(hub, (db) => db.exec("SELECT userId, sessionTokenHash FROM sangat_members").one());
    expect(row).toEqual({ userId: room.userId, sessionTokenHash: await sha256Hex(room.token) });
    expect(row.sessionTokenHash).not.toBe(room.token);
  });
});

describe("live position of a room", () => {
  const track = create(TrackInfoSchema, { id: "t", title: "T", duration: 240000n });
  const state = (init: Partial<{ isPlaying: boolean; position: bigint; lastUpdate: bigint; duration: bigint }>) =>
    create(RoomStateSchema, {
      currentTrack: { ...track, duration: init.duration ?? track.duration },
      isPlaying: init.isPlaying ?? false,
      position: init.position ?? 0n,
      lastUpdate: init.lastUpdate ?? 0n,
    });

  it("a playing room moves on by the time since its last update", () => {
    expect(livePosition(state({ isPlaying: true, position: 5000n, lastUpdate: 1_000_000n }), 1_002_500n)).toBe(7500n);
  });

  it("a paused room stays where it was", () => {
    expect(livePosition(state({ isPlaying: false, position: 5000n, lastUpdate: 1_000_000n }), 1_900_000n)).toBe(5000n);
  });

  it("never runs past the end of the track", () => {
    expect(livePosition(state({ isPlaying: true, position: 200000n, lastUpdate: 1_000_000n }), 1_100_000n)).toBe(240000n);
  });

  it("a track with no known duration is not capped", () => {
    expect(
      livePosition(state({ isPlaying: true, position: 5000n, lastUpdate: 1_000_000n, duration: 0n }), 1_100_000n),
    ).toBe(105000n);
  });

  it("does not go backwards when the clock reads earlier than the last update, or when nothing was ever updated", () => {
    expect(livePosition(state({ isPlaying: true, position: 5000n, lastUpdate: 1_000_000n }), 999_000n)).toBe(5000n);
    expect(livePosition(state({ isPlaying: true, position: 5000n, lastUpdate: 0n }), 1_000_000n)).toBe(5000n);
  });

  it("a negative stored position counts as zero", () => {
    expect(livePosition(state({ isPlaying: false, position: -5n }), 1_000_000n)).toBe(0n);
  });

  it("a room with no track has position zero while playing nothing", () => {
    const empty = create(RoomStateSchema, { isPlaying: true, position: 0n, lastUpdate: 1_000_000n });
    expect(livePosition(empty, 1_001_000n)).toBe(1000n);
  });

  it("request_sync reports the stored position plus the time since the room's last update", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);
    const [bharat] = guests;
    await host.client.send("playback_action", {
      action: "change_track",
      track_info: { id: "t1", title: "T", duration: 240000 },
    });
    await host.client.send("playback_action", { action: "play", position: 5000 });
    await bharat.client.next("sync_playback");
    await bharat.client.next("sync_playback");
    const stored = await sql(hub, (db) => db.exec("SELECT state FROM sangat_rooms").one());
    const { position, lastUpdate } = fromBinary(RoomStateSchema, new Uint8Array(stored.state as ArrayBuffer));
    await sleep(200);

    await bharat.client.send("request_sync");

    const sync = (await bharat.client.next("sync_state")).payload;
    const elapsed = Number(sync.last_update) - Number(lastUpdate);
    expect(elapsed).toBeGreaterThanOrEqual(190);
    expect(sync.is_playing).toBe(true);
    expect(Number(sync.position)).toBe(Number(position) + elapsed);
  });
});
