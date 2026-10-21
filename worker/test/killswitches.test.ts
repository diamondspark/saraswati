import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { FamilyHub } from "../src/FamilyHub";
import {
  type Stub,
  TEST_ADMIN_KEY,
  approveAndGetSessionToken,
  getFamilyHubStub,
  seedPendingDevice,
} from "./helpers";

const WRONG_BEARER = "cd".repeat(32);

const ALL_ENABLED = {
  sangat: true,
  parivaar_mix: true,
  family_playlists: true,
  cast_relay: true,
  ask_saraswati: true,
  feedback: true,
};

function putKillSwitches(
  stub: Stub,
  body: unknown,
  headers: Record<string, string> = {
    Authorization: `Bearer ${TEST_ADMIN_KEY}`,
    "content-type": "application/json",
  },
) {
  return stub.fetch("https://example.com/killswitches", {
    method: "PUT",
    headers,
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

function getKillSwitches(stub: Stub, token?: string) {
  return stub.fetch("https://example.com/killswitches", {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
}

async function adminKillSwitches(stub: Stub) {
  const response = await getKillSwitches(stub, TEST_ADMIN_KEY);
  expect(response.status).toBe(200);
  return (await response.json()) as Record<string, boolean>;
}

async function resetAllEnabled(stub: Stub) {
  const response = await putKillSwitches(stub, ALL_ENABLED);
  expect(response.status).toBe(200);
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

describe("GET /killswitches", () => {
  it("returns all six keys enabled before any PUT", async () => {
    const stub = getFamilyHubStub();

    const response = await getKillSwitches(stub, TEST_ADMIN_KEY);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual(ALL_ENABLED);
  });

  it("GET lists six keys with ask_saraswati and feedback enabled by default", async () => {
    const stub = getFamilyHubStub();
    const deviceToken = await approvedDeviceToken(
      stub,
      "e4444444-4444-4444-4444-444444444444",
    );

    for (const token of [TEST_ADMIN_KEY, deviceToken]) {
      const body = (await (await getKillSwitches(stub, token)).json()) as Record<string, boolean>;
      expect(Object.keys(body)).toEqual([
        "sangat",
        "parivaar_mix",
        "family_playlists",
        "cast_relay",
        "ask_saraswati",
        "feedback",
      ]);
      expect(body.ask_saraswati).toBe(true);
      expect(body.feedback).toBe(true);
    }
  });

  it("a hub from before ask_saraswati gains the key, switched on, when it starts", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);
    // Starting the class again over the same storage is what a deploy does to a live hub.
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec("DELETE FROM killswitches WHERE key = 'ask_saraswati'");
      new FamilyHub(state, env as never);
    });

    expect(await adminKillSwitches(stub)).toEqual(ALL_ENABLED);
    // The row exists again, so a switch-off sticks instead of being a silent no-op.
    expect((await putKillSwitches(stub, { ask_saraswati: false })).status).toBe(200);
    expect(await adminKillSwitches(stub)).toEqual({ ...ALL_ENABLED, ask_saraswati: false });
    await resetAllEnabled(stub);
  });

  it("returns 401 with no Authorization header", async () => {
    const stub = getFamilyHubStub();

    const response = await getKillSwitches(stub);

    expect(response.status).toBe(401);
  });

  it("returns 401 for a bearer that is neither the admin key nor a session token", async () => {
    const stub = getFamilyHubStub();

    const response = await getKillSwitches(stub, WRONG_BEARER);

    expect(response.status).toBe(401);
  });

  it("returns 401 when a claimed-but-pending device presents its deviceId as a bearer", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e1111111-1111-1111-1111-111111111111";
    await seedPendingDevice(stub, deviceId, "Still pending");

    const response = await getKillSwitches(stub, deviceId);

    expect(response.status).toBe(401);
  });

  it("returns 401 for a revoked device's former session token", async () => {
    const stub = getFamilyHubStub();
    const token = await revokedDeviceToken(
      stub,
      "e2222222-2222-2222-2222-222222222222",
    );

    const response = await getKillSwitches(stub, token);

    expect(response.status).toBe(401);
  });

  it("returns the current map to an approved device and to the admin", async () => {
    const stub = getFamilyHubStub();
    const deviceToken = await approvedDeviceToken(
      stub,
      "e3333333-3333-3333-3333-333333333333",
    );
    await resetAllEnabled(stub);
    expect((await putKillSwitches(stub, { cast_relay: false })).status).toBe(
      200,
    );
    const expected = { ...ALL_ENABLED, cast_relay: false };

    const asDevice = await getKillSwitches(stub, deviceToken);
    const asAdmin = await getKillSwitches(stub, TEST_ADMIN_KEY);

    expect(asDevice.status).toBe(200);
    expect(await asDevice.json()).toEqual(expected);
    expect(asAdmin.status).toBe(200);
    expect(await asAdmin.json()).toEqual(expected);
  });
});

describe("PUT /killswitches", () => {
  it("returns 401 with no Authorization header, and changes nothing", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);

    const response = await putKillSwitches(
      stub,
      { sangat: false },
      { "content-type": "application/json" },
    );

    expect(response.status).toBe(401);
    expect(await adminKillSwitches(stub)).toEqual(ALL_ENABLED);
  });

  it("returns 401 for a wrong bearer, an approved device's session token, and a revoked one", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);
    const approved = await approvedDeviceToken(
      stub,
      "f1111111-1111-1111-1111-111111111111",
    );
    const revoked = await revokedDeviceToken(
      stub,
      "f2222222-2222-2222-2222-222222222222",
    );

    for (const token of [WRONG_BEARER, approved, revoked]) {
      const response = await putKillSwitches(
        stub,
        { sangat: false },
        {
          Authorization: `Bearer ${token}`,
          "content-type": "application/json",
        },
      );
      expect(response.status).toBe(401);
    }

    expect(await adminKillSwitches(stub)).toEqual(ALL_ENABLED);
  });

  it("changes only the key in a partial map and returns the full map", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);

    const response = await putKillSwitches(stub, { sangat: false });

    expect(response.status).toBe(200);
    const expected = { ...ALL_ENABLED, sangat: false };
    expect(await response.json()).toEqual(expected);
    expect(await adminKillSwitches(stub)).toEqual(expected);
  });

  it("accumulates across PUTs and can turn a key back on", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);

    expect((await putKillSwitches(stub, { sangat: false })).status).toBe(200);
    expect(
      (await putKillSwitches(stub, { parivaar_mix: false, cast_relay: false }))
        .status,
    ).toBe(200);
    expect(await adminKillSwitches(stub)).toEqual({
      sangat: false,
      parivaar_mix: false,
      family_playlists: true,
      cast_relay: false,
      ask_saraswati: true,
      feedback: true,
    });

    expect((await putKillSwitches(stub, { sangat: true })).status).toBe(200);
    expect(await adminKillSwitches(stub)).toEqual({
      sangat: true,
      parivaar_mix: false,
      family_playlists: true,
      cast_relay: false,
      ask_saraswati: true,
      feedback: true,
    });
  });

  it("switches ask_saraswati off and on without touching the others", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);

    const off = await putKillSwitches(stub, { ask_saraswati: false });

    expect(off.status).toBe(200);
    expect(await off.json()).toEqual({ ...ALL_ENABLED, ask_saraswati: false });
    expect(await adminKillSwitches(stub)).toEqual({ ...ALL_ENABLED, ask_saraswati: false });
    expect((await putKillSwitches(stub, { ask_saraswati: true })).status).toBe(200);
    expect(await adminKillSwitches(stub)).toEqual(ALL_ENABLED);
  });

  it("treats an empty map as a no-op that returns the full map", async () => {
    const stub = getFamilyHubStub();
    await resetAllEnabled(stub);
    expect((await putKillSwitches(stub, { cast_relay: false })).status).toBe(
      200,
    );

    const response = await putKillSwitches(stub, {});

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      ...ALL_ENABLED,
      cast_relay: false,
    });
  });

  const rejectedBodies: Array<[string, unknown]> = [
    ["an unknown key", { bogus: false }],
    ["ask_saraswati with a string", { ask_saraswati: "false" }],
    ["a near miss of ask_saraswati", { ask_saraswati_: false }],
    ["an unknown key alongside a valid one", { sangat: false, bogus: true }],
    ["a key differing only in case", { Sangat: false }],
    ["a non-boolean value (string)", { sangat: "false" }],
    ["a non-boolean value (number)", { sangat: 0 }],
    ["a null value", { sangat: null }],
    ["a valid change alongside a non-boolean", { sangat: false, cast_relay: 1 }],
    ["a JSON array", [{ sangat: false }]],
    ["a null body", null],
    ["a bare boolean body", true],
    ["invalid JSON", "{not json"],
  ];

  it.each(rejectedBodies)(
    "rejects %s with 400 and changes nothing",
    async (_label, body) => {
      const stub = getFamilyHubStub();
      await resetAllEnabled(stub);

      const response = await putKillSwitches(stub, body);

      expect(response.status).toBe(400);
      expect(await adminKillSwitches(stub)).toEqual(ALL_ENABLED);
    },
  );
});
