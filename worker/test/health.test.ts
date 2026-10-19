import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const WRONG_BEARER = "cd".repeat(32);
const HEALTH_WINDOW_MS = 5 * 60 * 1000;

interface HealthReport {
  deviceId: string;
  versionCode: number;
  versionName: string;
  timezone: string;
}

interface DeviceHealth extends HealthReport {
  deviceName: string | null;
  lastSeenAt: number;
}

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

type Stub = ReturnType<typeof getFamilyHubStub>;

let deviceCounter = 0;

/** A fresh, well-formed deviceId, so cases never share state. */
function nextDeviceId(): string {
  deviceCounter += 1;
  return `00000000-0000-4000-8000-${String(deviceCounter).padStart(12, "0")}`;
}

function report(deviceId: string, overrides: Partial<HealthReport> = {}) {
  return {
    deviceId,
    versionCode: 3902,
    versionName: "3.9.2-saraswati",
    timezone: "Asia/Kolkata",
    ...overrides,
  };
}

function bearer(token: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    "content-type": "application/json",
  };
}

function postHealth(stub: Stub, token: string | null, body: unknown) {
  return stub.fetch("https://example.com/health", {
    method: "POST",
    headers: token
      ? bearer(token)
      : { "content-type": "application/json" },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

function getHealth(stub: Stub, token?: string) {
  return stub.fetch("https://example.com/health", {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

async function adminHealth(stub: Stub): Promise<DeviceHealth[]> {
  const response = await getHealth(stub, TEST_ADMIN_KEY);
  expect(response.status).toBe(200);
  return ((await response.json()) as { devices: DeviceHealth[] }).devices;
}

async function healthOf(stub: Stub, deviceId: string) {
  return (await adminHealth(stub)).find((d) => d.deviceId === deviceId);
}

async function seedPendingDevice(
  stub: Stub,
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

async function approveAndGetSessionToken(stub: Stub, deviceId: string) {
  const response = await stub.fetch(
    `https://example.com/roster/${deviceId}/approve`,
    { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
  );
  expect(response.status).toBe(200);
  return ((await response.json()) as { sessionToken: string }).sessionToken;
}

async function approvedDeviceToken(
  stub: Stub,
  deviceId: string,
  deviceName = `Device ${deviceId}`,
) {
  await seedPendingDevice(stub, deviceId, deviceName);
  return approveAndGetSessionToken(stub, deviceId);
}

/** Pretends `elapsedMs` have passed since the device's health window opened. */
function ageHealthWindow(stub: Stub, deviceId: string, elapsedMs: number) {
  return runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "UPDATE rate_limits SET windowStart = ? WHERE key = ?",
      Date.now() - elapsedMs,
      `health:${deviceId}`,
    );
  });
}

function storageSnapshot(stub: Stub, deviceIds: string[]) {
  return runInDurableObject(stub, (_instance, state) => {
    const sql = state.storage.sql;
    const healthRows = deviceIds.map((id) =>
      sql
        .exec("SELECT * FROM health WHERE deviceId = ?", id)
        .toArray(),
    );
    const limitRows = deviceIds.map((id) =>
      sql
        .exec("SELECT * FROM rate_limits WHERE key = ?", `health:${id}`)
        .toArray(),
    );
    return { healthRows, limitRows };
  });
}

describe("POST /health", () => {
  it("returns 401 and touches no storage with no Authorization header", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e1111111-1111-1111-1111-111111111111";
    await approvedDeviceToken(stub, deviceId);

    const response = await postHealth(stub, null, report(deviceId));

    expect(response.status).toBe(401);
    expect(await storageSnapshot(stub, [deviceId])).toEqual({
      healthRows: [[]],
      limitRows: [[]],
    });
  });

  it("returns 401 for a bearer that is neither the admin key nor a session token", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e2222222-2222-2222-2222-222222222222";
    await approvedDeviceToken(stub, deviceId);

    const response = await postHealth(stub, WRONG_BEARER, report(deviceId));

    expect(response.status).toBe(401);
    expect(await healthOf(stub, deviceId)).toBeUndefined();
  });

  it("returns 401 when a claimed-but-pending device presents its deviceId as a bearer", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e3333333-3333-3333-3333-333333333333";
    await seedPendingDevice(stub, deviceId, "Still pending");

    const response = await postHealth(stub, deviceId, report(deviceId));

    expect(response.status).toBe(401);
    expect(await healthOf(stub, deviceId)).toBeUndefined();
  });

  it("returns 401 for a revoked device's former session token", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e4444444-4444-4444-4444-444444444444";
    const token = await approvedDeviceToken(stub, deviceId);
    const revoke = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
      },
    );
    expect(revoke.status).toBe(200);

    const response = await postHealth(stub, token, report(deviceId));

    expect(response.status).toBe(401);
    expect(await healthOf(stub, deviceId)).toBeUndefined();
  });

  it("returns 401 for the admin key, since the route is device-only and the admin has no deviceId", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e5555555-5555-5555-5555-555555555555";
    await approvedDeviceToken(stub, deviceId);

    const response = await postHealth(stub, TEST_ADMIN_KEY, report(deviceId));

    expect(response.status).toBe(401);
    expect(await healthOf(stub, deviceId)).toBeUndefined();
  });

  it("upserts the row with timezone and returns 204 with no body", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "f1111111-1111-1111-1111-111111111111";
    const token = await approvedDeviceToken(stub, deviceId, "Mummy's Pixel");
    const before = Date.now();

    const response = await postHealth(stub, token, report(deviceId));

    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    const row = await healthOf(stub, deviceId);
    expect(row).toEqual({
      deviceId,
      deviceName: "Mummy's Pixel",
      versionCode: 3902,
      versionName: "3.9.2-saraswati",
      timezone: "Asia/Kolkata",
      lastSeenAt: expect.any(Number),
    });
    expect(row?.lastSeenAt).toBeGreaterThanOrEqual(before);
    expect(row?.lastSeenAt).toBeLessThanOrEqual(Date.now());
  });

  it("ignores unknown fields in the body", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "f2222222-2222-2222-2222-222222222222";
    const token = await approvedDeviceToken(stub, deviceId);

    const response = await postHealth(stub, token, {
      ...report(deviceId),
      sneaky: "extra",
      lastSeenAt: 1,
    });

    expect(response.status).toBe(204);
    expect((await healthOf(stub, deviceId))?.lastSeenAt).toBeGreaterThan(1);
  });

  it("returns 204 for a second report inside 5 minutes but writes nothing", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "f3333333-3333-3333-3333-333333333333";
    const token = await approvedDeviceToken(stub, deviceId);
    expect((await postHealth(stub, token, report(deviceId))).status).toBe(204);
    const first = await healthOf(stub, deviceId);
    const storedFirst = await storageSnapshot(stub, [deviceId]);

    const second = await postHealth(
      stub,
      token,
      report(deviceId, {
        versionCode: 4000,
        versionName: "4.0.0-saraswati",
        timezone: "America/Toronto",
      }),
    );

    expect(second.status).toBe(204);
    expect(await second.text()).toBe("");
    expect(await healthOf(stub, deviceId)).toEqual(first);
    expect(await storageSnapshot(stub, [deviceId])).toEqual(storedFirst);
  });

  it("accepts a new report once the 5-minute window has passed, updating every field", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "f4444444-4444-4444-4444-444444444444";
    const token = await approvedDeviceToken(stub, deviceId);
    expect((await postHealth(stub, token, report(deviceId))).status).toBe(204);
    const first = await healthOf(stub, deviceId);
    await ageHealthWindow(stub, deviceId, HEALTH_WINDOW_MS);

    const response = await postHealth(
      stub,
      token,
      report(deviceId, {
        versionCode: 4000,
        versionName: "4.0.0-saraswati",
        timezone: "America/Toronto",
      }),
    );

    expect(response.status).toBe(204);
    const updated = await healthOf(stub, deviceId);
    expect(updated).toMatchObject({
      deviceId,
      versionCode: 4000,
      versionName: "4.0.0-saraswati",
      timezone: "America/Toronto",
    });
    expect(updated?.lastSeenAt).toBeGreaterThan(first!.lastSeenAt);
  });

  it("keeps dropping reports while the window has not fully elapsed", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "f5555555-5555-5555-5555-555555555555";
    const token = await approvedDeviceToken(stub, deviceId);
    expect((await postHealth(stub, token, report(deviceId))).status).toBe(204);
    const first = await healthOf(stub, deviceId);
    await ageHealthWindow(stub, deviceId, HEALTH_WINDOW_MS - 10_000);

    const response = await postHealth(
      stub,
      token,
      report(deviceId, { timezone: "America/Toronto" }),
    );

    expect(response.status).toBe(204);
    expect(await healthOf(stub, deviceId)).toEqual(first);
  });

  it("rate-limits each device on its own window", async () => {
    const stub = getFamilyHubStub();
    const idA = "f6666666-6666-6666-6666-666666666666";
    const idB = "f7777777-7777-7777-7777-777777777777";
    const tokenA = await approvedDeviceToken(stub, idA);
    const tokenB = await approvedDeviceToken(stub, idB);
    expect((await postHealth(stub, tokenA, report(idA))).status).toBe(204);

    const response = await postHealth(
      stub,
      tokenB,
      report(idB, { timezone: "America/Toronto" }),
    );

    expect(response.status).toBe(204);
    expect((await healthOf(stub, idB))?.timezone).toBe("America/Toronto");
  });

  describe("with a body deviceId that is not the authenticated device", () => {
    it("returns 400 and writes neither a health row nor a rate-limit window for anyone", async () => {
      const stub = getFamilyHubStub();
      const attackerId = "a1111111-1111-1111-1111-111111111111";
      const victimId = "a2222222-2222-2222-2222-222222222222";
      const attackerToken = await approvedDeviceToken(stub, attackerId);
      await approvedDeviceToken(stub, victimId);

      const response = await postHealth(stub, attackerToken, report(victimId));

      expect(response.status).toBe(400);
      expect(await storageSnapshot(stub, [attackerId, victimId])).toEqual({
        healthRows: [[], []],
        limitRows: [[], []],
      });
    });

    it("cannot overwrite another device's existing row", async () => {
      const stub = getFamilyHubStub();
      const attackerId = "a3333333-3333-3333-3333-333333333333";
      const victimId = "a4444444-4444-4444-4444-444444444444";
      const attackerToken = await approvedDeviceToken(stub, attackerId);
      const victimToken = await approvedDeviceToken(stub, victimId);
      expect((await postHealth(stub, victimToken, report(victimId))).status).toBe(
        204,
      );
      const victimRow = await healthOf(stub, victimId);
      await ageHealthWindow(stub, victimId, HEALTH_WINDOW_MS);

      const response = await postHealth(
        stub,
        attackerToken,
        report(victimId, { timezone: "America/Toronto", versionCode: 1 }),
      );

      expect(response.status).toBe(400);
      expect(await healthOf(stub, victimId)).toEqual(victimRow);
    });

    it("cannot burn another device's rate-limit window, so the victim's own next report still lands", async () => {
      const stub = getFamilyHubStub();
      const attackerId = "a5555555-5555-5555-5555-555555555555";
      const victimId = "a6666666-6666-6666-6666-666666666666";
      const attackerToken = await approvedDeviceToken(stub, attackerId);
      const victimToken = await approvedDeviceToken(stub, victimId);
      expect(
        (await postHealth(stub, attackerToken, report(victimId))).status,
      ).toBe(400);

      const response = await postHealth(stub, victimToken, report(victimId));

      expect(response.status).toBe(204);
      expect(await healthOf(stub, victimId)).toBeDefined();
    });
  });

  const malformedBodies: Array<[string, (deviceId: string) => unknown]> = [
    ["invalid JSON", () => "{not json"],
    ["a JSON array instead of an object", (id) => [report(id)]],
    ["a null body", () => null],
    ["an empty object", () => ({})],
    ["a missing deviceId", (id) => report(id, { deviceId: undefined })],
    ["a non-string deviceId", () => report("x", { deviceId: 5 as never })],
    ["a missing versionCode", (id) => report(id, { versionCode: undefined })],
    ["a string versionCode", (id) => report(id, { versionCode: "3902" as never })],
    ["a fractional versionCode", (id) => report(id, { versionCode: 39.5 })],
    ["a negative versionCode", (id) => report(id, { versionCode: -1 })],
    ["a missing versionName", (id) => report(id, { versionName: undefined })],
    ["an empty versionName", (id) => report(id, { versionName: "" })],
    ["a non-string versionName", (id) => report(id, { versionName: 3.9 as never })],
    [
      "an over-long versionName",
      (id) => report(id, { versionName: "v".repeat(129) }),
    ],
    ["a missing timezone", (id) => report(id, { timezone: undefined })],
    ["an empty timezone", (id) => report(id, { timezone: "" })],
    ["a non-string timezone", (id) => report(id, { timezone: 5 as never })],
    [
      "an over-long timezone",
      (id) => report(id, { timezone: "Z".repeat(129) }),
    ],
  ];

  it.each(malformedBodies)(
    "rejects %s with 400, writes nothing, and does not use up the device's window",
    async (_label, makeBody) => {
      const stub = getFamilyHubStub();
      const deviceId = nextDeviceId();
      const token = await approvedDeviceToken(stub, deviceId);

      const response = await postHealth(stub, token, makeBody(deviceId));

      expect(response.status).toBe(400);
      expect(await storageSnapshot(stub, [deviceId])).toEqual({
        healthRows: [[]],
        limitRows: [[]],
      });
      expect(
        (await postHealth(stub, token, report(deviceId))).status,
      ).toBe(204);
      expect(await healthOf(stub, deviceId)).toBeDefined();
    },
  );
});

describe("GET /health", () => {
  it("returns 401 with no Authorization header", async () => {
    const stub = getFamilyHubStub();

    expect((await getHealth(stub)).status).toBe(401);
  });

  it("returns 401 for a wrong bearer, an approved device's session token, and a revoked one", async () => {
    const stub = getFamilyHubStub();
    const approvedId = "c1111111-1111-1111-1111-111111111111";
    const revokedId = "c2222222-2222-2222-2222-222222222222";
    const approved = await approvedDeviceToken(stub, approvedId);
    const revoked = await approvedDeviceToken(stub, revokedId);
    const revoke = await stub.fetch(
      `https://example.com/roster/${revokedId}/revoke`,
      {
        method: "POST",
        headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` },
      },
    );
    expect(revoke.status).toBe(200);

    for (const token of [WRONG_BEARER, approved, revoked]) {
      expect((await getHealth(stub, token)).status).toBe(401);
    }
  });

  it("lists every device's last report, with timezone and the deviceName from the roster", async () => {
    const stub = getFamilyHubStub();
    const idA = "d1111111-1111-1111-1111-111111111111";
    const idB = "d2222222-2222-2222-2222-222222222222";
    const tokenA = await approvedDeviceToken(stub, idA, "Dadi's Tablet");
    const tokenB = await approvedDeviceToken(stub, idB, "Papa's Phone");
    expect((await postHealth(stub, tokenA, report(idA))).status).toBe(204);
    expect(
      (
        await postHealth(
          stub,
          tokenB,
          report(idB, {
            versionCode: 3800,
            versionName: "3.8.0-saraswati",
            timezone: "America/Toronto",
          }),
        )
      ).status,
    ).toBe(204);

    const response = await getHealth(stub, TEST_ADMIN_KEY);

    expect(response.status).toBe(200);
    const body = (await response.json()) as { devices: DeviceHealth[] };
    expect(body.devices).toEqual(
      expect.arrayContaining([
        {
          deviceId: idA,
          deviceName: "Dadi's Tablet",
          versionCode: 3902,
          versionName: "3.9.2-saraswati",
          timezone: "Asia/Kolkata",
          lastSeenAt: expect.any(Number),
        },
        {
          deviceId: idB,
          deviceName: "Papa's Phone",
          versionCode: 3800,
          versionName: "3.8.0-saraswati",
          timezone: "America/Toronto",
          lastSeenAt: expect.any(Number),
        },
      ]),
    );
  });

  it("does not list a device that has never reported", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "d3333333-3333-3333-3333-333333333333";
    await approvedDeviceToken(stub, deviceId);

    expect(await healthOf(stub, deviceId)).toBeUndefined();
  });
});
