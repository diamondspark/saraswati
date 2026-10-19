import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

type Stub = ReturnType<typeof getFamilyHubStub>;

// rateLimit is private, so it is reached through the real DO instance rather
// than a mock, the same cast roster.test.ts uses for authenticate().
type RateLimiter = {
  rateLimit(key: string, windowSeconds: number, maxCount: number): boolean;
};

function rateLimit(
  stub: Stub,
  key: string,
  windowSeconds: number,
  maxCount: number,
) {
  return runInDurableObject(stub, (instance) =>
    (instance as unknown as RateLimiter).rateLimit(
      key,
      windowSeconds,
      maxCount,
    ),
  );
}

interface RateLimitRow extends Record<string, SqlStorageValue> {
  windowStart: number;
  count: number;
}

function readRow(stub: Stub, key: string) {
  return runInDurableObject(stub, (_instance, state) =>
    state.storage.sql
      .exec<RateLimitRow>(
        "SELECT windowStart, count FROM rate_limits WHERE key = ?",
        key,
      )
      .toArray(),
  );
}

async function rowCount(stub: Stub): Promise<number> {
  return runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{ n: number }>("SELECT COUNT(*) AS n FROM rate_limits")
        .one().n,
  );
}

/** Pretends `elapsedMs` have passed since the key's window opened. */
function ageWindow(stub: Stub, key: string, elapsedMs: number) {
  return runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "UPDATE rate_limits SET windowStart = ? WHERE key = ?",
      Date.now() - elapsedMs,
      key,
    );
  });
}

describe("FamilyHub.rateLimit", () => {
  it("allows the first call in a fresh window and records count=1", async () => {
    const stub = getFamilyHubStub();
    const key = "test:fresh";
    const before = Date.now();

    expect(await rateLimit(stub, key, 60, 3)).toBe(true);

    const rows = await readRow(stub, key);
    expect(rows).toHaveLength(1);
    expect(rows[0].count).toBe(1);
    expect(rows[0].windowStart).toBeGreaterThanOrEqual(before);
    expect(rows[0].windowStart).toBeLessThanOrEqual(Date.now());
  });

  it("allows calls under the max in the same window and increments the count", async () => {
    const stub = getFamilyHubStub();
    const key = "test:under-max";

    expect(await rateLimit(stub, key, 60, 3)).toBe(true);
    const [{ windowStart }] = await readRow(stub, key);
    expect(await rateLimit(stub, key, 60, 3)).toBe(true);
    expect(await rateLimit(stub, key, 60, 3)).toBe(true);

    const rows = await readRow(stub, key);
    expect(rows[0].count).toBe(3);
    // Staying in the same window must not slide it forward.
    expect(rows[0].windowStart).toBe(windowStart);
  });

  it("refuses once count == max, without incrementing further or moving the window", async () => {
    const stub = getFamilyHubStub();
    const key = "test:at-max";
    for (let i = 0; i < 3; i++) {
      expect(await rateLimit(stub, key, 60, 3)).toBe(true);
    }
    const [atMax] = await readRow(stub, key);
    expect(atMax.count).toBe(3);

    expect(await rateLimit(stub, key, 60, 3)).toBe(false);
    expect(await rateLimit(stub, key, 60, 3)).toBe(false);

    const [afterRefusals] = await readRow(stub, key);
    expect(afterRefusals).toEqual(atMax);
  });

  it("with max=1, allows exactly one call per window", async () => {
    const stub = getFamilyHubStub();
    const key = "test:max-one";

    expect(await rateLimit(stub, key, 300, 1)).toBe(true);
    expect(await rateLimit(stub, key, 300, 1)).toBe(false);
  });

  it("keeps refusing while the window has not yet fully elapsed", async () => {
    const stub = getFamilyHubStub();
    const key = "test:inside-window";
    expect(await rateLimit(stub, key, 60, 1)).toBe(true);
    await ageWindow(stub, key, 59_000);

    expect(await rateLimit(stub, key, 60, 1)).toBe(false);
  });

  it("resets the counter and returns true once windowSeconds have elapsed", async () => {
    const stub = getFamilyHubStub();
    const key = "test:new-window";
    for (let i = 0; i < 3; i++) {
      expect(await rateLimit(stub, key, 60, 3)).toBe(true);
    }
    expect(await rateLimit(stub, key, 60, 3)).toBe(false);
    await ageWindow(stub, key, 60_000);
    const aged = Date.now() - 60_000;

    expect(await rateLimit(stub, key, 60, 3)).toBe(true);

    const rows = await readRow(stub, key);
    expect(rows).toHaveLength(1);
    expect(rows[0].count).toBe(1);
    expect(rows[0].windowStart).toBeGreaterThan(aged);
  });

  it("tracks each key independently", async () => {
    const stub = getFamilyHubStub();

    expect(await rateLimit(stub, "test:key-a", 60, 1)).toBe(true);
    expect(await rateLimit(stub, "test:key-a", 60, 1)).toBe(false);

    expect(await rateLimit(stub, "test:key-b", 60, 1)).toBe(true);
  });
});

describe("POST /pairing/claim rate limit", () => {
  const CLAIM_MAX_ATTEMPTS = 10;

  async function createToken(stub: Stub) {
    const response = await stub.fetch("https://example.com/pairing/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });
    return ((await response.json()) as { token: string }).token;
  }

  function claim(stub: Stub, token: string, deviceName = "Some Phone") {
    return stub.fetch("https://example.com/pairing/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token, deviceName }),
    });
  }

  function countRoster(stub: Stub): Promise<number> {
    return runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql
          .exec<{ n: number }>("SELECT COUNT(*) AS n FROM roster")
          .one().n,
    );
  }

  function seedAttempts(stub: Stub, token: string, count: number) {
    return runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "INSERT OR REPLACE INTO rate_limits (key, windowStart, count) VALUES (?, ?, ?)",
        `claim:${token}`,
        Date.now(),
        count,
      );
    });
  }

  it("answers the 11th attempt on a token with 429, after the first succeeds and the next nine are 410", async () => {
    const stub = getFamilyHubStub();
    const token = await createToken(stub);

    expect((await claim(stub, token)).status).toBe(200);
    for (let attempt = 2; attempt <= CLAIM_MAX_ATTEMPTS; attempt++) {
      expect((await claim(stub, token)).status, `attempt ${attempt}`).toBe(410);
    }
    const rosterBefore = await countRoster(stub);

    expect((await claim(stub, token)).status).toBe(429);
    expect((await claim(stub, token)).status).toBe(429);

    expect(await countRoster(stub)).toBe(rosterBefore);
    const rows = await readRow(stub, `claim:${token}`);
    expect(rows[0].count).toBe(CLAIM_MAX_ATTEMPTS);
  });

  it("burns a still-unused token once its attempts are spent: 429, no roster row, token left unclaimed", async () => {
    const stub = getFamilyHubStub();
    const token = await createToken(stub);
    await seedAttempts(stub, token, CLAIM_MAX_ATTEMPTS);
    const rosterBefore = await countRoster(stub);

    const response = await claim(stub, token);

    expect(response.status).toBe(429);
    expect(await countRoster(stub)).toBe(rosterBefore);
    const usedAt = await runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql
          .exec<{ usedAt: number | null }>(
            "SELECT usedAt FROM pairing_tokens WHERE token = ?",
            token,
          )
          .one().usedAt,
    );
    expect(usedAt).toBeNull();
  });

  it("counts attempts per token, so spending one token's attempts does not block another", async () => {
    const stub = getFamilyHubStub();
    const spent = await createToken(stub);
    const fresh = await createToken(stub);
    await seedAttempts(stub, spent, CLAIM_MAX_ATTEMPTS);

    expect((await claim(stub, spent)).status).toBe(429);
    expect((await claim(stub, fresh)).status).toBe(200);
  });

  it("lets a token through again once its 15-minute window has elapsed", async () => {
    const stub = getFamilyHubStub();
    const token = await createToken(stub);
    await seedAttempts(stub, token, CLAIM_MAX_ATTEMPTS);
    expect((await claim(stub, token)).status).toBe(429);

    await ageWindow(stub, `claim:${token}`, 900_000);

    expect((await claim(stub, token)).status).toBe(200);
  });

  it("answers an unknown token 404 every time and never writes a rate-limit row for it", async () => {
    const stub = getFamilyHubStub();
    const rowsBefore = await rowCount(stub);

    for (let i = 0; i < CLAIM_MAX_ATTEMPTS + 2; i++) {
      const response = await claim(stub, `not-a-real-token-${i}`);
      expect(response.status).toBe(404);
    }

    expect(await rowCount(stub)).toBe(rowsBefore);
  });
});
