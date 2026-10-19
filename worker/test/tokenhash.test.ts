import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  type Stub,
  TEST_ADMIN_KEY,
  adminHeaders,
  approveDevice,
  claimDeviceWithToken,
  collectSessionToken,
  dumpAllTables,
  getFamilyHubStub,
  mintPairingToken,
  sha256Hex,
} from "./helpers";

const HEX_64 = /^[0-9a-f]{64}$/;

function deviceGet(stub: Stub, path: string, token: string) {
  return stub.fetch(`https://example.com${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

function rosterRow(stub: Stub, deviceId: string) {
  return runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{
          sessionTokenHash: string | null;
          pendingSessionToken: string | null;
          claimSecretHash: string | null;
        }>(
          "SELECT sessionTokenHash, pendingSessionToken, claimSecretHash FROM roster WHERE deviceId = ?",
          deviceId,
        )
        .one(),
  );
}

async function dumpText(stub: Stub): Promise<string> {
  return JSON.stringify(await dumpAllTables(stub));
}

describe("the pairing token", () => {
  it("is stored as its SHA-256, with the 15-minute expiry intact", async () => {
    const stub = getFamilyHubStub();
    const before = Date.now();
    const token = await mintPairingToken(stub);

    const stored = await runInDurableObject(stub, (_instance, state) =>
      state.storage.sql
        .exec<{ tokenHash: string; expiresAt: number; usedAt: number | null }>(
          "SELECT tokenHash, expiresAt, usedAt FROM pairing_tokens",
        )
        .toArray(),
    );

    const hash = await sha256Hex(token);
    const mine = stored.find((r) => r.tokenHash === hash);
    expect(mine?.usedAt).toBeNull();
    expect(mine!.expiresAt - before).toBeGreaterThan(14 * 60_000);
    expect(mine!.expiresAt - before).toBeLessThan(16 * 60_000);
    expect(stored.map((r) => r.tokenHash)).not.toContain(token);
  });
});

describe("plaintext credentials at rest", () => {
  it("holds the pairing token and claimSecret only as hashes, from the first byte", async () => {
    const stub = getFamilyHubStub();
    const {
      token,
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);

    const dump = await dumpText(stub);

    expect(dump).not.toContain(token);
    expect(dump).not.toContain(claimSecret);
    const tokens = (await dumpAllTables(stub)).pairing_tokens as Array<{
      tokenHash: string;
    }>;
    expect(tokens.map((row) => row.tokenHash)).toContain(await sha256Hex(token));
    expect((await rosterRow(stub, deviceId)).claimSecretHash).toBe(
      await sha256Hex(claimSecret),
    );
  });

  it("holds the sessionToken in pendingSessionToken only, from approval until the phone first uses it", async () => {
    const stub = getFamilyHubStub();
    const {
      token,
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);

    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);

    expect(sessionToken).toMatch(HEX_64);
    const row = await rosterRow(stub, deviceId);
    expect(row.pendingSessionToken).toBe(sessionToken);
    expect(row.sessionTokenHash).toBe(await sha256Hex(sessionToken));
    const tables = await dumpAllTables(stub);
    // Exactly one column anywhere may hold the plaintext session token.
    const holders: string[] = [];
    for (const [table, rows] of Object.entries(tables)) {
      for (const stored of rows as Array<Record<string, unknown>>) {
        for (const [column, value] of Object.entries(stored)) {
          if (typeof value === "string" && value.includes(sessionToken)) {
            holders.push(`${table}.${column}`);
          }
        }
      }
    }
    expect(holders).toEqual(["roster.pendingSessionToken"]);
    const dump = await dumpText(stub);
    expect(dump).not.toContain(token);
    expect(dump).not.toContain(claimSecret);
  });

  it("leaves no plaintext credential anywhere once the device has collected and used its token", async () => {
    const stub = getFamilyHubStub();
    const {
      token,
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);

    // The phone's first authenticated request, and a few more of every kind.
    expect((await deviceGet(stub, "/stations", sessionToken)).status).toBe(200);
    expect((await deviceGet(stub, "/me", sessionToken)).status).toBe(200);
    const health = await stub.fetch("https://example.com/health", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        Authorization: `Bearer ${sessionToken}`,
      },
      body: JSON.stringify({
        deviceId,
        versionCode: 1,
        versionName: "1.0",
        timezone: "Asia/Kolkata",
      }),
    });
    expect(health.status).toBe(204);

    const dump = await dumpText(stub);
    expect(dump).not.toContain(sessionToken);
    expect(dump).not.toContain(token);
    expect(dump).not.toContain(claimSecret);
    expect(await rosterRow(stub, deviceId)).toEqual({
      sessionTokenHash: await sha256Hex(sessionToken),
      pendingSessionToken: null,
      claimSecretHash: null,
    });
  });

  it("leaves no plaintext credential after a revoke either, and the old token stops working", async () => {
    const stub = getFamilyHubStub();
    const {
      token,
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);

    const revoke = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      { method: "POST", headers: adminHeaders },
    );
    expect(revoke.status).toBe(200);

    const dump = await dumpText(stub);
    expect(dump).not.toContain(sessionToken);
    expect(dump).not.toContain(token);
    expect(dump).not.toContain(claimSecret);
    expect(await rosterRow(stub, deviceId)).toEqual({
      sessionTokenHash: null,
      pendingSessionToken: null,
      claimSecretHash: null,
    });
    expect((await deviceGet(stub, "/stations", sessionToken)).status).toBe(401);
  });

  it("never lists a credential through GET /roster, not even a pending one", async () => {
    const stub = getFamilyHubStub();
    const {
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);

    const text = await (
      await stub.fetch("https://example.com/roster", { headers: adminHeaders })
    ).text();

    expect(text).not.toContain(sessionToken);
    expect(text).not.toContain(await sha256Hex(sessionToken));
    expect(text).not.toContain(claimSecret);
    expect(text).not.toContain(await sha256Hex(claimSecret));
  });
});

describe("authenticate() against hashed storage", () => {
  it("accepts the real token and rejects the stored hash presented as a bearer", async () => {
    const stub = getFamilyHubStub();
    const {
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);
    const { sessionTokenHash } = await rosterRow(stub, deviceId);

    expect((await deviceGet(stub, "/stations", sessionToken)).status).toBe(200);
    // A leaked database must not be a credential.
    expect(sessionTokenHash).toMatch(HEX_64);
    expect((await deviceGet(stub, "/stations", sessionTokenHash!)).status).toBe(
      401,
    );
  });

  it("rejects the pending token's hash, the claimSecret and the pairing token as bearers", async () => {
    const stub = getFamilyHubStub();
    const {
      token,
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    await collectSessionToken(stub, deviceId, claimSecret);

    for (const bearer of [
      token,
      claimSecret,
      await sha256Hex(token),
      await sha256Hex(claimSecret),
      deviceId,
    ]) {
      expect((await deviceGet(stub, "/stations", bearer)).status).toBe(401);
    }
  });

  it("does not authenticate a pending device, even one that somehow holds a hash", async () => {
    const stub = getFamilyHubStub();
    const {
      claimed: { deviceId },
    } = await claimDeviceWithToken(stub);
    const guess = "ef".repeat(32);
    await runInDurableObject(stub, async (_instance, state) => {
      state.storage.sql.exec(
        "UPDATE roster SET sessionTokenHash = ? WHERE deviceId = ?",
        await sha256Hex(guess),
        deviceId,
      );
    });

    expect((await deviceGet(stub, "/stations", guess)).status).toBe(401);
  });

  it("keeps authenticating after the pending token and claimSecret are cleared", async () => {
    const stub = getFamilyHubStub();
    const {
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    const sessionToken = await collectSessionToken(stub, deviceId, claimSecret);

    for (let i = 0; i < 3; i += 1) {
      expect((await deviceGet(stub, "/stations", sessionToken)).status).toBe(
        200,
      );
    }
  });

  it("still accepts the admin key and never touches a roster row for it", async () => {
    const stub = getFamilyHubStub();
    const {
      claimed: { deviceId, claimSecret },
    } = await claimDeviceWithToken(stub);
    await approveDevice(stub, deviceId);
    await collectSessionToken(stub, deviceId, claimSecret);
    const before = await rosterRow(stub, deviceId);

    expect((await deviceGet(stub, "/stations", TEST_ADMIN_KEY)).status).toBe(
      200,
    );

    expect(await rosterRow(stub, deviceId)).toEqual(before);
    expect(before.pendingSessionToken).not.toBeNull();
    // The env binding is the same literal the helpers use.
    expect(env.ADMIN_KEY).toBe(TEST_ADMIN_KEY);
  });
});
