import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const HEX_64 = /^[0-9a-f]{64}$/;
const STATUS_WINDOW_MS = 60_000;
const STATUS_MAX_CALLS = 12;

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

type Stub = ReturnType<typeof getFamilyHubStub>;

interface Claimed {
  deviceId: string;
  claimSecret: string;
}

const adminHeaders = { Authorization: `Bearer ${TEST_ADMIN_KEY}` };

async function claimDevice(stub: Stub, deviceName = "Test Phone") {
  const created = await stub.fetch("https://example.com/pairing/create", {
    method: "POST",
    headers: adminHeaders,
  });
  const { token } = (await created.json()) as { token: string };

  const response = await stub.fetch("https://example.com/pairing/claim", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token, deviceName }),
  });
  expect(response.status).toBe(200);
  return (await response.json()) as Claimed & { status: string };
}

async function approve(stub: Stub, deviceId: string): Promise<string> {
  const response = await stub.fetch(
    `https://example.com/roster/${deviceId}/approve`,
    { method: "POST", headers: adminHeaders },
  );
  expect(response.status).toBe(200);
  return ((await response.json()) as { sessionToken: string }).sessionToken;
}

async function revoke(stub: Stub, deviceId: string) {
  const response = await stub.fetch(
    `https://example.com/roster/${deviceId}/revoke`,
    { method: "POST", headers: adminHeaders },
  );
  expect(response.status).toBe(200);
}

function postStatus(stub: Stub, body: unknown) {
  return stub.fetch("https://example.com/pairing/status", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

function status(stub: Stub, { deviceId, claimSecret }: Claimed) {
  return postStatus(stub, { deviceId, claimSecret });
}

/** Any device-role authenticated request; GET /stations is the cheapest. */
function deviceGetStations(stub: Stub, sessionToken: string) {
  return stub.fetch("https://example.com/stations", {
    headers: { Authorization: `Bearer ${sessionToken}` },
  });
}

function storedClaimSecret(stub: Stub, deviceId: string) {
  return runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{ claimSecret: string | null }>(
          "SELECT claimSecret FROM roster WHERE deviceId = ?",
          deviceId,
        )
        .one().claimSecret,
  );
}

function rateLimitRows(stub: Stub, key?: string) {
  return runInDurableObject(stub, (_instance, state) =>
    key === undefined
      ? state.storage.sql
          .exec<{ key: string; count: number }>(
            "SELECT key, count FROM rate_limits",
          )
          .toArray()
      : state.storage.sql
          .exec<{ key: string; count: number }>(
            "SELECT key, count FROM rate_limits WHERE key = ?",
            key,
          )
          .toArray(),
  );
}

function ageWindow(stub: Stub, key: string, elapsedMs: number) {
  return runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "UPDATE rate_limits SET windowStart = ? WHERE key = ?",
      Date.now() - elapsedMs,
      key,
    );
  });
}

function unknownDevice(n: number): string {
  return `ffffffff-0000-4000-8000-${String(n).padStart(12, "0")}`;
}

describe("POST /pairing/claim claimSecret", () => {
  it("returns a 64-hex claimSecret alongside deviceId and status", async () => {
    const stub = getFamilyHubStub();

    const body = await claimDevice(stub);

    expect(Object.keys(body).sort()).toEqual([
      "claimSecret",
      "deviceId",
      "status",
    ]);
    expect(body.status).toBe("pending");
    expect(body.claimSecret).toMatch(HEX_64);
  });

  it("stores the claimSecret on the roster row and never reuses one", async () => {
    const stub = getFamilyHubStub();

    const first = await claimDevice(stub);
    const second = await claimDevice(stub);

    expect(await storedClaimSecret(stub, first.deviceId)).toBe(
      first.claimSecret,
    );
    expect(first.claimSecret).not.toBe(second.claimSecret);
  });

  it("does not leak the claimSecret through GET /roster", async () => {
    const stub = getFamilyHubStub();
    const claimed = await claimDevice(stub);

    const response = await stub.fetch("https://example.com/roster", {
      headers: adminHeaders,
    });

    expect(await response.text()).not.toContain(claimed.claimSecret);
  });
});

describe("POST /pairing/status", () => {
  it("reports pending for a claimed device that is not yet approved", async () => {
    const stub = getFamilyHubStub();
    const claimed = await claimDevice(stub);

    const response = await status(stub, claimed);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "pending" });
  });

  it("returns the very sessionToken approve returned, once approved", async () => {
    const stub = getFamilyHubStub();
    const claimed = await claimDevice(stub);
    const sessionToken = await approve(stub, claimed.deviceId);

    const response = await status(stub, claimed);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      status: "approved",
      sessionToken,
    });
    expect(sessionToken).toMatch(HEX_64);
  });

  it("can be polled repeatedly until the token is first used", async () => {
    const stub = getFamilyHubStub();
    const claimed = await claimDevice(stub);
    const sessionToken = await approve(stub, claimed.deviceId);

    for (let i = 0; i < 3; i += 1) {
      const response = await status(stub, claimed);
      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({
        status: "approved",
        sessionToken,
      });
    }
  });

  it("needs no bearer: a bogus Authorization header changes nothing", async () => {
    const stub = getFamilyHubStub();
    const claimed = await claimDevice(stub);

    const response = await stub.fetch("https://example.com/pairing/status", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${"cd".repeat(32)}`,
      },
      body: JSON.stringify(claimed),
    });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ status: "pending" });
  });

  describe("indistinguishable 404", () => {
    async function notFoundBody(response: Response) {
      expect(response.status).toBe(404);
      return response.text();
    }

    it("is the same body for an unknown id, a wrong secret, a revoked device and a cleared secret", async () => {
      const stub = getFamilyHubStub();

      const unknownId = await notFoundBody(
        await status(stub, {
          deviceId: unknownDevice(1),
          claimSecret: "ab".repeat(32),
        }),
      );

      const live = await claimDevice(stub);
      const wrongSecret = await notFoundBody(
        await status(stub, { ...live, claimSecret: "ab".repeat(32) }),
      );
      const wrongLength = await notFoundBody(
        await status(stub, { ...live, claimSecret: "ab" }),
      );

      const revoked = await claimDevice(stub);
      const revokedToken = await approve(stub, revoked.deviceId);
      await revoke(stub, revoked.deviceId);
      const revokedBody = await notFoundBody(await status(stub, revoked));

      const used = await claimDevice(stub);
      const usedToken = await approve(stub, used.deviceId);
      expect((await deviceGetStations(stub, usedToken)).status).toBe(200);
      const cleared = await notFoundBody(await status(stub, used));

      expect(revokedToken).toMatch(HEX_64);
      for (const body of [wrongSecret, wrongLength, revokedBody, cleared]) {
        expect(body).toBe(unknownId);
      }
      expect(unknownId).not.toContain(live.deviceId);
    });

    it("returns 404 for a secret that is another device's secret", async () => {
      const stub = getFamilyHubStub();
      const first = await claimDevice(stub);
      const second = await claimDevice(stub);

      const response = await postStatus(stub, {
        deviceId: first.deviceId,
        claimSecret: second.claimSecret,
      });

      expect(response.status).toBe(404);
    });

    it("returns 404 for a pending device whose claimSecret is NULL", async () => {
      const stub = getFamilyHubStub();
      const deviceId = "12121212-1212-4212-8212-121212121212";
      await runInDurableObject(stub, (_instance, state) => {
        state.storage.sql.exec(
          "INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt) VALUES (?, 'Seeded', 'pending', NULL, NULL)",
          deviceId,
        );
      });

      for (const claimSecret of ["ab".repeat(32), "null", "NULL"]) {
        const response = await postStatus(stub, { deviceId, claimSecret });
        expect(response.status, claimSecret).toBe(404);
      }
    });
  });

  describe("malformed bodies", () => {
    const valid = { deviceId: unknownDevice(2), claimSecret: "ab".repeat(32) };
    const cases: Array<[string, unknown]> = [
      ["not JSON", "{nope"],
      ["empty body", ""],
      ["a JSON array", [valid]],
      ["a JSON string", '"hello"'],
      ["null", "null"],
      ["no deviceId", { claimSecret: valid.claimSecret }],
      ["no claimSecret", { deviceId: valid.deviceId }],
      ["empty deviceId", { ...valid, deviceId: "" }],
      ["empty claimSecret", { ...valid, claimSecret: "" }],
      ["numeric deviceId", { ...valid, deviceId: 7 }],
      ["numeric claimSecret", { ...valid, claimSecret: 7 }],
      ["null claimSecret", { ...valid, claimSecret: null }],
      ["object claimSecret", { ...valid, claimSecret: { a: 1 } }],
    ];

    for (const [label, body] of cases) {
      it(`returns 400 for ${label}, without touching rate_limits`, async () => {
        const stub = getFamilyHubStub();
        const before = await rateLimitRows(stub);

        const response = await postStatus(stub, body);

        expect(response.status).toBe(400);
        expect(await rateLimitRows(stub)).toEqual(before);
      });
    }

    it("returns 400 (not a token) for a real deviceId with no claimSecret", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      await approve(stub, claimed.deviceId);

      const response = await postStatus(stub, { deviceId: claimed.deviceId });

      expect(response.status).toBe(400);
      expect(await response.text()).not.toMatch(HEX_64);
    });
  });

  describe("deviceId alone grants nothing", () => {
    it("is not accepted as a bearer token, before or after approval", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);

      const pending = await deviceGetStations(stub, claimed.deviceId);
      expect(pending.status).toBe(401);

      await approve(stub, claimed.deviceId);
      const approved = await deviceGetStations(stub, claimed.deviceId);
      expect(approved.status).toBe(401);
    });

    it("is not accepted as a claimSecret, and the claimSecret is not a bearer", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      await approve(stub, claimed.deviceId);

      const asSecret = await postStatus(stub, {
        deviceId: claimed.deviceId,
        claimSecret: claimed.deviceId,
      });
      expect(asSecret.status).toBe(404);

      const secretAsBearer = await deviceGetStations(stub, claimed.claimSecret);
      expect(secretAsBearer.status).toBe(401);
    });
  });

  describe("clearing the claimSecret", () => {
    it("clears it after the sessionToken's first device-role use, and status becomes 404", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      const sessionToken = await approve(stub, claimed.deviceId);
      expect((await status(stub, claimed)).status).toBe(200);
      expect(await storedClaimSecret(stub, claimed.deviceId)).toBe(
        claimed.claimSecret,
      );

      expect((await deviceGetStations(stub, sessionToken)).status).toBe(200);

      expect(await storedClaimSecret(stub, claimed.deviceId)).toBeNull();
      expect((await status(stub, claimed)).status).toBe(404);
      // The session itself keeps working after the secret is gone.
      expect((await deviceGetStations(stub, sessionToken)).status).toBe(200);
    });

    it("clears only the authenticating device's own secret", async () => {
      const stub = getFamilyHubStub();
      const first = await claimDevice(stub);
      const second = await claimDevice(stub);
      const firstToken = await approve(stub, first.deviceId);
      const secondToken = await approve(stub, second.deviceId);

      expect((await deviceGetStations(stub, firstToken)).status).toBe(200);

      expect(await storedClaimSecret(stub, first.deviceId)).toBeNull();
      expect(await storedClaimSecret(stub, second.deviceId)).toBe(
        second.claimSecret,
      );
      const response = await status(stub, second);
      expect(await response.json()).toEqual({
        status: "approved",
        sessionToken: secondToken,
      });
    });

    it("is not cleared by a failed authentication or by the admin key", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      await approve(stub, claimed.deviceId);

      expect((await deviceGetStations(stub, "cd".repeat(32))).status).toBe(401);
      expect((await deviceGetStations(stub, claimed.deviceId)).status).toBe(
        401,
      );
      expect((await deviceGetStations(stub, TEST_ADMIN_KEY)).status).toBe(200);

      expect(await storedClaimSecret(stub, claimed.deviceId)).toBe(
        claimed.claimSecret,
      );
      expect((await status(stub, claimed)).status).toBe(200);
    });

    it("clears it on revoke of an approved device", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      await approve(stub, claimed.deviceId);

      await revoke(stub, claimed.deviceId);

      expect(await storedClaimSecret(stub, claimed.deviceId)).toBeNull();
      expect((await status(stub, claimed)).status).toBe(404);
    });

    it("clears it on revoke of a device that was never approved", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);

      await revoke(stub, claimed.deviceId);

      expect(await storedClaimSecret(stub, claimed.deviceId)).toBeNull();
      expect((await status(stub, claimed)).status).toBe(404);
    });
  });

  describe("rate limit", () => {
    it("allows 12 calls per minute per device and answers the 13th with 429", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);

      for (let call = 1; call <= STATUS_MAX_CALLS; call += 1) {
        const response = await status(stub, claimed);
        expect(response.status, `call ${call}`).toBe(200);
      }

      const limited = await status(stub, claimed);
      expect(limited.status).toBe(429);
      expect((await status(stub, claimed)).status).toBe(429);
      expect(await rateLimitRows(stub, `status:${claimed.deviceId}`)).toEqual([
        { key: `status:${claimed.deviceId}`, count: STATUS_MAX_CALLS },
      ]);
    });

    it("counts wrong-secret calls too, so guessing is throttled", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);

      for (let call = 1; call <= STATUS_MAX_CALLS; call += 1) {
        const response = await postStatus(stub, {
          deviceId: claimed.deviceId,
          claimSecret: "ab".repeat(32),
        });
        expect(response.status, `call ${call}`).toBe(404);
      }

      expect((await postStatus(stub, {
        deviceId: claimed.deviceId,
        claimSecret: "ab".repeat(32),
      })).status).toBe(429);
      // Even the right secret is held back until the window passes.
      expect((await status(stub, claimed)).status).toBe(429);
    });

    it("keeps a separate budget per device", async () => {
      const stub = getFamilyHubStub();
      const spent = await claimDevice(stub);
      const other = await claimDevice(stub);
      for (let call = 0; call < STATUS_MAX_CALLS; call += 1) {
        await status(stub, spent);
      }
      expect((await status(stub, spent)).status).toBe(429);

      expect((await status(stub, other)).status).toBe(200);
    });

    it("opens a fresh window once 60 seconds have passed", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      const key = `status:${claimed.deviceId}`;
      for (let call = 0; call < STATUS_MAX_CALLS; call += 1) {
        await status(stub, claimed);
      }
      expect((await status(stub, claimed)).status).toBe(429);

      await ageWindow(stub, key, STATUS_WINDOW_MS - 5_000);
      expect((await status(stub, claimed)).status).toBe(429);

      await ageWindow(stub, key, STATUS_WINDOW_MS);
      expect((await status(stub, claimed)).status).toBe(200);
    });

    it("rate-limits a revoked or cleared device without ever leaking its state", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      await revoke(stub, claimed.deviceId);

      for (let call = 1; call <= STATUS_MAX_CALLS; call += 1) {
        expect((await status(stub, claimed)).status, `call ${call}`).toBe(404);
      }
      expect((await status(stub, claimed)).status).toBe(429);
    });

    it("creates no rate_limits rows for unknown deviceIds", async () => {
      const stub = getFamilyHubStub();
      const before = await rateLimitRows(stub);

      for (let n = 0; n < 20; n += 1) {
        const response = await postStatus(stub, {
          deviceId: unknownDevice(100 + n),
          claimSecret: "ab".repeat(32),
        });
        expect(response.status).toBe(404);
      }

      expect(await rateLimitRows(stub)).toEqual(before);
    });

    it("does not spend the admin budget", async () => {
      const stub = getFamilyHubStub();
      const claimed = await claimDevice(stub);
      const adminBefore = await rateLimitRows(stub, "admin");

      for (let call = 0; call < 5; call += 1) {
        await status(stub, claimed);
      }

      expect(await rateLimitRows(stub, "admin")).toEqual(adminBefore);
    });
  });
});
