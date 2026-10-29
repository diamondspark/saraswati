// PUT /push/token and DELETE /push/token: each phone's FCM registration token. Device only
// (the admin bearer is 401, as on the other device-only routes), independent of the sangat
// switch. Check order: auth (401), body size (413), body (400), write limit (429).

import { beforeEach, describe, expect, it } from "vitest";
import { hubStub, seedDevice, sql } from "./sangat.helpers";
import { call, rateRow, resetRegistries, revoke, setSwitch, TEST_ADMIN_KEY } from "./registry.helpers";

const TOK_A = "dA1b2C3:APA91bH-fake_token.A";
const TOK_B = "dB1b2C3:APA91bH-fake_token.B";

beforeEach(async () => {
  await resetRegistries(hubStub());
  await sql(hubStub(), (db) => {
    db.exec("DELETE FROM push_tokens");
    db.exec("DELETE FROM rate_limits WHERE key LIKE 'push-%'");
  });
});

const put = (token: string | null, body: unknown) => call(hubStub(), "PUT", "/push/token", { token, body });
const del = (token: string | null) => call(hubStub(), "DELETE", "/push/token", { token });

const rows = () =>
  sql(hubStub(), (db) =>
    db
      .exec<{ deviceId: string; token: string; updatedAt: number }>(
        "SELECT deviceId, token, updatedAt FROM push_tokens ORDER BY deviceId",
      )
      .toArray(),
  );

describe("PUT /push/token", () => {
  it("answers 204 with no body and stores this device's token", async () => {
    const a = await seedDevice(hubStub(), "a");

    const before = Date.now();
    const reply = await put(a.token, { token: TOK_A });

    expect(reply.status).toBe(204);
    expect(reply.body).toBe("");
    expect(await rows()).toEqual([{ deviceId: a.deviceId, token: TOK_A, updatedAt: expect.any(Number) }]);
    expect((await rows())[0].updatedAt).toBeGreaterThanOrEqual(before);
  });

  it("replaces the device's own token, keeping one row, and ignores a deviceId in the body", async () => {
    const a = await seedDevice(hubStub(), "a");
    const b = await seedDevice(hubStub(), "b");

    expect((await put(a.token, { token: TOK_A, deviceId: b.deviceId })).status).toBe(204);
    expect((await put(a.token, { token: TOK_B })).status).toBe(204);
    expect((await put(a.token, { token: TOK_B })).status).toBe(204);

    expect((await rows()).map(({ deviceId, token }) => ({ deviceId, token }))).toEqual([
      { deviceId: a.deviceId, token: TOK_B },
    ]);
  });

  it("moves a token that another device held: the old row is deleted, the new one kept", async () => {
    const a = await seedDevice(hubStub(), "a");
    const b = await seedDevice(hubStub(), "b");
    await put(a.token, { token: TOK_A });
    await put(b.token, { token: TOK_B });

    expect((await put(b.token, { token: TOK_A })).status).toBe(204);

    expect((await rows()).map(({ deviceId, token }) => ({ deviceId, token }))).toEqual([
      { deviceId: b.deviceId, token: TOK_A },
    ]);
  });

  it("is limited to a device: no token, a stranger, a pending phone and the admin are 401", async () => {
    const hub = hubStub();
    await seedDevice(hub, "pending", "pending");

    for (const token of [null, "ab".repeat(32), TEST_ADMIN_KEY]) {
      const reply = await put(token, { token: TOK_A });
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
    expect(await rows()).toEqual([]);
    // Refused at the door: neither the admin's budget nor a device's is spent.
    expect(await rateRow(hub, "admin")).toBeUndefined();
  });

  it.each([
    ["no token", {}],
    ["a numeric token", { token: 12345 }],
    ["a null token", { token: null }],
    ["an empty token", { token: "" }],
    ["a space in the token", { token: "abc def" }],
    ["a slash in the token", { token: "abc/def" }],
    ["a non-ASCII token", { token: "abcé" }],
    ["a newline in the token", { token: "abc\ndef" }],
  ])("%s is 400 and stores nothing", async (_label, body) => {
    const a = await seedDevice(hubStub(), "a");

    const reply = await put(a.token, body);

    expect(reply.status).toBe(400);
    expect(reply.body).toEqual({ error: expect.stringContaining("token must be") });
    expect(await rows()).toEqual([]);
    expect(await rateRow(hubStub(), `push-token:${a.deviceId}`)).toBeUndefined();
  });

  it("a body that is not a JSON object is 400, and a body over 4 KB is 413", async () => {
    const a = await seedDevice(hubStub(), "a");

    for (const body of ["not json", "[]", '"x"']) {
      const reply = await put(a.token, body);
      expect(reply.status).toBe(400);
      expect(reply.body).toEqual({ error: "body must be a JSON object" });
    }
    const big = await put(a.token, { token: "a".repeat(5000) });
    expect(big.status).toBe(413);
    expect(big.body).toEqual({ error: "too large" });
    expect(await rows()).toEqual([]);
  });

  it("accepts a long token that fits the body cap", async () => {
    const a = await seedDevice(hubStub(), "a");
    const long = "a".repeat(4000);

    expect((await put(a.token, { token: long })).status).toBe(204);
    expect((await rows())[0].token).toBe(long);
  });

  it("allows 20 writes an hour per device, then 429, without touching the stored token", async () => {
    const hub = hubStub();
    const a = await seedDevice(hub, "a");
    const b = await seedDevice(hub, "b");

    for (let i = 0; i < 20; i++) {
      expect((await put(a.token, { token: `tok-${i}` })).status).toBe(204);
    }
    const limited = await put(a.token, { token: "tok-over" });

    expect(limited.status).toBe(429);
    expect(limited.body).toEqual({ error: "too many requests" });
    expect((await rows())[0].token).toBe("tok-19");
    expect(await rateRow(hub, `push-token:${a.deviceId}`)).toMatchObject({ count: 20 });
    // Another phone has a budget of its own.
    expect((await put(b.token, { token: "tok-b" })).status).toBe(204);
  });

  it("works with the sangat switch off", async () => {
    const a = await seedDevice(hubStub(), "a");
    await setSwitch(hubStub(), "sangat", false);

    expect((await put(a.token, { token: TOK_A })).status).toBe(204);
    expect((await del(a.token)).status).toBe(204);
  });

  it("is not a route for other methods", async () => {
    const a = await seedDevice(hubStub(), "a");
    expect((await call(hubStub(), "GET", "/push/token", { token: a.token })).status).toBe(404);
    expect((await call(hubStub(), "POST", "/push/token", { token: a.token, body: {} })).status).toBe(404);
  });
});

describe("DELETE /push/token", () => {
  it("removes this device's row only, and answers 204 again when there is none", async () => {
    const a = await seedDevice(hubStub(), "a");
    const b = await seedDevice(hubStub(), "b");
    await put(a.token, { token: TOK_A });
    await put(b.token, { token: TOK_B });

    const first = await del(a.token);
    const second = await del(a.token);

    expect(first.status).toBe(204);
    expect(first.body).toBe("");
    expect(second.status).toBe(204);
    expect((await rows()).map((row) => row.deviceId)).toEqual([b.deviceId]);
  });

  it("is device only: no token, a stranger and the admin are 401", async () => {
    const a = await seedDevice(hubStub(), "a");
    await put(a.token, { token: TOK_A });

    for (const token of [null, "ab".repeat(32), TEST_ADMIN_KEY]) {
      const reply = await del(token);
      expect(reply.status).toBe(401);
      expect(reply.body).toEqual({ error: "unauthorized" });
    }
    expect(await rows()).toHaveLength(1);
  });
});

describe("revoking a device", () => {
  it("deletes its push token and leaves the others", async () => {
    const a = await seedDevice(hubStub(), "a");
    const b = await seedDevice(hubStub(), "b");
    await put(a.token, { token: TOK_A });
    await put(b.token, { token: TOK_B });

    expect((await revoke(hubStub(), a.deviceId)).status).toBe(200);

    expect((await rows()).map((row) => row.deviceId)).toEqual([b.deviceId]);
    // And the revoked phone can no longer register one.
    expect((await put(a.token, { token: TOK_A })).status).toBe(401);
  });
});
