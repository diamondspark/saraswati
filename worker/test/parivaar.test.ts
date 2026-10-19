// PUT /parivaar/playlist and GET /parivaar/playlists: each phone's own Parivaar Mix playlist,
// listed for the rest of the family. Check order: auth (401), kill switch (503), body size (413),
// body (400), rate limit (429). A device revoked while its body is still arriving is 401.

import { beforeEach, describe, expect, it } from "vitest";
import { listParivaarPlaylists, putParivaarPlaylist } from "../src/routes/parivaar";
import { hubStub, seedDevice, sleep, sql } from "./sangat.helpers";
import {
  ageRateWindow,
  call,
  inHub,
  ISO,
  rateKeys,
  rateRow,
  resetRegistries,
  revoke,
  setSwitch,
  streamedCall,
  stubContext,
  TEST_ADMIN_KEY,
} from "./registry.helpers";

const PL_A = "PLaaaaaaaaaaaaaaaa";
const PL_B = "PLbbbbbbbbbbbbbbbb";

beforeEach(() => resetRegistries(hubStub()));

const put = (token: string | null, body: unknown) =>
  call(hubStub(), "PUT", "/parivaar/playlist", { token, body });
const list = (token: string | null) => call(hubStub(), "GET", "/parivaar/playlists", { token });

const rows = () =>
  sql(hubStub(), (db) =>
    db
      .exec<{ deviceId: string; memberName: string; playlistId: string; updatedAt: number }>(
        "SELECT deviceId, memberName, playlistId, updatedAt FROM parivaar_playlists ORDER BY deviceId",
      )
      .toArray(),
  );

describe("PUT /parivaar/playlist", () => {
  it("upserts this device's row and ignores any deviceId in the body", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    const before = Date.now();
    const first = await put(a.token, { playlistId: PL_A, memberName: "  Asha  ", deviceId: b.deviceId });
    expect(first.status).toBe(200);
    expect(first.contentType).toContain("application/json");
    expect(first.body).toEqual({
      deviceId: a.deviceId,
      memberName: "Asha",
      playlistId: PL_A,
      updatedAt: expect.stringMatching(ISO),
    });
    expect(Date.parse(first.body.updatedAt)).toBeGreaterThanOrEqual(before);

    // Stored as epoch milliseconds, shown as ISO-8601 UTC.
    const [stored] = await rows();
    expect(stored).toEqual({
      deviceId: a.deviceId,
      memberName: "Asha",
      playlistId: PL_A,
      updatedAt: expect.any(Number),
    });
    expect(new Date(stored.updatedAt).toISOString()).toBe(first.body.updatedAt);

    const second = await put(a.token, { playlistId: PL_B, memberName: "Asha D." });
    expect(second.status).toBe(200);
    expect(second.body).toMatchObject({ deviceId: a.deviceId, memberName: "Asha D.", playlistId: PL_B });
    expect(Date.parse(second.body.updatedAt)).toBeGreaterThanOrEqual(Date.parse(first.body.updatedAt));

    const all = await rows();
    expect(all).toHaveLength(1);
    expect(all[0]).toMatchObject({ deviceId: a.deviceId, memberName: "Asha D.", playlistId: PL_B });
  });

  it("is limited to the device: an admin key, a stranger and a pending phone are 401", async () => {
    const hub = hubStub();
    await seedDevice(hub, "pending", "pending");
    const body = { playlistId: PL_A, memberName: "Asha" };

    for (const token of [null, "ab".repeat(32)]) {
      const reply = await put(token, body);
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
    expect((await put(TEST_ADMIN_KEY, body)).status).toBe(401);
    expect(await rows()).toEqual([]);
    // Refused at the door: the admin's budget is not spent on a route the admin cannot use.
    expect(await rateRow(hub, "admin")).toBeUndefined();
    expect(await rateKeys(hub)).toEqual([]);
  });

  it.each([
    ["no playlistId", { memberName: "Asha" }],
    ["a numeric playlistId", { playlistId: 1234567890, memberName: "Asha" }],
    ["a 9-char playlistId", { playlistId: "a".repeat(9), memberName: "Asha" }],
    ["a 65-char playlistId", { playlistId: "a".repeat(65), memberName: "Asha" }],
    ["a playlistId with a space", { playlistId: "PLaaaaaaaa aaaa", memberName: "Asha" }],
    ["a playlistId with a slash", { playlistId: "PLaaaaaaaa/aaaa", memberName: "Asha" }],
    ["a playlistId with a trailing newline", { playlistId: `${PL_A}\n`, memberName: "Asha" }],
    ["a playlistId in non-Latin letters", { playlistId: "प्लेलिस्टआईडी१२", memberName: "Asha" }],
    ["no memberName", { playlistId: PL_A }],
    ["a numeric memberName", { playlistId: PL_A, memberName: 7 }],
    ["an empty memberName", { playlistId: PL_A, memberName: "" }],
    ["a blank memberName", { playlistId: PL_A, memberName: " \t\n " }],
    ["a 51-char memberName", { playlistId: PL_A, memberName: "n".repeat(51) }],
    ["a memberName with a NUL", { playlistId: PL_A, memberName: "As\u0000ha" }],
    ["a memberName that starts with a NUL", { playlistId: PL_A, memberName: "\u0000Asha" }],
    ["a memberName with a newline", { playlistId: PL_A, memberName: "Asha\nD." }],
    ["a memberName with a tab", { playlistId: PL_A, memberName: "Asha\tD." }],
    ["a memberName with U+2028", { playlistId: PL_A, memberName: "Asha\u2028D." }],
    ["a memberName with U+2029", { playlistId: PL_A, memberName: "Asha\u2029D." }],
    ["a memberName with DEL", { playlistId: PL_A, memberName: "Asha\u007fD." }],
    ["a memberName with a C1 control", { playlistId: PL_A, memberName: "Asha\u0085D." }],
    ["a body that is a JSON array", [PL_A]],
    ["a body that is not JSON", "{not json"],
    ["a body that is null", "null"],
  ])("rejects %s with 400 and spends nothing", async (_label, body) => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    const reply = await put(a.token, body);
    expect(reply.status).toBe(400);
    expect(reply.body).toEqual({ error: expect.any(String) });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
  });

  it("accepts the limits themselves: 10 and 64 id characters, 50 name characters after trim", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    expect((await put(a.token, { playlistId: "a-_Z0".repeat(2), memberName: `  ${"n".repeat(50)}  ` })).status).toBe(200);
    expect((await put(b.token, { playlistId: "a-_Z0".repeat(12) + "wxyz", memberName: "म" })).status).toBe(200);
    expect((await rows()).map((r) => r.memberName)).toEqual(["n".repeat(50), "म"]);
  });

  it("the 11th PUT in an hour is 429, and only that device's bucket is spent", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    for (let i = 1; i <= 10; i++) {
      const reply = await put(a.token, { playlistId: PL_A, memberName: `Asha ${i}` });
      expect(reply.status, `PUT ${i}`).toBe(200);
    }
    const refused = await put(a.token, { playlistId: PL_B, memberName: "Asha 11" });
    expect(refused.status).toBe(429);
    expect(refused.body).toEqual({ error: "too many requests" });
    expect((await rows())[0]).toMatchObject({ memberName: "Asha 10", playlistId: PL_A });

    expect((await put(b.token, { playlistId: PL_B, memberName: "Bharat" })).status).toBe(200);
    expect(await rateKeys(hub)).toEqual([`parivaar:${a.deviceId}`, `parivaar:${b.deviceId}`]);
    expect((await rateRow(hub, `parivaar:${a.deviceId}`)).count).toBe(10);
  });

  it("the hour is a fixed 3600 s window", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const key = `parivaar:${a.deviceId}`;
    for (let i = 0; i < 10; i++) await put(a.token, { playlistId: PL_A, memberName: "Asha" });
    expect((await put(a.token, { playlistId: PL_A, memberName: "Asha" })).status).toBe(429);

    await ageRateWindow(hub, key, 3500);
    expect((await put(a.token, { playlistId: PL_A, memberName: "Asha" })).status).toBe(429);

    await ageRateWindow(hub, key, 200);
    expect((await put(a.token, { playlistId: PL_B, memberName: "Asha" })).status).toBe(200);
    expect((await rateRow(hub, key)).count).toBe(1);
  });

  it("checks the body before the limit: a bad body is 400 even when the hour is spent", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    for (let i = 0; i < 10; i++) await put(a.token, { playlistId: PL_A, memberName: "Asha" });

    expect((await put(a.token, { playlistId: "short", memberName: "Asha" })).status).toBe(400);
    expect((await put(a.token, { playlistId: PL_A, memberName: "Asha" })).status).toBe(429);
    expect((await rateRow(hub, `parivaar:${a.deviceId}`)).count).toBe(10);
  });

  it("503 when parivaar_mix is off, before the body is looked at, and it spends nothing", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    expect((await setSwitch(hub, "parivaar_mix", false)).status).toBe(200);
    for (const body of [{ playlistId: PL_A, memberName: "Asha" }, { playlistId: "short" }]) {
      const reply = await put(a.token, body);
      expect(reply.status).toBe(503);
      expect(reply.body).toEqual({ error: "feature disabled" });
    }
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
    // 401 comes first: a caller who is not a device learns nothing about the switch.
    expect((await put("ab".repeat(32), { playlistId: PL_A, memberName: "Asha" })).status).toBe(401);

    // Only its own switch matters.
    await setSwitch(hub, "parivaar_mix", true);
    await setSwitch(hub, "family_playlists", false);
    expect((await put(a.token, { playlistId: PL_A, memberName: "Asha" })).status).toBe(200);
  });

  it("asks the limiter for parivaar:<deviceId>, 10 per 3600 s, after validating, and stamps now()", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const at = Date.UTC(2026, 9, 19, 8, 30, 15, 123);
    const request = (body: unknown) =>
      new Request("https://hub.example/parivaar/playlist", { method: "PUT", body: JSON.stringify(body) });

    await inHub(hub, async (db) => {
      const bad = stubContext(db, { now: at });
      const refusedBad = await putParivaarPlaylist(bad, request({ playlistId: "x", memberName: "A" }), {
        role: "device",
        deviceId: a.deviceId,
      });
      expect(refusedBad.status).toBe(400);
      expect(bad.rateCalls).toEqual([]);

      const spent = stubContext(db, { now: at, allowed: false });
      const refused = await putParivaarPlaylist(spent, request({ playlistId: PL_A, memberName: "Asha" }), {
        role: "device",
        deviceId: a.deviceId,
      });
      expect(refused.status).toBe(429);
      expect(spent.rateCalls).toEqual([[`parivaar:${a.deviceId}`, 3600, 10]]);
      expect(db.exec("SELECT COUNT(*) AS n FROM parivaar_playlists").one().n).toBe(0);

      const ctx = stubContext(db, { now: at });
      const ok = await putParivaarPlaylist(ctx, request({ playlistId: PL_A, memberName: "Asha" }), {
        role: "device",
        deviceId: a.deviceId,
      });
      expect(await ok.json()).toEqual({
        deviceId: a.deviceId,
        memberName: "Asha",
        playlistId: PL_A,
        updatedAt: "2026-10-19T08:30:15.123Z",
      });
      expect(db.exec("SELECT updatedAt FROM parivaar_playlists").one().updatedAt).toBe(at);
    });
  });
});

describe("PUT /parivaar/playlist: text, size and revoke races", () => {
  it("keeps inner single spaces and non-Latin names; only controls are refused", async () => {
    const a = await seedDevice(hubStub(), "a");
    const reply = await put(a.token, { playlistId: PL_A, memberName: "  Asha  Devi\u00a0Sharma  " });
    expect(reply.status).toBe(200);
    expect(reply.body.memberName).toBe("Asha  Devi\u00a0Sharma");
    expect((await put(a.token, { playlistId: PL_A, memberName: "आशा देवी" })).status).toBe(200);
  });

  // A body with `pad` characters of filler so the whole thing is exactly `bytes` long.
  const bodyOfSize = (bytes: number) => {
    const head = JSON.stringify({ playlistId: PL_A, memberName: "Asha", pad: "" });
    return JSON.stringify({ playlistId: PL_A, memberName: "Asha", pad: "p".repeat(bytes - head.length) });
  };

  it("takes a body of 4096 bytes and refuses 4097 with 413 before parsing, spending nothing", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    expect(bodyOfSize(4096)).toHaveLength(4096);

    const refused = await put(a.token, bodyOfSize(4097));
    expect(refused.status).toBe(413);
    expect(refused.body).toEqual({ error: "too large" });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);

    expect((await put(a.token, bodyOfSize(4096))).status).toBe(200);
  });

  it("counts bytes, not characters", async () => {
    const a = await seedDevice(hubStub(), "a");
    // 1400 three-byte characters: 1400 characters but 4200 bytes.
    const reply = await put(a.token, JSON.stringify({ playlistId: PL_A, memberName: "Asha", pad: "म".repeat(1400) }));
    expect(reply.status).toBe(413);
  });

  it("is 413 for a streamed body past the cap even though it never declared a length", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const streamed = streamedCall(hub, "PUT", "/parivaar/playlist", a.token);
    streamed.send('{"playlistId":"' + PL_A + '","memberName":"Asha","pad":"');
    for (let i = 0; i < 6; i++) streamed.send("p".repeat(1000));
    streamed.send('"}');
    streamed.end();

    const reply = await streamed.reply;
    expect(reply.status).toBe(413);
    expect(reply.body).toEqual({ error: "too large" });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
  });

  it("checks size before JSON, after auth and the switch", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const notJson = "x".repeat(5000);

    expect((await put(a.token, notJson)).status).toBe(413);
    expect((await put(a.token, "x".repeat(100))).status).toBe(400);
    expect((await put("ab".repeat(32), notJson)).status).toBe(401);
    expect((await put(TEST_ADMIN_KEY, notJson)).status).toBe(401);
    await setSwitch(hub, "parivaar_mix", false);
    expect((await put(a.token, notJson)).status).toBe(503);
  });

  it("a device revoked while its body is still arriving gets 401, and nothing is written or charged", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    const streamed = streamedCall(hub, "PUT", "/parivaar/playlist", a.token);
    // Let the hub authenticate the request and start waiting for the body.
    await sleep(100);
    expect((await revoke(hub, a.deviceId)).status).toBe(200);
    streamed.send(JSON.stringify({ playlistId: PL_A, memberName: "Asha" }));
    streamed.end();

    const reply = await streamed.reply;
    expect(reply.status).toBe(401);
    expect(reply.body).toEqual({ error: "unauthorized" });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
    // Another phone is unaffected.
    expect((await put(b.token, { playlistId: PL_B, memberName: "Bharat" })).status).toBe(200);
  });

  it("re-checks the roster right after the body is read: revoked between auth and write is 401", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const auth = { role: "device", deviceId: a.deviceId } as const;
    const payload = new TextEncoder().encode(JSON.stringify({ playlistId: PL_A, memberName: "Asha" }));

    await inHub(hub, async (db) => {
      // The revoke lands while the body is being read, as it can between two events.
      const body = new ReadableStream<Uint8Array>({
        pull(controller) {
          db.exec("UPDATE roster SET status = 'revoked', sessionToken = NULL WHERE deviceId = ?", a.deviceId);
          controller.enqueue(payload);
          controller.close();
        },
      });
      const ctx = stubContext(db);
      const reply = await putParivaarPlaylist(
        ctx,
        new Request("https://hub.example/parivaar/playlist", { method: "PUT", body, duplex: "half" } as RequestInit),
        auth,
      );
      expect(reply.status).toBe(401);
      expect(await reply.json()).toEqual({ error: "unauthorized" });
      expect(ctx.rateCalls).toEqual([]);
      expect(db.exec("SELECT COUNT(*) AS n FROM parivaar_playlists").one().n).toBe(0);
    });
  });

  it("asks the hub whether the device is still approved, with its own deviceId", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await inHub(hub, async (db) => {
      const asked: string[] = [];
      const ctx = { ...stubContext(db), isApproved: (id: string) => (asked.push(id), false) };
      const reply = await putParivaarPlaylist(
        ctx,
        new Request("https://hub.example/parivaar/playlist", {
          method: "PUT",
          body: JSON.stringify({ playlistId: PL_A, memberName: "Asha", deviceId: "someone-else" }),
        }),
        { role: "device", deviceId: a.deviceId },
      );
      expect(reply.status).toBe(401);
      expect(asked).toEqual([a.deviceId]);
    });
  });
});

describe("GET /parivaar/playlists", () => {
  it("lists every approved device's row, to a device or the admin", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    await put(a.token, { playlistId: PL_A, memberName: "Asha" });
    await put(b.token, { playlistId: PL_B, memberName: "Bharat" });

    for (const token of [a.token, b.token, TEST_ADMIN_KEY]) {
      const reply = await list(token);
      expect(reply.status).toBe(200);
      expect(reply.contentType).toContain("application/json");
      expect(reply.body).toEqual({
        playlists: [
          { deviceId: a.deviceId, memberName: "Asha", playlistId: PL_A, updatedAt: expect.stringMatching(ISO) },
          { deviceId: b.deviceId, memberName: "Bharat", playlistId: PL_B, updatedAt: expect.stringMatching(ISO) },
        ],
      });
    }
  });

  it("is {playlists: []} before anyone has set one", async () => {
    const a = await seedDevice(hubStub(), "a");
    expect((await list(a.token)).body).toEqual({ playlists: [] });
  });

  it("hides rows of revoked devices, even a row that is still stored", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const gone = await seedDevice(hub, "gone", "revoked");
    const waiting = await seedDevice(hub, "waiting", "pending");
    await put(a.token, { playlistId: PL_A, memberName: "Asha" });
    await sql(hub, (db) => {
      for (const device of [gone, waiting]) {
        db.exec(
          "INSERT INTO parivaar_playlists (deviceId, memberName, playlistId, updatedAt) VALUES (?, 'Ghost', ?, 1)",
          device.deviceId,
          PL_B,
        );
      }
    });
    expect(await rows()).toHaveLength(3);

    for (const token of [a.token, TEST_ADMIN_KEY]) {
      const reply = await list(token);
      expect(reply.body.playlists.map((p: { deviceId: string }) => p.deviceId)).toEqual([a.deviceId]);
    }
  });

  it("orders by memberName, then deviceId", async () => {
    const hub = hubStub();
    // Seeded and written in an order that is neither name order nor deviceId order, so a
    // missing tie-break would show.
    const d = await seedDevice(hub, "d");
    const c = await seedDevice(hub, "c");
    const b = await seedDevice(hub, "b");
    const a = await seedDevice(hub, "a");
    await put(a.token, { playlistId: PL_A, memberName: "Zed" });
    await put(c.token, { playlistId: PL_A, memberName: "Meera" });
    await put(d.token, { playlistId: PL_A, memberName: "Asha" });
    await put(b.token, { playlistId: PL_A, memberName: "Meera" });

    const reply = await list(a.token);
    expect(reply.body.playlists.map((p: { deviceId: string; memberName: string }) => [p.memberName, p.deviceId])).toEqual([
      ["Asha", d.deviceId],
      ["Meera", b.deviceId],
      ["Meera", c.deviceId],
      ["Zed", a.deviceId],
    ]);
  });

  it("is open to a device or the admin only; no token, a stranger or a pending phone is 401", async () => {
    await seedDevice(hubStub(), "pending", "pending");
    for (const token of [null, "ab".repeat(32)]) {
      const reply = await list(token);
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
  });

  it("admin can GET but not PUT; a GET spends the admin budget and never a device bucket", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await put(a.token, { playlistId: PL_A, memberName: "Asha" });
    const before = await rateKeys(hub);

    const viaAdmin = await list(TEST_ADMIN_KEY);
    expect(viaAdmin.status).toBe(200);
    expect(viaAdmin.body.playlists).toHaveLength(1);
    expect((await rateRow(hub, "admin")).count).toBe(1);

    expect((await put(TEST_ADMIN_KEY, { playlistId: PL_B, memberName: "Owner" })).status).toBe(401);
    expect(await rows()).toHaveLength(1);

    await list(a.token);
    expect((await rateRow(hub, "admin")).count).toBe(1);
    expect(await rateKeys(hub)).toEqual(before);
  });

  it("503 when parivaar_mix is off, for a device and for the admin", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await setSwitch(hub, "parivaar_mix", false);

    for (const token of [a.token, TEST_ADMIN_KEY]) {
      const reply = await list(token);
      expect(reply.status).toBe(503);
      expect(reply.body).toEqual({ error: "feature disabled" });
    }
    expect((await list(null)).status).toBe(401);

    await setSwitch(hub, "parivaar_mix", true);
    expect((await list(a.token)).status).toBe(200);
  });

  it("shows updatedAt as ISO-8601 UTC converted from stored epoch milliseconds", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const at = Date.UTC(2026, 0, 2, 3, 4, 5, 60);
    await inHub(hub, async (db) => {
      db.exec(
        "INSERT INTO parivaar_playlists (deviceId, memberName, playlistId, updatedAt) VALUES (?, 'Asha', ?, ?)",
        a.deviceId,
        PL_A,
        at,
      );
      const response = await listParivaarPlaylists(stubContext(db), new Request("https://hub.example/x"), {
        role: "admin",
      });
      expect(await response.json()).toEqual({
        playlists: [{ deviceId: a.deviceId, memberName: "Asha", playlistId: PL_A, updatedAt: "2026-01-02T03:04:05.060Z" }],
      });
    });
  });
});

describe("revoking a device", () => {
  it("removes its Parivaar row and leaves the others", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    await put(a.token, { playlistId: PL_A, memberName: "Asha" });
    await put(b.token, { playlistId: PL_B, memberName: "Bharat" });

    expect((await revoke(hub, b.deviceId)).status).toBe(200);

    expect((await rows()).map((r) => r.deviceId)).toEqual([a.deviceId]);
    expect((await list(TEST_ADMIN_KEY)).body.playlists.map((p: { deviceId: string }) => p.deviceId)).toEqual([a.deviceId]);
    // The old token is dead, so the phone cannot write the row back.
    expect((await put(b.token, { playlistId: PL_B, memberName: "Bharat" })).status).toBe(401);
    expect(await rows()).toHaveLength(1);
  });

  it("revoking a device that has no row is fine", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    expect((await revoke(hub, a.deviceId)).status).toBe(200);
    expect(await rows()).toEqual([]);
  });
});
