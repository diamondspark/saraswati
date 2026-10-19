import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

async function seedPendingDevice(
  stub: ReturnType<typeof getFamilyHubStub>,
  deviceId: string,
  deviceName: string,
) {
  await runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt) VALUES (?, ?, 'pending', NULL, NULL)",
      deviceId,
      deviceName,
    );
  });
}

describe("GET /roster", () => {
  it("returns 401 without admin auth", async () => {
    const stub = getFamilyHubStub();

    const response = await stub.fetch("https://example.com/roster");

    expect(response.status).toBe(401);
  });

  it("lists every claimed device and its status, when admin-authed", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "11111111-1111-1111-1111-111111111111";
    await seedPendingDevice(stub, deviceId, "Dadi's Tablet");

    const response = await stub.fetch("https://example.com/roster", {
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });

    expect(response.status).toBe(200);
    const body = (await response.json()) as {
      devices: Array<{ deviceId: string; deviceName: string; status: string }>;
    };
    expect(body.devices).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          deviceId,
          deviceName: "Dadi's Tablet",
          status: "pending",
        }),
      ]),
    );
  });
});

describe("POST /roster/{id}/approve", () => {
  it("sets status=approved and mints a session token for a pending device", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "22222222-2222-2222-2222-222222222222";
    await seedPendingDevice(stub, deviceId, "Papa's Phone");

    const response = await stub.fetch(
      `https://example.com/roster/${deviceId}/approve`,
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );

    expect(response.status).toBe(200);
    const body = (await response.json()) as {
      deviceId: string;
      sessionToken: string;
      status: string;
    };
    expect(body.deviceId).toBe(deviceId);
    expect(body.status).toBe("approved");
    expect(body.sessionToken).toMatch(/^[0-9a-f]{64}$/);
  });

  it("returns 404 when approving an already-approved device", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "33333333-3333-3333-3333-333333333333";
    await seedPendingDevice(stub, deviceId, "Mummy's Pixel");

    const approve = () =>
      stub.fetch(`https://example.com/roster/${deviceId}/approve`, {
        method: "POST",
        headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
      });

    const first = await approve();
    expect(first.status).toBe(200);

    const second = await approve();
    expect(second.status).toBe(404);
  });

  it("returns 404 when approving an unknown device", async () => {
    const stub = getFamilyHubStub();

    const response = await stub.fetch(
      "https://example.com/roster/no-such-device/approve",
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );

    expect(response.status).toBe(404);
  });

  it("returns 401 without admin auth", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "77777777-7777-7777-7777-777777777777";
    await seedPendingDevice(stub, deviceId, "Unauthed target");

    const response = await stub.fetch(
      `https://example.com/roster/${deviceId}/approve`,
      { method: "POST" },
    );

    expect(response.status).toBe(401);
  });
});

describe("POST /roster/{id}/revoke", () => {
  it("sets status=revoked, visible via a follow-up admin-authed GET /roster, and clears sessionToken", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "44444444-4444-4444-4444-444444444444";
    await seedPendingDevice(stub, deviceId, "Nani's Phone");

    const approveResponse = await stub.fetch(
      `https://example.com/roster/${deviceId}/approve`,
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );
    expect(approveResponse.status).toBe(200);

    const revokeResponse = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );
    expect(revokeResponse.status).toBe(200);
    const revokeBody = (await revokeResponse.json()) as {
      deviceId: string;
      status: string;
    };
    expect(revokeBody.status).toBe("revoked");

    // Verify via a follow-up admin-authed GET /roster, per the brief, since
    // no device-authed endpoint exists yet (Tasks 4-5) to prove revocation
    // any other way.
    const rosterResponse = await stub.fetch("https://example.com/roster", {
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });
    const rosterBody = (await rosterResponse.json()) as {
      devices: Array<{ deviceId: string; status: string }>;
    };
    const device = rosterBody.devices.find((d) => d.deviceId === deviceId);
    expect(device?.status).toBe("revoked");

    // GET /roster's response shape (Global Constraints' API table) doesn't
    // surface sessionToken at all, so confirm it's cleared to NULL the only
    // way left: reading the real DO's own storage directly.
    const sessionTokenAfterRevoke = await runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql
          .exec<{ sessionToken: string | null }>(
            "SELECT sessionToken FROM roster WHERE deviceId = ?",
            deviceId,
          )
          .one().sessionToken,
    );
    expect(sessionTokenAfterRevoke).toBeNull();
  });

  it("returns 401 without admin auth", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "88888888-8888-8888-8888-888888888888";
    await seedPendingDevice(stub, deviceId, "Unauthed revoke target");

    const response = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      { method: "POST" },
    );

    expect(response.status).toBe(401);
  });
});

// Review Focus item 2: a non-owner must get 401 on owner-only routes, not
// merely be hidden in the app's UI. Missing-header 401s are covered above;
// these cover a present-but-wrong bearer, a real device session token, and a
// revoked device's former token.
describe("admin-only routes reject every non-admin bearer token", () => {
  const WRONG_BEARER = "cd".repeat(32);

  type Authenticator = {
    authenticate(request: Request, env: unknown): Promise<unknown>;
  };

  function adminRoutes(targetDeviceId: string) {
    return [
      { method: "POST", path: "/pairing/create" },
      { method: "GET", path: "/roster" },
      { method: "POST", path: `/roster/${targetDeviceId}/approve` },
      { method: "POST", path: `/roster/${targetDeviceId}/revoke` },
      { method: "PUT", path: `/roster/${targetDeviceId}/role` },
    ];
  }

  async function expectAllUnauthorized(
    stub: ReturnType<typeof getFamilyHubStub>,
    token: string,
    targetDeviceId: string,
  ) {
    for (const { method, path } of adminRoutes(targetDeviceId)) {
      const response = await stub.fetch(`https://example.com${path}`, {
        method,
        headers: { Authorization: `Bearer ${token}` },
      });
      expect(response.status, `${method} ${path}`).toBe(401);
    }
  }

  async function approveAndGetSessionToken(
    stub: ReturnType<typeof getFamilyHubStub>,
    deviceId: string,
  ) {
    const response = await stub.fetch(
      `https://example.com/roster/${deviceId}/approve`,
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );
    expect(response.status).toBe(200);
    return ((await response.json()) as { sessionToken: string }).sessionToken;
  }

  function authenticateAs(
    stub: ReturnType<typeof getFamilyHubStub>,
    token: string,
  ) {
    return runInDurableObject(stub, (instance) =>
      (instance as unknown as Authenticator).authenticate(
        new Request("https://example.com/anything", {
          headers: { Authorization: `Bearer ${token}` },
        }),
        env,
      ),
    );
  }

  it("returns 401 on every admin route for a bearer that is neither the admin key nor a session token", async () => {
    const stub = getFamilyHubStub();
    const targetId = "55555555-5555-5555-5555-555555555555";
    await seedPendingDevice(stub, targetId, "Untouched target");

    await expectAllUnauthorized(stub, WRONG_BEARER, targetId);

    // Rejected requests must not have touched storage.
    const rosterResponse = await stub.fetch("https://example.com/roster", {
      headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
    });
    const { devices } = (await rosterResponse.json()) as {
      devices: Array<{ deviceId: string; status: string }>;
    };
    expect(devices.find((d) => d.deviceId === targetId)?.status).toBe(
      "pending",
    );
  });

  it("returns 401 on every admin route for an approved device's real session token", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "66666666-6666-6666-6666-666666666666";
    const targetId = "99999999-9999-9999-9999-999999999999";
    await seedPendingDevice(stub, deviceId, "Approved device");
    await seedPendingDevice(stub, targetId, "Pending target");
    const sessionToken = await approveAndGetSessionToken(stub, deviceId);

    // The token is genuinely valid as a device credential...
    expect(await authenticateAs(stub, sessionToken)).toEqual({
      role: "device",
      deviceId,
    });

    // ...but a device is never the owner.
    await expectAllUnauthorized(stub, sessionToken, targetId);
  });

  it("returns 401 on every admin route for a revoked device's former session token", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    const targetId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    await seedPendingDevice(stub, deviceId, "Soon revoked");
    await seedPendingDevice(stub, targetId, "Pending target");
    const sessionToken = await approveAndGetSessionToken(stub, deviceId);

    const revokeResponse = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
    );
    expect(revokeResponse.status).toBe(200);

    expect(await authenticateAs(stub, sessionToken)).toBeNull();
    await expectAllUnauthorized(stub, sessionToken, targetId);
  });

  it("authenticates the admin key as role admin", async () => {
    const stub = getFamilyHubStub();

    expect(await authenticateAs(stub, TEST_ADMIN_KEY)).toEqual({
      role: "admin",
    });
  });
});
