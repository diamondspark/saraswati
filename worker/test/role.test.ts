import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const WRONG_BEARER = "cd".repeat(32);
const ADMIN_BUDGET = 20;

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

type Stub = ReturnType<typeof getFamilyHubStub>;
type Role = "adult" | "grandparent";

const adminHeaders = { Authorization: `Bearer ${TEST_ADMIN_KEY}` };

interface Claimed {
  deviceId: string;
  status: string;
  claimSecret: string;
}

async function mintPairingToken(stub: Stub): Promise<string> {
  const response = await stub.fetch("https://example.com/pairing/create", {
    method: "POST",
    headers: adminHeaders,
  });
  return ((await response.json()) as { token: string }).token;
}

function claimWith(stub: Stub, token: string, extra: Record<string, unknown>) {
  return stub.fetch("https://example.com/pairing/claim", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token, deviceName: "Role Phone", ...extra }),
  });
}

async function claimDevice(
  stub: Stub,
  extra: Record<string, unknown> = {},
): Promise<Claimed> {
  const response = await claimWith(stub, await mintPairingToken(stub), extra);
  expect(response.status).toBe(200);
  return (await response.json()) as Claimed;
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

function putRole(
  stub: Stub,
  deviceId: string,
  body: unknown,
  token: string | null = TEST_ADMIN_KEY,
) {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return stub.fetch(`https://example.com/roster/${deviceId}/role`, {
    method: "PUT",
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

function getMe(stub: Stub, token: string | null) {
  return stub.fetch("https://example.com/me", {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

async function rosterEntry(stub: Stub, deviceId: string) {
  const response = await stub.fetch("https://example.com/roster", {
    headers: adminHeaders,
  });
  expect(response.status).toBe(200);
  const { devices } = (await response.json()) as {
    devices: Array<{ deviceId: string; role?: string; status: string }>;
  };
  return devices.find((d) => d.deviceId === deviceId);
}

function storedRole(stub: Stub, deviceId: string) {
  return runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{ role: string }>(
          "SELECT role FROM roster WHERE deviceId = ?",
          deviceId,
        )
        .one().role,
  );
}

async function adminBudgetCount(stub: Stub): Promise<number> {
  const row = await runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{ count: number }>(
          "SELECT count FROM rate_limits WHERE key = 'admin'",
        )
        .toArray()[0],
  );
  return row?.count ?? 0;
}

function spendAdminBudget(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "INSERT OR REPLACE INTO rate_limits (key, windowStart, count) VALUES ('admin', ?, ?)",
      Date.now(),
      ADMIN_BUDGET,
    );
  });
}

describe("POST /pairing/claim with a role", () => {
  it("stores 'adult' when the body carries no role, and the response shape is unchanged", async () => {
    const stub = getFamilyHubStub();

    const claimed = await claimDevice(stub);

    expect(Object.keys(claimed).sort()).toEqual([
      "claimSecret",
      "deviceId",
      "status",
    ]);
    expect(claimed.status).toBe("pending");
    expect(await storedRole(stub, claimed.deviceId)).toBe("adult");
  });

  it("stores the role the phone picked in onboarding", async () => {
    const stub = getFamilyHubStub();

    const grandparent = await claimDevice(stub, { role: "grandparent" });
    const adult = await claimDevice(stub, { role: "adult" });

    expect(Object.keys(grandparent).sort()).toEqual([
      "claimSecret",
      "deviceId",
      "status",
    ]);
    expect(await storedRole(stub, grandparent.deviceId)).toBe("grandparent");
    expect(await storedRole(stub, adult.deviceId)).toBe("adult");
  });

  it.each([
    ["an unknown role", "child"],
    ["a different case", "Grandparent"],
    ["an empty string", ""],
    ["a number", 7],
    ["null", null],
    ["an object", { role: "adult" }],
  ])("answers 400 for %s and leaves the pairing token unspent", async (_label, role) => {
    const stub = getFamilyHubStub();
    const token = await mintPairingToken(stub);
    const rosterBefore = await runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql.exec("SELECT deviceId FROM roster").toArray().length,
    );

    const rejected = await claimWith(stub, token, { role });

    expect(rejected.status).toBe(400);
    const rosterAfter = await runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql.exec("SELECT deviceId FROM roster").toArray().length,
    );
    expect(rosterAfter).toBe(rosterBefore);

    // The same token still works for a correct retry.
    const retry = await claimWith(stub, token, { role: "grandparent" });
    expect(retry.status).toBe(200);
    const { deviceId } = (await retry.json()) as Claimed;
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
  });
});

describe("GET /roster role", () => {
  it("includes each device's role, defaulting to adult", async () => {
    const stub = getFamilyHubStub();
    const plain = await claimDevice(stub);
    const grandparent = await claimDevice(stub, { role: "grandparent" });

    expect((await rosterEntry(stub, plain.deviceId))?.role).toBe("adult");
    expect((await rosterEntry(stub, grandparent.deviceId))?.role).toBe(
      "grandparent",
    );
  });

  it("shows a row seeded without a role as adult", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "c0000000-0000-4000-8000-000000000001";
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt) VALUES (?, 'Seeded', 'pending', NULL, NULL)",
        deviceId,
      );
    });

    expect((await rosterEntry(stub, deviceId))?.role).toBe("adult");
  });
});

describe("PUT /roster/{deviceId}/role", () => {
  it("lets the admin change a pending device's role and answers {deviceId, role}", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);

    const response = await putRole(stub, deviceId, { role: "grandparent" });

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deviceId, role: "grandparent" });
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
    expect((await rosterEntry(stub, deviceId))?.role).toBe("grandparent");
  });

  it("lets the admin switch an approved device to grandparent and back to adult", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    await approve(stub, deviceId);

    const toGrandparent = await putRole(stub, deviceId, { role: "grandparent" });
    expect(toGrandparent.status).toBe(200);
    expect((await rosterEntry(stub, deviceId))?.role).toBe("grandparent");

    const toAdult = await putRole(stub, deviceId, { role: "adult" });
    expect(toAdult.status).toBe(200);
    expect(await toAdult.json()).toEqual({ deviceId, role: "adult" });
    expect((await rosterEntry(stub, deviceId))?.role).toBe("adult");
  });

  it("changes only the targeted device and leaves its status and session token alone", async () => {
    const stub = getFamilyHubStub();
    const target = await claimDevice(stub);
    const bystander = await claimDevice(stub);
    const sessionToken = await approve(stub, target.deviceId);

    await putRole(stub, target.deviceId, { role: "grandparent" });

    expect(await storedRole(stub, bystander.deviceId)).toBe("adult");
    expect((await rosterEntry(stub, target.deviceId))?.status).toBe("approved");
    expect((await getMe(stub, sessionToken)).status).toBe(200);
  });

  it("answers 401 with no Authorization header, a wrong bearer, or a device's own session token, and changes nothing", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    const sessionToken = await approve(stub, deviceId);
    const spentBefore = await adminBudgetCount(stub);

    for (const token of [null, WRONG_BEARER, sessionToken]) {
      const response = await putRole(
        stub,
        deviceId,
        { role: "grandparent" },
        token,
      );
      expect(response.status, String(token)).toBe(401);
    }

    expect(await storedRole(stub, deviceId)).toBe("adult");
    expect(await adminBudgetCount(stub)).toBe(spentBefore);
  });

  it("keeps a grandparent-mode phone from switching itself back: its own session token is 401", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub, { role: "grandparent" });
    const sessionToken = await approve(stub, deviceId);

    const response = await putRole(
      stub,
      deviceId,
      { role: "adult" },
      sessionToken,
    );

    expect(response.status).toBe(401);
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
  });

  it("answers 404 for an unknown deviceId", async () => {
    const stub = getFamilyHubStub();

    const response = await putRole(stub, "no-such-device", {
      role: "grandparent",
    });

    expect(response.status).toBe(404);
  });

  it("answers 404 for a revoked device and leaves its role alone", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub, { role: "grandparent" });
    await approve(stub, deviceId);
    await revoke(stub, deviceId);

    const response = await putRole(stub, deviceId, { role: "adult" });

    expect(response.status).toBe(404);
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
  });

  it.each([
    ["a body that is not JSON", "not json"],
    ["a JSON array", ["grandparent"]],
    ["a JSON string", '"grandparent"'],
    ["an empty object", {}],
    ["an unknown role", { role: "child" }],
    ["a different case", { role: "Adult" }],
    ["a non-string role", { role: 1 }],
    ["a null role", { role: null }],
  ])("answers 400 for %s and changes nothing", async (_label, body) => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub, { role: "grandparent" });

    const response = await putRole(stub, deviceId, body);

    expect(response.status).toBe(400);
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
  });

  it("spends the shared admin budget, and does nothing once it is gone", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);

    const spentBefore = await adminBudgetCount(stub);
    const first = await putRole(stub, deviceId, { role: "grandparent" });
    expect(first.status).toBe(200);
    expect(await adminBudgetCount(stub)).toBe(spentBefore + 1);

    await spendAdminBudget(stub);
    const throttled = await putRole(stub, deviceId, { role: "adult" });

    expect(throttled.status).toBe(429);
    expect(await storedRole(stub, deviceId)).toBe("grandparent");
  });
});

describe("GET /me", () => {
  it("answers 401 with no Authorization header or a wrong bearer", async () => {
    const stub = getFamilyHubStub();

    expect((await getMe(stub, null)).status).toBe(401);
    expect((await getMe(stub, WRONG_BEARER)).status).toBe(401);
  });

  it("answers 401 to the admin key and does not spend the admin budget", async () => {
    const stub = getFamilyHubStub();

    const response = await getMe(stub, TEST_ADMIN_KEY);

    expect(response.status).toBe(401);
    expect(await adminBudgetCount(stub)).toBe(0);
  });

  it("tells an approved device its deviceId and role", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    const sessionToken = await approve(stub, deviceId);

    const response = await getMe(stub, sessionToken);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deviceId, role: "adult" });
  });

  it("reports the role the phone claimed with", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub, { role: "grandparent" });
    const sessionToken = await approve(stub, deviceId);

    const response = await getMe(stub, sessionToken);

    expect(await response.json()).toEqual({ deviceId, role: "grandparent" });
  });

  it("returns the current role after the owner changes it", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub, { role: "grandparent" });
    const sessionToken = await approve(stub, deviceId);
    expect(await (await getMe(stub, sessionToken)).json()).toEqual({
      deviceId,
      role: "grandparent",
    });

    const change = await putRole(stub, deviceId, { role: "adult" });
    expect(change.status).toBe(200);

    expect(await (await getMe(stub, sessionToken)).json()).toEqual({
      deviceId,
      role: "adult",
    });
  });

  it("answers 401 to a revoked device's former session token", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    const sessionToken = await approve(stub, deviceId);
    await revoke(stub, deviceId);

    expect((await getMe(stub, sessionToken)).status).toBe(401);
  });

  it("is not counted against the admin budget when a device asks, even with the budget spent", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    const sessionToken = await approve(stub, deviceId);
    await spendAdminBudget(stub);

    for (let i = 0; i < ADMIN_BUDGET + 5; i++) {
      const response = await getMe(stub, sessionToken);
      expect(response.status, `call ${i}`).toBe(200);
      expect(await response.json()).toEqual({ deviceId, role: "adult" });
    }
    expect(await adminBudgetCount(stub)).toBe(ADMIN_BUDGET);
  });

  it("only answers GET", async () => {
    const stub = getFamilyHubStub();
    const { deviceId } = await claimDevice(stub);
    const sessionToken = await approve(stub, deviceId);

    const response = await stub.fetch("https://example.com/me", {
      method: "PUT",
      headers: { Authorization: `Bearer ${sessionToken}` },
      body: JSON.stringify({ role: "adult" }),
    });

    expect(response.status).toBe(404);
  });
});
