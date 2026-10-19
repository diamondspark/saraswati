import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const WRONG_BEARER = "cd".repeat(32);

interface Station {
  id: string;
  title: string;
  seedVideoId: string;
  iconIndex: number;
}

function getFamilyHubStub() {
  const id = env.FAMILY_HUB.idFromName("family");
  return env.FAMILY_HUB.get(id);
}

type Stub = ReturnType<typeof getFamilyHubStub>;

function station(n: number): Station {
  return {
    id: `station-${n}`,
    title: `Station ${n}`,
    seedVideoId: `video${String(n).padStart(6, "0")}`,
    iconIndex: n % 4,
  };
}

function stationList(count: number): Station[] {
  return Array.from({ length: count }, (_, i) => station(i + 1));
}

function adminHeaders(): Record<string, string> {
  return {
    Authorization: `Bearer ${TEST_ADMIN_KEY}`,
    "content-type": "application/json",
  };
}

function putStations(stub: Stub, body: unknown, headers = adminHeaders()) {
  return stub.fetch("https://example.com/stations", {
    method: "PUT",
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

function getStations(stub: Stub, token?: string) {
  return stub.fetch("https://example.com/stations", {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
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

async function approvedDeviceToken(stub: Stub, deviceId: string) {
  await seedPendingDevice(stub, deviceId, `Device ${deviceId}`);
  return approveAndGetSessionToken(stub, deviceId);
}

async function revokedDeviceToken(stub: Stub, deviceId: string) {
  const token = await approvedDeviceToken(stub, deviceId);
  const revoke = await stub.fetch(
    `https://example.com/roster/${deviceId}/revoke`,
    { method: "POST", headers: { Authorization: `Bearer ${TEST_ADMIN_KEY}` } },
  );
  expect(revoke.status).toBe(200);
  return token;
}

async function adminStations(stub: Stub): Promise<Station[]> {
  const response = await getStations(stub, TEST_ADMIN_KEY);
  expect(response.status).toBe(200);
  return ((await response.json()) as { stations: Station[] }).stations;
}

describe("GET /stations", () => {
  it("returns an empty list before the owner has set any stations", async () => {
    const stub = getFamilyHubStub();

    const response = await getStations(stub, TEST_ADMIN_KEY);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ stations: [] });
  });

  it("returns 401 with no Authorization header", async () => {
    const stub = getFamilyHubStub();

    const response = await getStations(stub);

    expect(response.status).toBe(401);
  });

  it("returns 401 for a bearer that is neither the admin key nor a session token", async () => {
    const stub = getFamilyHubStub();

    const response = await getStations(stub, WRONG_BEARER);

    expect(response.status).toBe(401);
  });

  it("returns 401 when a claimed-but-pending device presents its deviceId as a bearer", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "c1111111-1111-1111-1111-111111111111";
    await seedPendingDevice(stub, deviceId, "Still pending");

    const response = await getStations(stub, deviceId);

    expect(response.status).toBe(401);
  });

  it("returns 401 for a revoked device's former session token", async () => {
    const stub = getFamilyHubStub();
    const token = await revokedDeviceToken(
      stub,
      "c2222222-2222-2222-2222-222222222222",
    );

    const response = await getStations(stub, token);

    expect(response.status).toBe(401);
  });

  it("returns the stations in position order to an approved device and to the admin", async () => {
    const stub = getFamilyHubStub();
    const deviceToken = await approvedDeviceToken(
      stub,
      "c3333333-3333-3333-3333-333333333333",
    );
    const stations = stationList(4);
    const put = await putStations(stub, { stations });
    expect(put.status).toBe(200);

    const asDevice = await getStations(stub, deviceToken);
    const asAdmin = await getStations(stub, TEST_ADMIN_KEY);

    expect(asDevice.status).toBe(200);
    expect(await asDevice.json()).toEqual({ stations });
    expect(asAdmin.status).toBe(200);
    expect(await asAdmin.json()).toEqual({ stations });
  });
});

describe("PUT /stations", () => {
  it("returns 401 with no Authorization header, and leaves the stored list untouched", async () => {
    const stub = getFamilyHubStub();
    const original = stationList(2);
    expect((await putStations(stub, { stations: original })).status).toBe(200);

    const response = await putStations(
      stub,
      { stations: stationList(5) },
      { "content-type": "application/json" },
    );

    expect(response.status).toBe(401);
    expect(await adminStations(stub)).toEqual(original);
  });

  it("returns 401 for a wrong bearer, an approved device's session token, and a revoked one", async () => {
    const stub = getFamilyHubStub();
    const original = stationList(3);
    expect((await putStations(stub, { stations: original })).status).toBe(200);
    const approved = await approvedDeviceToken(
      stub,
      "d1111111-1111-1111-1111-111111111111",
    );
    const revoked = await revokedDeviceToken(
      stub,
      "d2222222-2222-2222-2222-222222222222",
    );

    for (const token of [WRONG_BEARER, approved, revoked]) {
      const response = await putStations(
        stub,
        { stations: stationList(5) },
        {
          Authorization: `Bearer ${token}`,
          "content-type": "application/json",
        },
      );
      expect(response.status).toBe(401);
    }

    expect(await adminStations(stub)).toEqual(original);
  });

  it.each([1, 6])(
    "replaces the table with a list of %i station(s) and returns it",
    async (count) => {
      const stub = getFamilyHubStub();
      const stations = stationList(count);

      const response = await putStations(stub, { stations });

      expect(response.status).toBe(200);
      expect(await response.json()).toEqual({ stations });
      expect(await adminStations(stub)).toEqual(stations);
    },
  );

  it("replaces the whole previous list rather than merging, and keeps request order", async () => {
    const stub = getFamilyHubStub();
    expect(
      (await putStations(stub, { stations: stationList(5) })).status,
    ).toBe(200);
    const replacement = [station(9), station(2), station(7)];

    const response = await putStations(stub, { stations: replacement });

    expect(response.status).toBe(200);
    expect(await adminStations(stub)).toEqual(replacement);
  });

  it("allows the same id to be re-sent in a later PUT", async () => {
    const stub = getFamilyHubStub();
    expect(
      (await putStations(stub, { stations: stationList(3) })).status,
    ).toBe(200);

    const response = await putStations(stub, { stations: stationList(2) });

    expect(response.status).toBe(200);
    expect(await adminStations(stub)).toEqual(stationList(2));
  });

  it("rejects a 7th station with 400 and leaves the stored list untouched", async () => {
    const stub = getFamilyHubStub();
    const original = stationList(3);
    expect((await putStations(stub, { stations: original })).status).toBe(200);

    const response = await putStations(stub, { stations: stationList(7) });

    expect(response.status).toBe(400);
    expect(await adminStations(stub)).toEqual(original);
  });

  it("drops fields other than id, title, seedVideoId and iconIndex", async () => {
    const stub = getFamilyHubStub();
    const withExtras = { ...station(1), sneaky: "extra", position: 99 };

    const response = await putStations(stub, { stations: [withExtras] });

    expect(response.status).toBe(200);
    expect(await adminStations(stub)).toEqual([station(1)]);
  });

  const malformedBodies: Array<[string, unknown]> = [
    ["invalid JSON", "{not json"],
    ["a JSON array instead of an object", [station(1)]],
    ["a null body", null],
    ["a missing stations key", {}],
    ["stations that is not an array", { stations: station(1) }],
    ["an empty stations array", { stations: [] }],
    ["a non-object entry", { stations: ["station-1"] }],
    ["a null entry", { stations: [null] }],
    ["a missing id", { stations: [{ ...station(1), id: undefined }] }],
    ["an empty id", { stations: [{ ...station(1), id: "" }] }],
    ["a non-string id", { stations: [{ ...station(1), id: 1 }] }],
    ["a missing title", { stations: [{ ...station(1), title: undefined }] }],
    ["an empty title", { stations: [{ ...station(1), title: "" }] }],
    ["a non-string title", { stations: [{ ...station(1), title: 5 }] }],
    [
      "a missing seedVideoId",
      { stations: [{ ...station(1), seedVideoId: undefined }] },
    ],
    [
      "an empty seedVideoId",
      { stations: [{ ...station(1), seedVideoId: "" }] },
    ],
    [
      "a non-string seedVideoId",
      { stations: [{ ...station(1), seedVideoId: 12 }] },
    ],
    [
      "a missing iconIndex",
      { stations: [{ ...station(1), iconIndex: undefined }] },
    ],
    [
      "a fractional iconIndex",
      { stations: [{ ...station(1), iconIndex: 1.5 }] },
    ],
    [
      "a negative iconIndex",
      { stations: [{ ...station(1), iconIndex: -1 }] },
    ],
    [
      "a string iconIndex",
      { stations: [{ ...station(1), iconIndex: "1" }] },
    ],
    ["a duplicate id", { stations: [station(1), station(2), station(1)] }],
  ];

  it.each(malformedBodies)(
    "rejects %s with 400 and leaves the stored list untouched",
    async (_label, body) => {
      const stub = getFamilyHubStub();
      const original = stationList(2);
      expect((await putStations(stub, { stations: original })).status).toBe(
        200,
      );

      const response = await putStations(stub, body);

      expect(response.status).toBe(400);
      expect(await adminStations(stub)).toEqual(original);
    },
  );
});
