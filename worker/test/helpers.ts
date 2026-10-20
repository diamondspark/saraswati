import { env, runInDurableObject } from "cloudflare:test";
import { expect } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
export const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

export const adminHeaders = { Authorization: `Bearer ${TEST_ADMIN_KEY}` };

export function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

export type Stub = ReturnType<typeof getFamilyHubStub>;

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

/** The claimSecret every directly-seeded pending device is given. */
export const SEEDED_CLAIM_SECRET = "cc".repeat(32);

export interface SeedRow {
  deviceId: string;
  deviceName?: string | null;
  status?: "pending" | "approved" | "revoked";
  /** Plaintext; stored hashed, the way the hub stores it. */
  sessionToken?: string | null;
  pendingSessionToken?: string | null;
  /** Plaintext; stored hashed. */
  claimSecret?: string | null;
  approvedAt?: number | null;
  claimedAt?: number | null;
  role?: string;
}

/** Writes a roster row straight into storage, hashing the secrets as the hub does. */
export async function seedRoster(stub: Stub, row: SeedRow): Promise<void> {
  const sessionTokenHash =
    row.sessionToken == null ? null : await sha256Hex(row.sessionToken);
  const claimSecretHash =
    row.claimSecret == null ? null : await sha256Hex(row.claimSecret);
  await runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      `INSERT INTO roster
         (deviceId, deviceName, status, sessionTokenHash, pendingSessionToken,
          approvedAt, claimSecretHash, role, claimedAt)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.deviceId,
      row.deviceName ?? `Device ${row.deviceId}`,
      row.status ?? "pending",
      sessionTokenHash,
      row.pendingSessionToken ?? null,
      row.approvedAt ?? null,
      claimSecretHash,
      row.role ?? "adult",
      row.claimedAt ?? null,
    );
  });
}

export async function seedPendingDevice(
  stub: Stub,
  deviceId: string,
  deviceName: string,
  claimedAt: number = Date.now(),
): Promise<void> {
  await seedRoster(stub, {
    deviceId,
    deviceName,
    status: "pending",
    claimSecret: SEEDED_CLAIM_SECRET,
    claimedAt,
  });
}

export async function approveDevice(stub: Stub, deviceId: string) {
  const response = await stub.fetch(
    `https://example.com/roster/${deviceId}/approve`,
    { method: "POST", headers: adminHeaders },
  );
  expect(response.status).toBe(200);
  return response;
}

export async function collectSessionToken(
  stub: Stub,
  deviceId: string,
  claimSecret: string,
): Promise<string> {
  const response = await stub.fetch("https://example.com/pairing/status", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId, claimSecret }),
  });
  expect(response.status).toBe(200);
  const body = (await response.json()) as {
    status: string;
    sessionToken?: string;
  };
  expect(body.status).toBe("approved");
  return body.sessionToken as string;
}

/** claimSecrets handed out by claimDevice(), so a later approve can collect. */
const claimSecrets = new Map<string, string>();

/**
 * Approves a device made with seedPendingDevice or claimDevice and collects
 * its session token the way a phone does, through POST /pairing/status.
 */
export async function approveAndGetSessionToken(
  stub: Stub,
  deviceId: string,
): Promise<string> {
  await approveDevice(stub, deviceId);
  return collectSessionToken(
    stub,
    deviceId,
    claimSecrets.get(deviceId) ?? SEEDED_CLAIM_SECRET,
  );
}

export async function mintPairingToken(stub: Stub): Promise<string> {
  const response = await stub.fetch("https://example.com/pairing/create", {
    method: "POST",
    headers: adminHeaders,
  });
  expect(response.status).toBe(200);
  return ((await response.json()) as { token: string }).token;
}

export interface Claimed {
  deviceId: string;
  status: string;
  claimSecret: string;
}

export async function claimWithToken(
  stub: Stub,
  token: string,
  extra: Record<string, unknown> = {},
): Promise<Response> {
  return stub.fetch("https://example.com/pairing/claim", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token, deviceName: "Test Phone", ...extra }),
  });
}

/** The pairing handshake up to, and including, the phone's claim. */
export async function claimDevice(
  stub: Stub,
  extra: Record<string, unknown> = {},
): Promise<Claimed> {
  return (await claimDeviceWithToken(stub, extra)).claimed;
}

/** Like claimDevice, but also hands back the plaintext pairing token used. */
export async function claimDeviceWithToken(
  stub: Stub,
  extra: Record<string, unknown> = {},
): Promise<{ claimed: Claimed; token: string }> {
  const token = await mintPairingToken(stub);
  const response = await claimWithToken(stub, token, extra);
  expect(response.status).toBe(200);
  const claimed = (await response.json()) as Claimed;
  claimSecrets.set(claimed.deviceId, claimed.claimSecret);
  return { claimed, token };
}

/** Every table's rows, for asserting what is (and is not) at rest. */
export function dumpAllTables(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) => {
    const tables = state.storage.sql
      .exec<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' ORDER BY name",
      )
      .toArray()
      .map((row) => row.name);
    return Object.fromEntries(
      tables.map((name) => [
        name,
        state.storage.sql.exec(`SELECT * FROM "${name}" ORDER BY rowid`).toArray(),
      ]),
    );
  });
}
