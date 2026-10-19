// GET/POST /family-playlists and DELETE /family-playlists/{id}: the playlists the whole family
// shares. Check order: auth (401), kill switch (503), body size (413), body (400), rate limit
// (429), then the rules that read stored rows (409, 404, 403). A device revoked while its body
// is still arriving is 401.

import { beforeEach, describe, expect, it } from "vitest";
import { addFamilyPlaylist } from "../src/routes/familyPlaylists";
import { hubStub, seedDevice, sleep, sql, type Device } from "./sangat.helpers";
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
  UUID,
} from "./registry.helpers";

beforeEach(() => resetRegistries(hubStub()));

const pid = (n: number) => `PLfamily${String(n).padStart(8, "0")}`;

const list = (token: string | null) => call(hubStub(), "GET", "/family-playlists", { token });
const add = (token: string | null, body: unknown) => call(hubStub(), "POST", "/family-playlists", { token, body });
const remove = (token: string | null, id: string) =>
  call(hubStub(), "DELETE", `/family-playlists/${id}`, { token });

const rows = () =>
  sql(hubStub(), (db) =>
    db
      .exec<{ id: string; playlistId: string; title: string; addedByDeviceId: string; addedAt: number }>(
        "SELECT id, playlistId, title, addedByDeviceId, addedAt FROM family_playlists ORDER BY rowid",
      )
      .toArray(),
  );

async function addOk(device: Device, n: number, title = `Playlist ${n}`): Promise<string> {
  const reply = await add(device.token, { playlistId: pid(n), title });
  expect(reply.status, `POST ${n}`).toBe(201);
  return reply.body.id;
}

describe("POST /family-playlists", () => {
  it("creates a row with a uuid id", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    const before = Date.now();
    const reply = await add(a.token, { playlistId: pid(1), title: "  Morning Bhajans  " });
    expect(reply.status).toBe(201);
    expect(reply.contentType).toContain("application/json");
    expect(reply.body).toEqual({
      id: expect.stringMatching(UUID),
      playlistId: pid(1),
      title: "Morning Bhajans",
      addedByDeviceId: a.deviceId,
      addedAt: expect.stringMatching(ISO),
    });
    expect(Date.parse(reply.body.addedAt)).toBeGreaterThanOrEqual(before);

    // Stored as epoch milliseconds, shown as ISO-8601 UTC.
    const [stored] = await rows();
    expect(stored).toEqual({
      id: reply.body.id,
      playlistId: pid(1),
      title: "Morning Bhajans",
      addedByDeviceId: a.deviceId,
      addedAt: expect.any(Number),
    });
    expect(new Date(stored.addedAt).toISOString()).toBe(reply.body.addedAt);

    const second = await add(a.token, { playlistId: pid(2), title: "Evening Aarti" });
    expect(second.body.id).not.toBe(reply.body.id);
  });

  it("ignores an id, addedBy or addedAt in the body", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    const reply = await add(a.token, {
      playlistId: pid(1),
      title: "Mine",
      id: "chosen-by-caller",
      addedByDeviceId: b.deviceId,
      addedAt: "2000-01-01T00:00:00.000Z",
    });
    expect(reply.status).toBe(201);
    expect(reply.body.id).toMatch(UUID);
    expect(reply.body.addedByDeviceId).toBe(a.deviceId);
    expect(reply.body.addedAt).not.toBe("2000-01-01T00:00:00.000Z");
  });

  it("duplicate playlistId is 409, from the same device or another, and stores nothing", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    await addOk(a, 1, "First");

    for (const device of [a, b]) {
      const reply = await add(device.token, { playlistId: pid(1), title: "Again" });
      expect(reply.status).toBe(409);
      expect(reply.body).toEqual({ error: "playlist already added" });
    }
    // YouTube ids are case-sensitive: a different case is a different playlist.
    expect((await add(b.token, { playlistId: pid(1).toUpperCase(), title: "Other" })).status).toBe(201);
    expect(await rows()).toHaveLength(2);
    expect((await rows())[0]).toMatchObject({ title: "First", addedByDeviceId: a.deviceId });
  });

  it("the 21st playlist is 409", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    const c = await seedDevice(hub, "c");
    for (let n = 1; n <= 10; n++) await addOk(a, n);
    for (let n = 11; n <= 20; n++) await addOk(b, n);

    const full = await add(c.token, { playlistId: pid(21), title: "One too many" });
    expect(full.status).toBe(409);
    expect(full.body).toEqual({ error: "family playlist limit reached" });
    expect(await rows()).toHaveLength(20);

    // A duplicate is still reported as a duplicate, and room opens up when one is deleted.
    expect((await add(c.token, { playlistId: pid(5), title: "Dup" })).body).toEqual({ error: "playlist already added" });
    const id = (await rows())[0].id;
    expect((await remove(TEST_ADMIN_KEY, id)).status).toBe(204);
    expect((await add(c.token, { playlistId: pid(21), title: "Fits now" })).status).toBe(201);
  });

  it("is limited to the device: an admin key, a stranger and a pending phone are 401", async () => {
    const hub = hubStub();
    await seedDevice(hub, "pending", "pending");
    const body = { playlistId: pid(1), title: "Bhajans" };

    for (const token of [null, "ab".repeat(32)]) {
      const reply = await add(token, body);
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
    expect((await add(TEST_ADMIN_KEY, body)).status).toBe(401);
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
    // Refused at the door: the admin's budget is not spent on a route the admin cannot use.
    expect(await rateRow(hub, "admin")).toBeUndefined();
  });

  it.each([
    ["no playlistId", { title: "Bhajans" }],
    ["a numeric playlistId", { playlistId: 1234567890, title: "Bhajans" }],
    ["a 9-char playlistId", { playlistId: "a".repeat(9), title: "Bhajans" }],
    ["a 65-char playlistId", { playlistId: "a".repeat(65), title: "Bhajans" }],
    ["a playlistId with a space", { playlistId: "PLaaaaaaaa aaaa", title: "Bhajans" }],
    ["a playlistId with a trailing newline", { playlistId: `${pid(1)}\n`, title: "Bhajans" }],
    ["no title", { playlistId: pid(1) }],
    ["a numeric title", { playlistId: pid(1), title: 5 }],
    ["an empty title", { playlistId: pid(1), title: "" }],
    ["a blank title", { playlistId: pid(1), title: "  \n\t " }],
    ["a 151-char title", { playlistId: pid(1), title: "t".repeat(151) }],
    ["a title with a NUL", { playlistId: pid(1), title: "Bha\u0000jans" }],
    ["a title that starts with a NUL", { playlistId: pid(1), title: "\u0000Bhajans" }],
    ["a title with a newline", { playlistId: pid(1), title: "Morning\nBhajans" }],
    ["a title with a tab", { playlistId: pid(1), title: "Morning\tBhajans" }],
    ["a title with U+2028", { playlistId: pid(1), title: "Morning\u2028Bhajans" }],
    ["a title with U+2029", { playlistId: pid(1), title: "Morning\u2029Bhajans" }],
    ["a title with DEL", { playlistId: pid(1), title: "Morning\u007fBhajans" }],
    ["a title with a C1 control", { playlistId: pid(1), title: "Morning\u0085Bhajans" }],
    ["a body that is a JSON array", [pid(1)]],
    ["a body that is not JSON", "{not json"],
    ["a body that is null", "null"],
  ])("rejects %s with 400 and spends nothing", async (_label, body) => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    const reply = await add(a.token, body);
    expect(reply.status).toBe(400);
    expect(reply.body).toEqual({ error: expect.any(String) });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
  });

  it("accepts the limits themselves: 150 title characters after trim, 10 and 64 id characters", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    expect((await add(a.token, { playlistId: "a-_Z0".repeat(2), title: ` ${"t".repeat(150)} ` })).status).toBe(201);
    expect((await add(a.token, { playlistId: "a-_Z0".repeat(12) + "wxyz", title: "भजन" })).status).toBe(201);
    expect((await rows()).map((r) => r.title.length)).toEqual([150, 3]);
  });

  it("the 21st POST in an hour is 429, and deleting a playlist does not give the budget back", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    for (let n = 1; n <= 20; n++) {
      const id = await addOk(a, n);
      expect((await remove(a.token, id)).status, `DELETE ${n}`).toBe(204);
    }
    const refused = await add(a.token, { playlistId: pid(21), title: "Too soon" });
    expect(refused.status).toBe(429);
    expect(refused.body).toEqual({ error: "too many requests" });
    expect(await rows()).toEqual([]);

    expect((await add(b.token, { playlistId: pid(21), title: "Other phone" })).status).toBe(201);
    expect(await rateKeys(hub)).toEqual([`familyplaylists:${a.deviceId}`, `familyplaylists:${b.deviceId}`]);
    expect((await rateRow(hub, `familyplaylists:${a.deviceId}`)).count).toBe(20);
  });

  it("the hour is a fixed 3600 s window", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const key = `familyplaylists:${a.deviceId}`;
    for (let n = 1; n <= 20; n++) await remove(a.token, await addOk(a, n));
    expect((await add(a.token, { playlistId: pid(30), title: "x" })).status).toBe(429);

    await ageRateWindow(hub, key, 3500);
    expect((await add(a.token, { playlistId: pid(30), title: "x" })).status).toBe(429);

    await ageRateWindow(hub, key, 200);
    expect((await add(a.token, { playlistId: pid(30), title: "x" })).status).toBe(201);
    expect((await rateRow(hub, key)).count).toBe(1);
  });

  it("checks the body before the limit and the limit before the stored rules", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    for (let n = 1; n <= 20; n++) await remove(a.token, await addOk(a, n));
    await addOk(await seedDevice(hub, "b"), 99);

    expect((await add(a.token, { playlistId: "short", title: "x" })).status).toBe(400);
    // pid(99) is already there, but the spent hour answers first.
    expect((await add(a.token, { playlistId: pid(99), title: "x" })).status).toBe(429);
    expect((await rateRow(hub, `familyplaylists:${a.deviceId}`)).count).toBe(20);
  });

  it("503 when family_playlists is off, before the body is looked at, and it spends nothing", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");

    expect((await setSwitch(hub, "family_playlists", false)).status).toBe(200);
    for (const body of [{ playlistId: pid(1), title: "Bhajans" }, { playlistId: "short" }]) {
      const reply = await add(a.token, body);
      expect(reply.status).toBe(503);
      expect(reply.body).toEqual({ error: "feature disabled" });
    }
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
    expect((await add("ab".repeat(32), { playlistId: pid(1), title: "x" })).status).toBe(401);

    // Only its own switch matters.
    await setSwitch(hub, "family_playlists", true);
    await setSwitch(hub, "parivaar_mix", false);
    expect((await add(a.token, { playlistId: pid(1), title: "Bhajans" })).status).toBe(201);
  });

  it("asks the limiter for familyplaylists:<deviceId>, 20 per 3600 s, after validating, and stamps now()", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const at = Date.UTC(2026, 9, 19, 8, 30, 15, 123);
    const request = (body: unknown) =>
      new Request("https://hub.example/family-playlists", { method: "POST", body: JSON.stringify(body) });
    const auth = { role: "device", deviceId: a.deviceId } as const;

    await inHub(hub, async (db) => {
      const bad = stubContext(db, { now: at });
      expect((await addFamilyPlaylist(bad, request({ playlistId: "x", title: "T" }), auth)).status).toBe(400);
      expect(bad.rateCalls).toEqual([]);

      const spent = stubContext(db, { now: at, allowed: false });
      expect((await addFamilyPlaylist(spent, request({ playlistId: pid(1), title: "T" }), auth)).status).toBe(429);
      expect(spent.rateCalls).toEqual([[`familyplaylists:${a.deviceId}`, 3600, 20]]);
      expect(db.exec("SELECT COUNT(*) AS n FROM family_playlists").one().n).toBe(0);

      const ctx = stubContext(db, { now: at });
      const ok = await addFamilyPlaylist(ctx, request({ playlistId: pid(1), title: "T" }), auth);
      expect(ok.status).toBe(201);
      expect(await ok.json()).toMatchObject({ addedAt: "2026-10-19T08:30:15.123Z" });
      expect(db.exec("SELECT addedAt FROM family_playlists").one().addedAt).toBe(at);
    });
  });
});

describe("POST /family-playlists: text, size and revoke races", () => {
  it("keeps inner spaces and non-Latin titles; only controls are refused", async () => {
    const a = await seedDevice(hubStub(), "a");
    const reply = await add(a.token, { playlistId: pid(1), title: "  Morning  Bhajans\u00a0Mix  " });
    expect(reply.status).toBe(201);
    expect(reply.body.title).toBe("Morning  Bhajans\u00a0Mix");
    expect((await add(a.token, { playlistId: pid(2), title: "सुबह के भजन" })).status).toBe(201);
  });

  // A body with filler so the whole thing is exactly `bytes` long.
  const bodyOfSize = (bytes: number) => {
    const head = JSON.stringify({ playlistId: pid(1), title: "Bhajans", pad: "" });
    return JSON.stringify({ playlistId: pid(1), title: "Bhajans", pad: "p".repeat(bytes - head.length) });
  };

  it("takes a body of 4096 bytes and refuses 4097 with 413 before parsing, spending nothing", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    expect(bodyOfSize(4096)).toHaveLength(4096);

    const refused = await add(a.token, bodyOfSize(4097));
    expect(refused.status).toBe(413);
    expect(refused.body).toEqual({ error: "too large" });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);

    expect((await add(a.token, bodyOfSize(4096))).status).toBe(201);
  });

  it("counts bytes, not characters", async () => {
    const a = await seedDevice(hubStub(), "a");
    // 1400 three-byte characters: 1400 characters but 4200 bytes.
    const reply = await add(a.token, JSON.stringify({ playlistId: pid(1), title: "Bhajans", pad: "म".repeat(1400) }));
    expect(reply.status).toBe(413);
  });

  it("is 413 for a streamed body past the cap even though it never declared a length", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const streamed = streamedCall(hub, "POST", "/family-playlists", a.token);
    streamed.send('{"playlistId":"' + pid(1) + '","title":"Bhajans","pad":"');
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

    expect((await add(a.token, notJson)).status).toBe(413);
    expect((await add(a.token, "x".repeat(100))).status).toBe(400);
    expect((await add("ab".repeat(32), notJson)).status).toBe(401);
    expect((await add(TEST_ADMIN_KEY, notJson)).status).toBe(401);
    await setSwitch(hub, "family_playlists", false);
    expect((await add(a.token, notJson)).status).toBe(503);
  });

  it("a device revoked while its body is still arriving gets 401, and nothing is written or charged", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    const streamed = streamedCall(hub, "POST", "/family-playlists", a.token);
    // Let the hub authenticate the request and start waiting for the body.
    await sleep(100);
    expect((await revoke(hub, a.deviceId)).status).toBe(200);
    streamed.send(JSON.stringify({ playlistId: pid(1), title: "Bhajans" }));
    streamed.end();

    const reply = await streamed.reply;
    expect(reply.status).toBe(401);
    expect(reply.body).toEqual({ error: "unauthorized" });
    expect(await rows()).toEqual([]);
    expect(await rateKeys(hub)).toEqual([]);
    expect((await add(b.token, { playlistId: pid(1), title: "Bhajans" })).status).toBe(201);
  });

  it("re-checks the roster right after the body is read: revoked between auth and write is 401", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const auth = { role: "device", deviceId: a.deviceId } as const;
    const payload = new TextEncoder().encode(JSON.stringify({ playlistId: pid(1), title: "Bhajans" }));

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
      const reply = await addFamilyPlaylist(
        ctx,
        new Request("https://hub.example/family-playlists", { method: "POST", body, duplex: "half" } as RequestInit),
        auth,
      );
      expect(reply.status).toBe(401);
      expect(await reply.json()).toEqual({ error: "unauthorized" });
      expect(ctx.rateCalls).toEqual([]);
      expect(db.exec("SELECT COUNT(*) AS n FROM family_playlists").one().n).toBe(0);
    });
  });

  it("asks the hub whether the device is still approved, with its own deviceId", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await inHub(hub, async (db) => {
      const asked: string[] = [];
      const ctx = { ...stubContext(db), isApproved: (id: string) => (asked.push(id), false) };
      const reply = await addFamilyPlaylist(
        ctx,
        new Request("https://hub.example/family-playlists", {
          method: "POST",
          body: JSON.stringify({ playlistId: pid(1), title: "Bhajans" }),
        }),
        { role: "device", deviceId: a.deviceId },
      );
      expect(reply.status).toBe(401);
      expect(asked).toEqual([a.deviceId]);
    });
  });
});

describe("GET /family-playlists", () => {
  it("lists the playlists in the order they were added, to a device or the admin", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    const first = await addOk(b, 1, "Bhajans");
    const second = await addOk(a, 2, "Aarti");

    for (const token of [a.token, b.token, TEST_ADMIN_KEY]) {
      const reply = await list(token);
      expect(reply.status).toBe(200);
      expect(reply.contentType).toContain("application/json");
      expect(reply.body).toEqual({
        playlists: [
          { id: first, playlistId: pid(1), title: "Bhajans", addedByDeviceId: b.deviceId, addedAt: expect.stringMatching(ISO) },
          { id: second, playlistId: pid(2), title: "Aarti", addedByDeviceId: a.deviceId, addedAt: expect.stringMatching(ISO) },
        ],
      });
    }
  });

  it("is {playlists: []} before anyone has added one, and 401 without a device or admin token", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await seedDevice(hub, "pending", "pending");
    expect((await list(a.token)).body).toEqual({ playlists: [] });
    for (const token of [null, "ab".repeat(32)]) {
      const reply = await list(token);
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
  });

  it("shows addedAt as ISO-8601 UTC converted from stored epoch milliseconds", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await sql(hub, (db) => {
      db.exec(
        "INSERT INTO family_playlists (id, playlistId, title, addedByDeviceId, addedAt) VALUES ('row-1', ?, 'Old', ?, ?)",
        pid(1),
        a.deviceId,
        Date.UTC(2026, 0, 2, 3, 4, 5, 60),
      );
    });
    expect((await list(a.token)).body.playlists[0].addedAt).toBe("2026-01-02T03:04:05.060Z");
  });

  it("an admin GET spends the admin budget and never a device bucket; a device GET spends neither", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await addOk(a, 1);
    const before = await rateKeys(hub);

    await list(a.token);
    expect(await rateRow(hub, "admin")).toBeUndefined();
    await list(TEST_ADMIN_KEY);
    expect((await rateRow(hub, "admin")).count).toBe(1);
    expect(await rateKeys(hub)).toEqual(before);
  });

  it("503 when family_playlists is off, for a device and for the admin", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await setSwitch(hub, "family_playlists", false);

    for (const token of [a.token, TEST_ADMIN_KEY]) {
      const reply = await list(token);
      expect(reply.status).toBe(503);
      expect(reply.body).toEqual({ error: "feature disabled" });
    }
    expect((await list(null)).status).toBe(401);

    await setSwitch(hub, "family_playlists", true);
    expect((await list(a.token)).status).toBe(200);
  });
});

describe("DELETE /family-playlists/{id}", () => {
  it("the adding device and admin can DELETE, another device gets 403", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    const mine = await addOk(a, 1);
    const adminsTarget = await addOk(a, 2);
    const keep = await addOk(b, 3);

    const refused = await remove(b.token, mine);
    expect(refused.status).toBe(403);
    expect(refused.body).toEqual({ error: "only the device that added it can remove it" });
    expect((await rows()).map((r) => r.id)).toEqual([mine, adminsTarget, keep]);

    const byOwner = await remove(a.token, mine);
    expect(byOwner.status).toBe(204);
    expect(byOwner.body).toBe("");

    const byAdmin = await remove(TEST_ADMIN_KEY, adminsTarget);
    expect(byAdmin.status).toBe(204);
    expect(byAdmin.body).toBe("");

    expect((await rows()).map((r) => r.id)).toEqual([keep]);
    expect((await list(b.token)).body.playlists.map((p: { id: string }) => p.id)).toEqual([keep]);
  });

  it("an unknown id is 404, and a second DELETE of the same id too", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const id = await addOk(a, 1);

    for (const token of [a.token, TEST_ADMIN_KEY]) {
      const reply = await remove(token, "no-such-playlist");
      expect(reply.status).toBe(404);
      expect(reply.body).toEqual({ error: "not found" });
    }
    expect((await remove(a.token, id)).status).toBe(204);
    expect((await remove(a.token, id)).status).toBe(404);
    expect((await remove(TEST_ADMIN_KEY, id)).status).toBe(404);
  });

  it("a malformed escape in the id is 404, not a crash", async () => {
    const a = await seedDevice(hubStub(), "a");
    const reply = await remove(a.token, "%E0%A4%A");
    expect(reply.status).toBe(404);
  });

  it("an id that is not one segment is not a route", async () => {
    const a = await seedDevice(hubStub(), "a");
    const id = await addOk(a, 1);
    expect((await remove(a.token, `${id}/extra`)).status).toBe(404);
    expect((await call(hubStub(), "DELETE", "/family-playlists", { token: a.token })).status).toBe(404);
    expect(await rows()).toHaveLength(1);
  });

  it("is 401 for no token, a stranger or a pending phone, even for an id that does not exist", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await seedDevice(hub, "pending", "pending");
    const id = await addOk(a, 1);

    for (const token of [null, "ab".repeat(32)]) {
      expect((await remove(token, id)).status).toBe(401);
      expect((await remove(token, "no-such-playlist")).status).toBe(401);
    }
    expect(await rows()).toHaveLength(1);
  });

  it("an admin DELETE spends the admin budget and never a device bucket; a device DELETE spends neither", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const first = await addOk(a, 1);
    const second = await addOk(a, 2);
    const before = await rateKeys(hub);

    expect((await remove(a.token, first)).status).toBe(204);
    expect(await rateRow(hub, "admin")).toBeUndefined();
    expect((await remove(TEST_ADMIN_KEY, second)).status).toBe(204);
    expect((await rateRow(hub, "admin")).count).toBe(1);
    expect(await rateKeys(hub)).toEqual(before);
    expect((await rateRow(hub, `familyplaylists:${a.deviceId}`)).count).toBe(2);
  });

  it("503 when family_playlists is off, and nothing is deleted", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const id = await addOk(a, 1);
    await setSwitch(hub, "family_playlists", false);

    for (const token of [a.token, TEST_ADMIN_KEY]) {
      const reply = await remove(token, id);
      expect(reply.status).toBe(503);
      expect(reply.body).toEqual({ error: "feature disabled" });
    }
    expect(await rows()).toHaveLength(1);
    expect((await remove(null, id)).status).toBe(401);

    await setSwitch(hub, "family_playlists", true);
    expect((await remove(a.token, id)).status).toBe(204);
  });
});

describe("revoking the device that added a playlist", () => {
  it("keeps the playlist, which belongs to the family; only the admin can still delete it", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");
    const id = await addOk(a, 1, "Bhajans");

    expect((await revoke(hub, a.deviceId)).status).toBe(200);

    expect((await rows()).map((r) => r.id)).toEqual([id]);
    expect((await list(b.token)).body.playlists).toEqual([
      { id, playlistId: pid(1), title: "Bhajans", addedByDeviceId: a.deviceId, addedAt: expect.stringMatching(ISO) },
    ]);
    // The revoked phone's token no longer authenticates, and another phone is not the adder.
    expect((await remove(a.token, id)).status).toBe(401);
    expect((await add(a.token, { playlistId: pid(2), title: "x" })).status).toBe(401);
    expect((await remove(b.token, id)).status).toBe(403);
    expect((await remove(TEST_ADMIN_KEY, id)).status).toBe(204);
    expect(await rows()).toEqual([]);
  });
});
