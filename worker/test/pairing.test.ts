import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { TEST_ADMIN_KEY, getFamilyHubStub, sha256Hex } from "./helpers";

describe("POST /pairing/create", () => {
  it("returns 401 without the admin bearer token", async () => {
    const stub = getFamilyHubStub();

    const response = await stub.fetch("https://example.com/pairing/create", {
      method: "POST",
    });

    expect(response.status).toBe(401);
  });

  it("returns 401 for a well-formed bearer token that is not the admin key", async () => {
    const stub = getFamilyHubStub();

    const response = await stub.fetch("https://example.com/pairing/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${"cd".repeat(32)}` },
    });

    expect(response.status).toBe(401);
  });

  it("returns a 64-char hex token and an expiresAt ~15 minutes out, when admin-authed", async () => {
    const stub = getFamilyHubStub();
    const before = Date.now();

    const response = await stub.fetch("https://example.com/pairing/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as { token: string; expiresAt: string };
    expect(body.token).toMatch(/^[0-9a-f]{64}$/);

    const minutesOut = (Date.parse(body.expiresAt) - before) / 60_000;
    expect(minutesOut).toBeGreaterThan(14);
    expect(minutesOut).toBeLessThan(16);
  });
});

describe("POST /pairing/claim", () => {
  async function createToken(stub: ReturnType<typeof getFamilyHubStub>) {
    const response = await stub.fetch("https://example.com/pairing/create", {
      method: "POST",
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });
    const body = (await response.json()) as { token: string };
    return body.token;
  }

  it("creates a pending roster row and returns a deviceId", async () => {
    const stub = getFamilyHubStub();
    const token = await createToken(stub);

    const response = await stub.fetch("https://example.com/pairing/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token, deviceName: "Mummy's Pixel" }),
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as { deviceId: string; status: string };
    expect(body.status).toBe("pending");
    expect(body.deviceId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });

  it("returns 410 when the same token is claimed a second time", async () => {
    const stub = getFamilyHubStub();
    const token = await createToken(stub);

    const claim = () =>
      stub.fetch("https://example.com/pairing/claim", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ token, deviceName: "Papa's Phone" }),
      });

    const first = await claim();
    expect(first.status).toBe(200);

    const second = await claim();
    expect(second.status).toBe(410);
  });

  it("returns 410 when claiming an expired token", async () => {
    const stub = getFamilyHubStub();
    const expiredToken = "ff".repeat(32);

    // No endpoint can create an already-expired token, so seed one directly
    // in the real DO's SQLite storage via the sanctioned test helper.
    const expiredTokenHash = await sha256Hex(expiredToken);
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "INSERT INTO pairing_tokens (tokenHash, expiresAt, usedAt) VALUES (?, ?, NULL)",
        expiredTokenHash,
        Date.now() - 1_000,
      );
    });

    const response = await stub.fetch("https://example.com/pairing/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: expiredToken, deviceName: "Late Phone" }),
    });

    expect(response.status).toBe(410);
  });

  it("returns 404 for an unknown token", async () => {
    const stub = getFamilyHubStub();

    const response = await stub.fetch("https://example.com/pairing/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token: "not-a-real-token", deviceName: "Nobody" }),
    });

    expect(response.status).toBe(404);
  });
});
