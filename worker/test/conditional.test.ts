import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  type Stub,
  getFamilyHubStub,
  seedPendingDevice,
  seedRoster,
  sha256Hex,
} from "./helpers";

// The hub awaits a digest between "is this still allowed?" and the write that
// acts on it. On today's runtime nothing else can run in that gap, but nothing
// in the code should depend on that. These tests drive the write step on its
// own, against a row that has changed since the check, which is exactly what a
// runtime that yields at the await would hand it.

type Hub = {
  claimPairingToken(
    tokenHash: string,
    now: number,
    device: {
      deviceId: string;
      deviceName: string | null;
      claimSecretHash: string;
      role: string;
    },
  ): boolean;
  commitApproval(
    deviceId: string,
    sessionTokenHash: string,
    sessionToken: string,
    now: number,
  ): boolean;
};

function onHub<T>(stub: Stub, run: (hub: Hub, sql: SqlStorage) => T) {
  return runInDurableObject(stub, (instance, state) =>
    run(instance as unknown as Hub, state.storage.sql),
  );
}

let counter = 0;

async function seedToken(
  stub: Stub,
  fields: { expiresAt: number; usedAt: number | null },
) {
  counter += 1;
  const tokenHash = await sha256Hex(`conditional-token-${counter}-${Math.random()}`);
  await onHub(stub, (_hub, sql) => {
    sql.exec(
      "INSERT INTO pairing_tokens (tokenHash, expiresAt, usedAt) VALUES (?, ?, ?)",
      tokenHash,
      fields.expiresAt,
      fields.usedAt,
    );
  });
  return tokenHash;
}

function device(deviceId: string) {
  return {
    deviceId,
    deviceName: "Conditional phone",
    claimSecretHash: "ab".repeat(32),
    role: "adult",
  };
}

function rosterCount(stub: Stub, deviceId: string) {
  return onHub(
    stub,
    (_hub, sql) =>
      sql
        .exec<{ n: number }>(
          "SELECT COUNT(*) AS n FROM roster WHERE deviceId = ?",
          deviceId,
        )
        .one().n,
  );
}

function tokenRow(stub: Stub, tokenHash: string) {
  return onHub(
    stub,
    (_hub, sql) =>
      sql
        .exec<{ usedAt: number | null }>(
          "SELECT usedAt FROM pairing_tokens WHERE tokenHash = ?",
          tokenHash,
        )
        .one(),
  );
}

describe("spending a pairing token", () => {
  it("marks an unused, unexpired token used and creates the pending device, together", async () => {
    const stub = getFamilyHubStub();
    const now = Date.now();
    const tokenHash = await seedToken(stub, { expiresAt: now + 60_000, usedAt: null });
    const id = "f0000000-0000-4000-8000-000000000001";

    const spent = await onHub(stub, (hub) =>
      hub.claimPairingToken(tokenHash, now, device(id)),
    );

    expect(spent).toBe(true);
    expect(await tokenRow(stub, tokenHash)).toEqual({ usedAt: now });
    expect(await rosterCount(stub, id)).toBe(1);
  });

  it("refuses a token that was spent after the check, and creates no device", async () => {
    const stub = getFamilyHubStub();
    const now = Date.now();
    const tokenHash = await seedToken(stub, { expiresAt: now + 60_000, usedAt: null });
    // The row changes between the caller's check and the write.
    await onHub(stub, (_hub, sql) => {
      sql.exec("UPDATE pairing_tokens SET usedAt = ? WHERE tokenHash = ?", now - 5, tokenHash);
    });
    const id = "f0000000-0000-4000-8000-000000000002";

    const spent = await onHub(stub, (hub) =>
      hub.claimPairingToken(tokenHash, now, device(id)),
    );

    expect(spent).toBe(false);
    expect(await rosterCount(stub, id)).toBe(0);
    expect(await tokenRow(stub, tokenHash)).toEqual({ usedAt: now - 5 });
  });

  it("refuses a token that has expired, and one that does not exist", async () => {
    const stub = getFamilyHubStub();
    const now = Date.now();
    const expired = await seedToken(stub, { expiresAt: now - 1, usedAt: null });
    const idA = "f0000000-0000-4000-8000-000000000003";
    const idB = "f0000000-0000-4000-8000-000000000004";

    expect(
      await onHub(stub, (hub) => hub.claimPairingToken(expired, now, device(idA))),
    ).toBe(false);
    expect(
      await onHub(stub, (hub) =>
        hub.claimPairingToken("00".repeat(32), now, device(idB)),
      ),
    ).toBe(false);

    expect(await rosterCount(stub, idA)).toBe(0);
    expect(await rosterCount(stub, idB)).toBe(0);
    expect(await tokenRow(stub, expired)).toEqual({ usedAt: null });
  });

  it("lets only one of two claims win the same token", async () => {
    const stub = getFamilyHubStub();
    const now = Date.now();
    const tokenHash = await seedToken(stub, { expiresAt: now + 60_000, usedAt: null });
    const first = "f0000000-0000-4000-8000-000000000005";
    const second = "f0000000-0000-4000-8000-000000000006";

    const results = await onHub(stub, (hub) => [
      hub.claimPairingToken(tokenHash, now, device(first)),
      hub.claimPairingToken(tokenHash, now, device(second)),
    ]);

    expect(results).toEqual([true, false]);
    expect(await rosterCount(stub, first)).toBe(1);
    expect(await rosterCount(stub, second)).toBe(0);
  });
});

describe("committing an approval", () => {
  const SESSION_TOKEN = "5e".repeat(32);

  async function row(stub: Stub, deviceId: string) {
    return onHub(
      stub,
      (_hub, sql) =>
        sql
          .exec<{
            status: string;
            sessionTokenHash: string | null;
            pendingSessionToken: string | null;
            approvedAt: number | null;
          }>(
            "SELECT status, sessionTokenHash, pendingSessionToken, approvedAt FROM roster WHERE deviceId = ?",
            deviceId,
          )
          .one(),
    );
  }

  it("approves a fresh pending device", async () => {
    const stub = getFamilyHubStub();
    const id = "f1000000-0000-4000-8000-000000000001";
    await seedPendingDevice(stub, id, "Fresh", Date.now());
    const hash = await sha256Hex(SESSION_TOKEN);
    const now = Date.now();

    const done = await onHub(stub, (hub) =>
      hub.commitApproval(id, hash, SESSION_TOKEN, now),
    );

    expect(done).toBe(true);
    expect(await row(stub, id)).toEqual({
      status: "approved",
      sessionTokenHash: hash,
      pendingSessionToken: SESSION_TOKEN,
      approvedAt: now,
    });
  });

  it("does not resurrect a device that was revoked after the check", async () => {
    const stub = getFamilyHubStub();
    const id = "f1000000-0000-4000-8000-000000000002";
    await seedPendingDevice(stub, id, "Revoked meanwhile", Date.now());
    await onHub(stub, (_hub, sql) => {
      sql.exec(
        "UPDATE roster SET status = 'revoked', sessionTokenHash = NULL, pendingSessionToken = NULL, claimSecretHash = NULL WHERE deviceId = ?",
        id,
      );
    });

    const done = await onHub(stub, async (hub) =>
      hub.commitApproval(id, await sha256Hex(SESSION_TOKEN), SESSION_TOKEN, Date.now()),
    );

    expect(done).toBe(false);
    expect(await row(stub, id)).toEqual({
      status: "revoked",
      sessionTokenHash: null,
      pendingSessionToken: null,
      approvedAt: null,
    });
  });

  it("does not re-mint a device that was approved after the check", async () => {
    const stub = getFamilyHubStub();
    const id = "f1000000-0000-4000-8000-000000000003";
    await seedRoster(stub, {
      deviceId: id,
      status: "approved",
      sessionToken: "aa".repeat(32),
      approvedAt: 1234,
      claimedAt: Date.now(),
    });
    const before = await row(stub, id);

    const done = await onHub(stub, async (hub) =>
      hub.commitApproval(id, await sha256Hex(SESSION_TOKEN), SESSION_TOKEN, Date.now()),
    );

    expect(done).toBe(false);
    expect(await row(stub, id)).toEqual(before);
  });

  it("refuses a pending device that is past the hour, and an unknown one", async () => {
    const stub = getFamilyHubStub();
    const stale = "f1000000-0000-4000-8000-000000000004";
    await seedPendingDevice(stub, stale, "Stale", Date.now() - 2 * 60 * 60 * 1000);
    const hash = await sha256Hex(SESSION_TOKEN);

    expect(
      await onHub(stub, (hub) => hub.commitApproval(stale, hash, SESSION_TOKEN, Date.now())),
    ).toBe(false);
    expect(
      await onHub(stub, (hub) =>
        hub.commitApproval("no-such-device", hash, SESSION_TOKEN, Date.now()),
      ),
    ).toBe(false);
    expect((await row(stub, stale)).status).toBe("pending");
  });
});
