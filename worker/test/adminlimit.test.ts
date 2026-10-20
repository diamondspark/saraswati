import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  type Stub,
  TEST_ADMIN_KEY,
  getFamilyHubStub,
  seedRoster,
  sha256Hex,
} from "./helpers";

const WRONG_BEARER = "cd".repeat(32);
const ADMIN_BUDGET = 20;
const ADMIN_WINDOW_MS = 60_000;

interface Route {
  method: string;
  path: string;
  body?: unknown;
}

function send(stub: Stub, route: Route, token: string | null) {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  return stub.fetch(`https://example.com${route.path}`, {
    method: route.method,
    headers,
    body: route.body === undefined ? undefined : JSON.stringify(route.body),
  });
}

const station = {
  id: "station-1",
  title: "Station 1",
  seedVideoId: "video000001",
  iconIndex: 1,
};

/** Every route an admin bearer can use, with a body that would succeed. */
function adminRoutes(targetDeviceId: string): Route[] {
  return [
    { method: "POST", path: "/pairing/create" },
    { method: "GET", path: "/roster" },
    { method: "POST", path: `/roster/${targetDeviceId}/approve` },
    { method: "POST", path: `/roster/${targetDeviceId}/revoke` },
    {
      method: "PUT",
      path: `/roster/${targetDeviceId}/role`,
      body: { role: "grandparent" },
    },
    { method: "GET", path: "/stations" },
    { method: "PUT", path: "/stations", body: { stations: [station] } },
    { method: "GET", path: "/killswitches" },
    { method: "PUT", path: "/killswitches", body: { sangat: false } },
    { method: "GET", path: "/health" },
  ];
}

function sql<T extends Record<string, SqlStorageValue>>(
  stub: Stub,
  query: string,
  ...bindings: SqlStorageValue[]
): Promise<T[]> {
  return runInDurableObject(stub, (_instance, state) =>
    state.storage.sql.exec<T>(query, ...bindings).toArray(),
  );
}

async function adminBudgetRow(stub: Stub) {
  const rows = await sql<{ windowStart: number; count: number }>(
    stub,
    "SELECT windowStart, count FROM rate_limits WHERE key = 'admin'",
  );
  return rows[0];
}

function spendAdminBudget(stub: Stub, count = ADMIN_BUDGET) {
  return runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec(
      "INSERT OR REPLACE INTO rate_limits (key, windowStart, count) VALUES ('admin', ?, ?)",
      Date.now(),
      count,
    );
  });
}

function seedDevice(
  stub: Stub,
  deviceId: string,
  status: "pending" | "approved" | "revoked",
  sessionToken: string | null = null,
) {
  return seedRoster(stub, {
    deviceId,
    status,
    sessionToken,
    claimedAt: Date.now(),
  });
}

describe("admin request cap (20 per minute)", () => {
  it("serves 20 admin requests, then answers the 21st with 429 and does not perform it", async () => {
    const stub = getFamilyHubStub();
    const route: Route = {
      method: "PUT",
      path: "/killswitches",
      body: { sangat: false },
    };

    for (let i = 1; i <= ADMIN_BUDGET; i++) {
      const response = await send(
        stub,
        { method: "GET", path: "/roster" },
        TEST_ADMIN_KEY,
      );
      expect(response.status, `request ${i}`).toBe(200);
    }
    const response = await send(stub, route, TEST_ADMIN_KEY);

    expect(response.status).toBe(429);
    expect(
      await sql(stub, "SELECT enabled FROM killswitches WHERE key = 'sangat'"),
    ).toEqual([{ enabled: 1 }]);
    expect((await adminBudgetRow(stub)).count).toBe(ADMIN_BUDGET);
  });

  it("is one shared budget across every admin route", async () => {
    const stub = getFamilyHubStub();
    const target = "10000000-0000-4000-8000-000000000001";
    await seedDevice(stub, target, "pending");
    const routes = adminRoutes(target);

    // 20 admin requests spread over different routes (cycled)...
    for (let i = 0; i < ADMIN_BUDGET; i++) {
      const response = await send(
        stub,
        { method: "GET", path: i % 2 === 0 ? "/roster" : "/health" },
        TEST_ADMIN_KEY,
      );
      expect(response.status, `request ${i + 1}`).toBe(200);
    }

    // ...leave none for any other.
    for (const route of routes) {
      const response = await send(stub, route, TEST_ADMIN_KEY);
      expect(response.status, `${route.method} ${route.path}`).toBe(429);
    }
  });

  it("performs none of the mutating admin actions once the budget is spent", async () => {
    const stub = getFamilyHubStub();
    const pending = "10000000-0000-4000-8000-000000000002";
    const approved = "10000000-0000-4000-8000-000000000003";
    const sessionToken = "ab".repeat(32);
    await seedDevice(stub, pending, "pending");
    await seedDevice(stub, approved, "approved", sessionToken);
    const tokensBefore = await sql(stub, "SELECT * FROM pairing_tokens");
    await spendAdminBudget(stub);

    for (const route of adminRoutes(pending).concat([
      { method: "POST", path: `/roster/${approved}/revoke` },
    ])) {
      const response = await send(stub, route, TEST_ADMIN_KEY);
      expect(response.status, `${route.method} ${route.path}`).toBe(429);
    }

    expect(await sql(stub, "SELECT * FROM pairing_tokens")).toEqual(
      tokensBefore,
    );
    expect(
      await sql(
        stub,
        "SELECT deviceId, status, sessionTokenHash FROM roster WHERE deviceId IN (?, ?) ORDER BY deviceId",
        pending,
        approved,
      ),
    ).toEqual([
      { deviceId: pending, status: "pending", sessionTokenHash: null },
      {
        deviceId: approved,
        status: "approved",
        sessionTokenHash: await sha256Hex(sessionToken),
      },
    ]);
    expect(await sql(stub, "SELECT * FROM stations")).toEqual([]);
    expect(
      await sql(stub, "SELECT enabled FROM killswitches WHERE key = 'sangat'"),
    ).toEqual([{ enabled: 1 }]);
  });

  it("keeps refusing until a full minute has passed, then starts a fresh window", async () => {
    const stub = getFamilyHubStub();
    const roster: Route = { method: "GET", path: "/roster" };
    await spendAdminBudget(stub);
    expect((await send(stub, roster, TEST_ADMIN_KEY)).status).toBe(429);

    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "UPDATE rate_limits SET windowStart = ? WHERE key = 'admin'",
        Date.now() - (ADMIN_WINDOW_MS - 5_000),
      );
    });
    expect((await send(stub, roster, TEST_ADMIN_KEY)).status).toBe(429);

    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "UPDATE rate_limits SET windowStart = ? WHERE key = 'admin'",
        Date.now() - ADMIN_WINDOW_MS,
      );
    });
    expect((await send(stub, roster, TEST_ADMIN_KEY)).status).toBe(200);
    expect((await adminBudgetRow(stub)).count).toBe(1);

    for (let i = 2; i <= ADMIN_BUDGET; i++) {
      expect((await send(stub, roster, TEST_ADMIN_KEY)).status).toBe(200);
    }
    expect((await send(stub, roster, TEST_ADMIN_KEY)).status).toBe(429);
  });

  describe("requests that are not authenticated as admin", () => {
    it("get 401 and never spend the admin budget, whatever the bearer", async () => {
      const stub = getFamilyHubStub();
      const target = "10000000-0000-4000-8000-000000000004";
      const pendingDevice = "10000000-0000-4000-8000-000000000005";
      const deviceToken = "cd".repeat(31) + "ef";
      const revokedToken = "ef".repeat(32);
      await seedDevice(stub, target, "pending");
      await seedDevice(stub, pendingDevice, "pending");
      await seedDevice(stub, "10000000-0000-4000-8000-000000000009", "revoked");
      await seedDevice(
        stub,
        "10000000-0000-4000-8000-000000000006",
        "approved",
        deviceToken,
      );
      // /stations and /killswitches also accept a device, so those two GETs
      // are 200 for the device token; every other admin route is 401.
      const deviceAllowed = new Set(["GET /stations", "GET /killswitches"]);
      const bearers: Array<[string, string | null]> = [
        ["no Authorization header", null],
        ["a wrong bearer", WRONG_BEARER],
        ["a pending device's id", pendingDevice],
        ["a revoked device's former token", revokedToken],
      ];

      // Well over the 20 budget, so a leak would show as a 429 or a count.
      for (let round = 0; round < 3; round++) {
        for (const [label, token] of bearers) {
          for (const route of adminRoutes(target)) {
            const response = await send(stub, route, token);
            expect(response.status, `${label}: ${route.method} ${route.path}`).toBe(
              401,
            );
          }
        }
        for (const route of adminRoutes(target)) {
          const response = await send(stub, route, deviceToken);
          const expected = deviceAllowed.has(`${route.method} ${route.path}`)
            ? 200
            : 401;
          expect(response.status, `device: ${route.method} ${route.path}`).toBe(
            expected,
          );
        }
      }

      expect(await adminBudgetRow(stub)).toBeUndefined();
      for (let i = 1; i <= ADMIN_BUDGET; i++) {
        const response = await send(
          stub,
          { method: "GET", path: "/roster" },
          TEST_ADMIN_KEY,
        );
        expect(response.status, `admin request ${i}`).toBe(200);
      }
    });

    it("are not turned into 429 by a spent budget: bad bearers stay 401", async () => {
      const stub = getFamilyHubStub();
      await spendAdminBudget(stub);

      for (const token of [null, WRONG_BEARER]) {
        const response = await send(
          stub,
          { method: "GET", path: "/roster" },
          token,
        );
        expect(response.status).toBe(401);
      }
      expect((await adminBudgetRow(stub)).count).toBe(ADMIN_BUDGET);
    });
  });

  describe("device-role requests", () => {
    it("are unaffected by a spent admin budget and do not count against it", async () => {
      const stub = getFamilyHubStub();
      const deviceId = "10000000-0000-4000-8000-000000000007";
      const sessionToken = "12".repeat(32);
      await seedDevice(stub, deviceId, "approved", sessionToken);
      await spendAdminBudget(stub);
      const before = await adminBudgetRow(stub);

      for (let i = 0; i < ADMIN_BUDGET + 5; i++) {
        for (const path of ["/stations", "/killswitches"]) {
          const response = await send(
            stub,
            { method: "GET", path },
            sessionToken,
          );
          expect(response.status, `device GET ${path} #${i}`).toBe(200);
        }
      }
      const report = await send(
        stub,
        {
          method: "POST",
          path: "/health",
          body: {
            deviceId,
            versionCode: 3902,
            versionName: "3.9.2-saraswati",
            timezone: "Asia/Kolkata",
          },
        },
        sessionToken,
      );

      expect(report.status).toBe(204);
      expect(await adminBudgetRow(stub)).toEqual(before);
    });

    it("still get 401, not 429, on an admin-only route while the budget is spent", async () => {
      const stub = getFamilyHubStub();
      const sessionToken = "34".repeat(32);
      await seedDevice(
        stub,
        "10000000-0000-4000-8000-000000000008",
        "approved",
        sessionToken,
      );
      await spendAdminBudget(stub);

      const response = await send(
        stub,
        { method: "PUT", path: "/stations", body: { stations: [station] } },
        sessionToken,
      );

      expect(response.status).toBe(401);
    });

    it("the admin key sent to the device-only POST /health is 401 and spends nothing", async () => {
      const stub = getFamilyHubStub();

      const response = await send(
        stub,
        {
          method: "POST",
          path: "/health",
          body: {
            deviceId: "x",
            versionCode: 1,
            versionName: "1",
            timezone: "Asia/Kolkata",
          },
        },
        TEST_ADMIN_KEY,
      );

      expect(response.status).toBe(401);
      expect(await adminBudgetRow(stub)).toBeUndefined();
    });
  });
});
