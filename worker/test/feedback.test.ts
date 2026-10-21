// SARASWATI: feedback — POST/GET /feedback and PUT /feedback/{id}: validation, auth, limits, storage.

import { beforeEach, describe, expect, it } from "vitest";
import { postFeedback, storeFeedback } from "../src/routes/feedback";
import { hubStub, seedDevice, sql } from "./sangat.helpers";
import {
  ageRateWindow,
  call,
  inHub,
  rateRow,
  resetRegistries,
  revoke,
  setSwitch,
  streamedCall,
  stubContext,
  TEST_ADMIN_KEY,
} from "./registry.helpers";

const DIAG = { appVersion: "13.7.0-s4119", android: "17 (API 37)", device: "Google Pixel 8 Pro" };

beforeEach(async () => {
  const hub = hubStub();
  await resetRegistries(hub);
  await sql(hub, (db) => {
    db.exec("DELETE FROM feedback");
    db.exec("DELETE FROM rate_limits WHERE key LIKE 'feedback:%' OR key = 'admin'");
  });
});

const post = (token: string | null, body: unknown) =>
  call(hubStub(), "POST", "/feedback", { token, body });
const list = (token: string | null, query = "") =>
  call(hubStub(), "GET", `/feedback${query}`, { token });
const put = (token: string | null, id: string, body: unknown) =>
  call(hubStub(), "PUT", `/feedback/${encodeURIComponent(id)}`, { token, body });

/** Sends one idea as the admin and returns its id. */
async function send(text: string, kind = "idea", token = TEST_ADMIN_KEY): Promise<string> {
  const reply = await post(token, { kind, text });
  expect(reply.status).toBe(201);
  return reply.body.id;
}

const storedRows = () =>
  sql(hubStub(), (db) => db.exec<Record<string, any>>("SELECT * FROM feedback ORDER BY rowid").toArray());

describe("POST /feedback", () => {
  it("stores a device's report with its roster nickname and diagnostics, and returns 201 {id}", async () => {
    const a = await seedDevice(hubStub(), "a");
    const before = Date.now();

    const reply = await post(a.token, { kind: "bug", text: "  Songs skip  ", diagnostics: DIAG });

    expect(reply.status).toBe(201);
    expect(reply.contentType).toContain("application/json");
    expect(Object.keys(reply.body)).toEqual(["id"]);
    const [row] = await storedRows();
    expect(row).toEqual({
      id: reply.body.id,
      device_id: a.deviceId,
      nickname: "Test phone a",
      kind: "bug",
      text: "Songs skip",
      app_version: DIAG.appVersion,
      android: DIAG.android,
      device: DIAG.device,
      status: "new",
      created_at: expect.any(Number),
      updated_at: row.created_at,
      issue_url: null,
    });
    expect(row.created_at).toBeGreaterThanOrEqual(before);
  });

  it("stores the admin's report as device 'owner', nickname 'Owner'", async () => {
    const id = await send("From the owner", "idea");

    const [row] = await storedRows();
    expect(row).toMatchObject({ id, device_id: "owner", nickname: "Owner", kind: "idea" });
    expect(row.app_version).toBeNull();
  });

  it("takes diagnostics as optional, field by field", async () => {
    const a = await seedDevice(hubStub(), "a");
    expect((await post(a.token, { kind: "bug", text: "x" })).status).toBe(201);
    expect((await post(a.token, { kind: "bug", text: "x", diagnostics: null })).status).toBe(201);
    expect(
      (await post(a.token, { kind: "bug", text: "x", diagnostics: { appVersion: "1.0" } })).status,
    ).toBe(201);
    const rows = await storedRows();
    expect(rows.map((r) => [r.app_version, r.android, r.device])).toEqual([
      [null, null, null],
      [null, null, null],
      ["1.0", null, null],
    ]);
  });

  it.each([
    ["no auth", null, 401],
    ["a wrong bearer", "not-a-token", 401],
  ])("%s is %i-class unauthorized and stores nothing", async (_label, token, status) => {
    expect((await post(token, { kind: "bug", text: "x" })).status).toBe(status);
    expect(await storedRows()).toEqual([]);
  });

  it("answers 401 for a pending or revoked device", async () => {
    const pending = await seedDevice(hubStub(), "p", "pending");
    const revoked = await seedDevice(hubStub(), "r", "revoked");
    expect((await post(pending.token, { kind: "bug", text: "x" })).status).toBe(401);
    expect((await post(revoked.token, { kind: "bug", text: "x" })).status).toBe(401);
    expect(await storedRows()).toEqual([]);
  });

  it("answers 401 to a device revoked while its body was still arriving", async () => {
    const a = await seedDevice(hubStub(), "a");
    const pending = streamedCall(hubStub(), "POST", "/feedback", a.token);
    pending.send('{"kind":"bug",');
    await revoke(hubStub(), a.deviceId);
    pending.send('"text":"late"}');
    pending.end();

    expect((await pending.reply).status).toBe(401);
    expect(await storedRows()).toEqual([]);
  });

  describe("text", () => {
    it.each([
      ["empty", ""],
      ["whitespace only", " \n\t  \u00a0 "],
      ["controls only", "\u0000\u0007\u001b"],
      ["a number", 5],
      ["null", null],
      ["an array", ["x"]],
      ["missing", undefined],
    ])("rejects %s with 400", async (_label, text) => {
      const a = await seedDevice(hubStub(), "a");
      expect((await post(a.token, { kind: "bug", text })).status).toBe(400);
      expect(await storedRows()).toEqual([]);
    });

    it("accepts exactly 1000 characters and rejects 1001", async () => {
      const a = await seedDevice(hubStub(), "a");
      expect((await post(a.token, { kind: "bug", text: "a".repeat(1000) })).status).toBe(201);
      expect((await post(a.token, { kind: "bug", text: "a".repeat(1001) })).status).toBe(400);
      // Counted after the trim: padding does not push a 1000-character text over.
      expect((await post(a.token, { kind: "bug", text: ` ${"b".repeat(1000)} ` })).status).toBe(201);
      const rows = await storedRows();
      expect(rows.map((r) => r.text.length)).toEqual([1000, 1000]);
    });

    it("counts the length after the control characters are stripped", async () => {
      const a = await seedDevice(hubStub(), "a");
      const text = `${"a".repeat(1000)}\u0000\u0001\r`;
      expect((await post(a.token, { kind: "bug", text })).status).toBe(201);
    });

    it("strips control characters except newlines, and turns CRLF into LF", async () => {
      const a = await seedDevice(hubStub(), "a");
      const id = (
        await post(a.token, {
          kind: "bug",
          text: "line1\u0000\nline2\r\n\u0007line3\ttabbed\u0085\u2028end\u007f",
        })
      ).body.id;

      expect((await storedRows())[0].text).toBe("line1\nline2\nline3tabbedend");
      const items = (await list(TEST_ADMIN_KEY)).body.items;
      expect(items.find((i: any) => i.id === id).text).toBe("line1\nline2\nline3tabbedend");
    });

    it("round-trips Hindi, emoji and RTL text unchanged", async () => {
      const a = await seedDevice(hubStub(), "a");
      const samples = [
        "गाना बार-बार रुक जाता है, कृपया ठीक करें",
        "Love it 🎶🙏🏽 👨‍👩‍👧 \u{1F600}",
        "مرحبا بالعالم \u200f שלום עולם \u202b",
      ];
      for (const text of samples) {
        expect((await post(a.token, { kind: "idea", text })).status).toBe(201);
      }

      const items = (await list(TEST_ADMIN_KEY, "?status=all")).body.items;
      expect(items.map((i: any) => i.text).sort()).toEqual([...samples].sort());
      expect((await storedRows()).map((r) => r.text)).toEqual(samples);
    });

    it("stores backticks, @everyone, </details>, HTML, markdown and SQL quotes verbatim", async () => {
      const a = await seedDevice(hubStub(), "a");
      const nasty = [
        "```\nfence\n``` ````` @everyone",
        "</details><script>alert(1)</script> <img src=x onerror=y>",
        "[click](javascript:alert(1)) **bold** # heading > quote",
        "Robert'); DROP TABLE feedback;-- ' OR '1'='1 \" ; ?",
        "${process.env} {{template}} $(rm -rf /) `id`",
      ];
      for (const text of nasty) {
        expect((await post(a.token, { kind: "bug", text })).status).toBe(201);
      }

      const items = (await list(TEST_ADMIN_KEY, "?status=all")).body.items;
      expect(items.map((i: any) => i.text).sort()).toEqual([...nasty].sort());
      // The table is still there with every row.
      expect(await storedRows()).toHaveLength(nasty.length);
    });

    it("keeps untrusted text out of SQL even when it holds the kind or status words", async () => {
      const a = await seedDevice(hubStub(), "a");
      const id = (await post(a.token, { kind: "bug", text: "'; UPDATE feedback SET status='done'; --" })).body.id;
      expect((await storedRows()).find((r) => r.id === id)!.status).toBe("new");
    });
  });

  describe("kind and diagnostics", () => {
    it.each([
      ["an unknown kind", { kind: "rant", text: "x" }],
      ["a capitalised kind", { kind: "Bug", text: "x" }],
      ["a missing kind", { text: "x" }],
      ["a non-string kind", { kind: 1, text: "x" }],
      ["an array body", [{ kind: "bug", text: "x" }]],
      ["invalid JSON", "{not json"],
      ["diagnostics as a string", { kind: "bug", text: "x", diagnostics: "13.7" }],
      ["diagnostics as an array", { kind: "bug", text: "x", diagnostics: [] }],
      ["a non-string appVersion", { kind: "bug", text: "x", diagnostics: { appVersion: 13 } }],
      ["a non-string android", { kind: "bug", text: "x", diagnostics: { android: {} } }],
      ["a non-string device", { kind: "bug", text: "x", diagnostics: { device: ["Pixel"] } }],
      ["appVersion over 40", { kind: "bug", text: "x", diagnostics: { appVersion: "v".repeat(41) } }],
      ["android over 40", { kind: "bug", text: "x", diagnostics: { android: "a".repeat(41) } }],
      ["device over 80", { kind: "bug", text: "x", diagnostics: { device: "d".repeat(81) } }],
    ])("rejects %s with 400", async (_label, body) => {
      const a = await seedDevice(hubStub(), "a");
      expect((await post(a.token, body)).status).toBe(400);
      expect(await storedRows()).toEqual([]);
    });

    it("accepts diagnostics at exactly their caps, and strips controls from them", async () => {
      const a = await seedDevice(hubStub(), "a");
      const reply = await post(a.token, {
        kind: "bug",
        text: "x",
        diagnostics: {
          appVersion: "v".repeat(40),
          android: "a".repeat(40),
          device: ` ${"d".repeat(80)}\n\u0000 `,
        },
      });
      expect(reply.status).toBe(201);
      const [row] = await storedRows();
      expect([row.app_version.length, row.android.length, row.device.length]).toEqual([40, 40, 80]);
    });

    it("ignores fields it does not know, so nothing else can ride along", async () => {
      const a = await seedDevice(hubStub(), "a");
      const reply = await post(a.token, {
        kind: "bug",
        text: "x",
        deviceId: "owner",
        nickname: "Forged",
        status: "done",
        issue_url: "https://evil.example",
        diagnostics: { ...DIAG, token: "secret", ip: "1.2.3.4" },
      });
      expect(reply.status).toBe(201);
      const [row] = await storedRows();
      expect(row).toMatchObject({
        device_id: a.deviceId,
        nickname: "Test phone a",
        status: "new",
        issue_url: null,
      });
      expect(JSON.stringify(row)).not.toContain("secret");
      expect(JSON.stringify(row)).not.toContain("1.2.3.4");
    });
  });

  it("answers 413 to a body past 8 KiB and 503 once the feedback switch is off", async () => {
    const a = await seedDevice(hubStub(), "a");
    const big = await post(a.token, { kind: "bug", text: "a".repeat(9000) });
    expect(big.status).toBe(413);

    expect((await setSwitch(hubStub(), "feedback", false)).status).toBe(200);
    expect((await post(a.token, { kind: "bug", text: "x" })).status).toBe(503);
    expect((await post(TEST_ADMIN_KEY, { kind: "bug", text: "x" })).status).toBe(503);
    // An unauthenticated caller is still 401, not told about the switch.
    expect((await post(null, { kind: "bug", text: "x" })).status).toBe(401);
    expect(await storedRows()).toEqual([]);

    expect((await setSwitch(hubStub(), "feedback", true)).status).toBe(200);
    expect((await post(a.token, { kind: "bug", text: "x" })).status).toBe(201);
  });

  describe("rate limits", () => {
    it("allows 5 an hour per device, then 429, and a rejected or invalid post is not stored", async () => {
      const hub = hubStub();
      const a = await seedDevice(hub, "a");
      const b = await seedDevice(hub, "b");

      // An invalid body is a 400 and costs nothing.
      expect((await post(a.token, { kind: "bug", text: "" })).status).toBe(400);
      for (let i = 0; i < 5; i++) {
        expect((await post(a.token, { kind: "bug", text: `n${i}` })).status).toBe(201);
      }
      const sixth = await post(a.token, { kind: "bug", text: "six" });
      expect(sixth.status).toBe(429);
      expect(sixth.body).toEqual({ error: "too many requests" });
      expect(await storedRows()).toHaveLength(5);

      // Per device: another phone is unaffected.
      expect((await post(b.token, { kind: "bug", text: "b" })).status).toBe(201);

      // A new hour opens the window again.
      await ageRateWindow(hub, `feedback:hour:${a.deviceId}`, 3601);
      expect((await post(a.token, { kind: "bug", text: "again" })).status).toBe(201);
    });

    it("allows 20 a day per device, then 429 even in a fresh hour, and a new day opens again", async () => {
      const hub = hubStub();
      const a = await seedDevice(hub, "a");
      for (let round = 0; round < 4; round++) {
        for (let i = 0; i < 5; i++) {
          expect((await post(a.token, { kind: "bug", text: `r${round}-${i}` })).status).toBe(201);
        }
        await ageRateWindow(hub, `feedback:hour:${a.deviceId}`, 3601);
      }

      const over = await post(a.token, { kind: "bug", text: "21st" });
      expect(over.status).toBe(429);
      expect(await storedRows()).toHaveLength(20);
      expect((await rateRow(hub, `feedback:day:${a.deviceId}`)).count).toBe(20);

      await ageRateWindow(hub, `feedback:day:${a.deviceId}`, 86401);
      await ageRateWindow(hub, `feedback:hour:${a.deviceId}`, 3601);
      expect((await post(a.token, { kind: "bug", text: "next day" })).status).toBe(201);
    });

    it("charges the admin's shared budget, not the per-device buckets", async () => {
      const hub = hubStub();
      for (let i = 0; i < 6; i++) {
        expect((await post(TEST_ADMIN_KEY, { kind: "idea", text: `o${i}` })).status).toBe(201);
      }
      expect(await rateRow(hub, "admin")).toMatchObject({ count: 6 });
      expect(
        await sql(hub, (db) =>
          db.exec("SELECT key FROM rate_limits WHERE key LIKE 'feedback:%'").toArray(),
        ),
      ).toEqual([]);

      // The 21st admin request in the minute is the existing admin 429.
      for (let i = 6; i < 20; i++) {
        expect((await post(TEST_ADMIN_KEY, { kind: "idea", text: `o${i}` })).status).toBe(201);
      }
      expect((await post(TEST_ADMIN_KEY, { kind: "idea", text: "over" })).status).toBe(429);
    });

    it("charges the hour window first, then the day window, with the documented sizes", async () => {
      const hub = hubStub();
      const a = await seedDevice(hub, "a");
      const request = () =>
        new Request("https://hub.example/feedback", {
          method: "POST",
          body: JSON.stringify({ kind: "bug", text: "x" }),
        });
      const auth = { role: "device" as const, deviceId: a.deviceId };

      await inHub(hub, async (db) => {
        const ok = stubContext(db);
        expect((await postFeedback(ok, request(), auth)).status).toBe(201);
        expect(ok.rateCalls).toEqual([
          [`feedback:hour:${a.deviceId}`, 3600, 5],
          [`feedback:day:${a.deviceId}`, 86400, 20],
        ]);

        // A spent limiter is a 429 and stores nothing more.
        const spent = stubContext(db, { allowed: false });
        expect((await postFeedback(spent, request(), auth)).status).toBe(429);
        expect(spent.rateCalls).toEqual([[`feedback:hour:${a.deviceId}`, 3600, 5]]);
        expect(db.exec("SELECT COUNT(*) AS n FROM feedback").one().n).toBe(1);

        // The admin is not charged per device at all.
        const admin = stubContext(db);
        expect((await postFeedback(admin, request(), { role: "admin" })).status).toBe(201);
        expect(admin.rateCalls).toEqual([]);
      });
    });
  });
});

describe("GET /feedback", () => {
  it("is admin only: a device or no bearer gets 401", async () => {
    const a = await seedDevice(hubStub(), "a");
    await send("hello");
    expect((await list(a.token)).status).toBe(401);
    expect((await list(null)).status).toBe(401);
    expect((await list("wrong")).status).toBe(401);
  });

  it("returns the documented item shape, omitting fields that were not sent", async () => {
    const a = await seedDevice(hubStub(), "a");
    const withDiag = (await post(a.token, { kind: "bug", text: "with", diagnostics: DIAG })).body.id;
    const bare = await send("without");

    const reply = await list(TEST_ADMIN_KEY);

    expect(reply.status).toBe(200);
    expect(Object.keys(reply.body)).toEqual(["items"]);
    const byId = Object.fromEntries(reply.body.items.map((i: any) => [i.id, i]));
    expect(byId[withDiag]).toEqual({
      id: withDiag,
      kind: "bug",
      text: "with",
      status: "new",
      nickname: "Test phone a",
      createdAt: expect.any(Number),
      ...DIAG,
    });
    expect(byId[bare]).toEqual({
      id: bare,
      kind: "idea",
      text: "without",
      status: "new",
      nickname: "Owner",
      createdAt: expect.any(Number),
    });
  });

  it("lists newest first", async () => {
    const hub = hubStub();
    const ids = [await send("one"), await send("two"), await send("three")];
    // Distinct, known times, with the middle one the newest.
    await sql(hub, (db) => {
      db.exec("UPDATE feedback SET created_at = 1000 WHERE id = ?", ids[0]);
      db.exec("UPDATE feedback SET created_at = 3000 WHERE id = ?", ids[1]);
      db.exec("UPDATE feedback SET created_at = 2000 WHERE id = ?", ids[2]);
    });

    expect((await list(TEST_ADMIN_KEY)).body.items.map((i: any) => i.id)).toEqual([
      ids[1],
      ids[2],
      ids[0],
    ]);
  });

  it("breaks a tie on the same millisecond by newest insert", async () => {
    const hub = hubStub();
    await inHub(hub, (db) => {
      const ctx = stubContext(db, { now: 5000 });
      const base = { deviceId: "owner", nickname: "Owner", kind: "bug" as const, appVersion: null, android: null, device: null };
      storeFeedback(ctx, { ...base, text: "first" });
      storeFeedback(ctx, { ...base, text: "second" });
    });
    expect((await list(TEST_ADMIN_KEY)).body.items.map((i: any) => i.text)).toEqual(["second", "first"]);
  });

  it("defaults to status=open (new, seen, planned) and filters by each status", async () => {
    const ids: Record<string, string> = {};
    for (const status of ["new", "seen", "planned", "done", "wontfix"]) {
      ids[status] = await send(`is ${status}`);
      if (status !== "new") {
        expect((await put(TEST_ADMIN_KEY, ids[status], { status })).status).toBe(200);
      }
    }

    const texts = async (query: string) =>
      (await list(TEST_ADMIN_KEY, query)).body.items.map((i: any) => i.status).sort();
    expect(await texts("")).toEqual(["new", "planned", "seen"]);
    expect(await texts("?status=open")).toEqual(["new", "planned", "seen"]);
    expect(await texts("?status=all")).toEqual(["done", "new", "planned", "seen", "wontfix"]);
    for (const status of ["new", "seen", "planned", "done", "wontfix"]) {
      expect(await texts(`?status=${status}`)).toEqual([status]);
    }
  });

  it.each(["bogus", "OPEN", "new,seen", "' OR 1=1 --", ""])("rejects status=%j with 400", async (status) => {
    await send("x");
    expect((await list(TEST_ADMIN_KEY, `?status=${encodeURIComponent(status)}`)).status).toBe(400);
  });

  it("defaults limit to 50 and clamps it to 1..100", async () => {
    const hub = hubStub();
    await inHub(hub, (db) => {
      const ctx = stubContext(db);
      for (let i = 0; i < 120; i++) {
        storeFeedback(ctx, {
          deviceId: "owner", nickname: "Owner", kind: "idea", text: `t${i}`,
          appVersion: null, android: null, device: null,
        });
      }
    });

    const count = async (query: string) => (await list(TEST_ADMIN_KEY, query)).body.items.length;
    expect(await count("")).toBe(50);
    expect(await count("?limit=7")).toBe(7);
    expect(await count("?limit=100")).toBe(100);
    expect(await count("?limit=101")).toBe(100);
    expect(await count("?limit=100000")).toBe(100);
    expect(await count("?limit=0")).toBe(1);
    expect(await count("?limit=-5")).toBe(1);
    expect(await count("?limit=abc")).toBe(50);
    expect(await count("?limit=")).toBe(50);
    expect(await count("?limit=2.9")).toBe(2);
  });

  it("is not blocked by the feedback switch, so the owner can still read the inbox", async () => {
    await send("kept");
    await setSwitch(hubStub(), "feedback", false);
    const reply = await list(TEST_ADMIN_KEY);
    expect(reply.status).toBe(200);
    expect(reply.body.items).toHaveLength(1);
  });

  it("shows the nickname the roster had when the item was sent", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    await post(a.token, { kind: "bug", text: "x" });
    await sql(hub, (db) => {
      db.exec("UPDATE roster SET deviceName = 'Renamed' WHERE deviceId = ?", a.deviceId);
    });
    await post(a.token, { kind: "bug", text: "y" });

    const nicks = (await list(TEST_ADMIN_KEY)).body.items.map((i: any) => [i.text, i.nickname]);
    expect(nicks.sort()).toEqual([
      ["x", "Test phone a"],
      ["y", "Renamed"],
    ]);
  });
});

describe("PUT /feedback/{id}", () => {
  it("sets the status, answers {ok:true} and moves the item between filters", async () => {
    const id = await send("fix me");
    const before = (await storedRows())[0].updated_at;

    const reply = await put(TEST_ADMIN_KEY, id, { status: "done" });

    expect(reply).toMatchObject({ status: 200, body: { ok: true } });
    expect((await list(TEST_ADMIN_KEY)).body.items).toEqual([]);
    expect((await list(TEST_ADMIN_KEY, "?status=done")).body.items.map((i: any) => i.id)).toEqual([id]);
    expect((await storedRows())[0].updated_at).toBeGreaterThanOrEqual(before);
    // Any status can be set again.
    expect((await put(TEST_ADMIN_KEY, id, { status: "new" })).status).toBe(200);
    expect((await storedRows())[0].status).toBe("new");
  });

  it("is admin only", async () => {
    const a = await seedDevice(hubStub(), "a");
    const id = await send("x");
    expect((await put(a.token, id, { status: "done" })).status).toBe(401);
    expect((await put(null, id, { status: "done" })).status).toBe(401);
    expect((await storedRows())[0].status).toBe("new");
  });

  it("answers 404 for an unknown id, including odd ones", async () => {
    await send("x");
    for (const id of ["nope", "00000000-0000-4000-8000-000000000000", "' OR 1=1 --", "%E0%A4%A"]) {
      const reply = await call(hubStub(), "PUT", `/feedback/${id.startsWith("%") ? id : encodeURIComponent(id)}`, {
        token: TEST_ADMIN_KEY,
        body: { status: "done" },
      });
      expect(reply.status).toBe(404);
    }
    expect((await storedRows())[0].status).toBe("new");
  });

  it.each([
    ["an unknown status", { status: "fixed" }],
    ["a capitalised status", { status: "Done" }],
    ["open", { status: "open" }],
    ["all", { status: "all" }],
    ["a missing status", {}],
    ["a non-string status", { status: 1 }],
    ["an array body", ["done"]],
    ["invalid JSON", "{nope"],
  ])("rejects %s with 400 and changes nothing", async (_label, body) => {
    const id = await send("x");
    expect((await put(TEST_ADMIN_KEY, id, body)).status).toBe(400);
    expect((await storedRows())[0].status).toBe("new");
  });

  it("is an unknown route (404) for other methods", async () => {
    const id = await send("x");
    expect((await call(hubStub(), "DELETE", `/feedback/${id}`, { token: TEST_ADMIN_KEY })).status).toBe(404);
    expect((await call(hubStub(), "GET", `/feedback/${id}`, { token: TEST_ADMIN_KEY })).status).toBe(404);
    expect((await call(hubStub(), "PUT", "/feedback", { token: TEST_ADMIN_KEY, body: { status: "done" } })).status).toBe(404);
  });
});

describe("storeFeedback", () => {
  it("returns the stored row, status new, with equal created and updated times", async () => {
    const row = await inHub(hubStub(), (db) =>
      storeFeedback(stubContext(db, { now: 1234 }), {
        deviceId: "owner", nickname: "Owner", kind: "idea", text: "t",
        appVersion: "1", android: "2", device: "3",
      }),
    );
    expect(row).toEqual({
      id: expect.any(String),
      device_id: "owner",
      nickname: "Owner",
      kind: "idea",
      text: "t",
      app_version: "1",
      android: "2",
      device: "3",
      status: "new",
      created_at: 1234,
      updated_at: 1234,
      issue_url: null,
    });
    expect((await storedRows())[0]).toEqual(row);
  });
});
