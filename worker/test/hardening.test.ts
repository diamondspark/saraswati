import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  type Stub,
  TEST_ADMIN_KEY,
  adminHeaders,
  claimDevice,
  claimWithToken,
  getFamilyHubStub,
  mintPairingToken,
  seedPendingDevice,
} from "./helpers";

const HOUR_MS = 60 * 60 * 1000;

type Authenticator = {
  authenticate(request: Request, env: unknown): Promise<unknown>;
};

function authenticateWith(stub: Stub, adminKey: unknown, bearer: string) {
  return runInDurableObject(stub, (instance) =>
    (instance as unknown as Authenticator).authenticate(
      new Request("https://example.com/roster", {
        headers: { Authorization: `Bearer ${bearer}` },
      }),
      { ...env, ADMIN_KEY: adminKey },
    ),
  );
}

function approve(stub: Stub, deviceId: string) {
  return stub.fetch(`https://example.com/roster/${deviceId}/approve`, {
    method: "POST",
    headers: adminHeaders,
  });
}

function rosterEntry(stub: Stub, deviceId: string) {
  return stub
    .fetch("https://example.com/roster", { headers: adminHeaders })
    .then((r) => r.json() as Promise<{ devices: Array<Record<string, unknown>> }>)
    .then(({ devices }) => devices.find((d) => d.deviceId === deviceId));
}

describe("approving a pending request", () => {
  it("works within the first hour", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e0000000-0000-4000-8000-000000000001";
    await seedPendingDevice(stub, deviceId, "Fresh", Date.now() - (HOUR_MS - 60_000));

    const response = await approve(stub, deviceId);

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ deviceId, status: "approved" });
  });

  it("answers 410 {error: expired} once it is older than an hour, and approves nothing", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e0000000-0000-4000-8000-000000000002";
    await seedPendingDevice(stub, deviceId, "Stale", Date.now() - HOUR_MS - 1_000);

    const response = await approve(stub, deviceId);

    expect(response.status).toBe(410);
    expect(await response.json()).toEqual({ error: "expired" });
    expect((await rosterEntry(stub, deviceId))?.status).toBe("pending");
    const stored = await runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql
          .exec<{ sessionTokenHash: string | null; pendingSessionToken: string | null }>(
            "SELECT sessionTokenHash, pendingSessionToken FROM roster WHERE deviceId = ?",
            deviceId,
          )
          .one(),
    );
    expect(stored).toEqual({ sessionTokenHash: null, pendingSessionToken: null });
  });

  it("treats a pending row with no claimedAt as expired, never as fresh", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e0000000-0000-4000-8000-000000000003";
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec(
        "INSERT INTO roster (deviceId, deviceName, status, claimedAt) VALUES (?, 'Undated', 'pending', NULL)",
        deviceId,
      );
    });

    expect((await approve(stub, deviceId)).status).toBe(410);
  });

  it("stays visible in the roster listing, with claimedAt in epoch ms so the app can show its age", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e0000000-0000-4000-8000-000000000004";
    const claimedAt = Date.now() - 2 * HOUR_MS;
    await seedPendingDevice(stub, deviceId, "Old but listed", claimedAt);

    const entry = await rosterEntry(stub, deviceId);

    expect(entry).toMatchObject({
      deviceId,
      deviceName: "Old but listed",
      status: "pending",
      claimedAt,
    });
  });

  it("lists claimedAt for a freshly claimed device", async () => {
    const stub = getFamilyHubStub();
    const before = Date.now();
    const { deviceId } = await claimDevice(stub);

    const entry = await rosterEntry(stub, deviceId);

    expect(entry?.claimedAt).toBeGreaterThanOrEqual(before);
    expect(entry?.claimedAt).toBeLessThanOrEqual(Date.now());
  });

  it("can still be revoked after it has expired", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "e0000000-0000-4000-8000-000000000005";
    await seedPendingDevice(stub, deviceId, "Stale", Date.now() - 3 * HOUR_MS);
    expect((await approve(stub, deviceId)).status).toBe(410);

    const revoke = await stub.fetch(
      `https://example.com/roster/${deviceId}/revoke`,
      { method: "POST", headers: adminHeaders },
    );

    expect(revoke.status).toBe(200);
    expect((await rosterEntry(stub, deviceId))?.status).toBe("revoked");
  });

  it("still answers 404 for unknown, already-approved and revoked ids, expired or not", async () => {
    const stub = getFamilyHubStub();
    const approved = "e0000000-0000-4000-8000-000000000006";
    await seedPendingDevice(stub, approved, "Approved", Date.now());
    expect((await approve(stub, approved)).status).toBe(200);
    expect((await approve(stub, approved)).status).toBe(404);

    const revoked = "e0000000-0000-4000-8000-000000000007";
    await seedPendingDevice(stub, revoked, "Revoked", Date.now() - 5 * HOUR_MS);
    await stub.fetch(`https://example.com/roster/${revoked}/revoke`, {
      method: "POST",
      headers: adminHeaders,
    });
    expect((await approve(stub, revoked)).status).toBe(404);
    expect((await approve(stub, "no-such-device")).status).toBe(404);
  });

  it("is not blocked by an expired entry sitting next to a fresh one", async () => {
    const stub = getFamilyHubStub();
    const stale = "e0000000-0000-4000-8000-000000000008";
    await seedPendingDevice(stub, stale, "Stale", Date.now() - 2 * HOUR_MS);
    const fresh = await claimDevice(stub);

    expect((await approve(stub, stale)).status).toBe(410);
    expect((await approve(stub, fresh.deviceId)).status).toBe(200);
  });
});

describe("the admin key", () => {
  it("is accepted when it is the configured 64-character key", async () => {
    const stub = getFamilyHubStub();

    expect(await authenticateWith(stub, TEST_ADMIN_KEY, TEST_ADMIN_KEY)).toEqual({
      role: "admin",
    });
  });

  it("is accepted at exactly 32 characters", async () => {
    const stub = getFamilyHubStub();
    const key = "k".repeat(32);

    expect(await authenticateWith(stub, key, key)).toEqual({ role: "admin" });
  });

  it.each([
    ["31 characters", "k".repeat(31)],
    ["a short word", "admin"],
    ["one character", "x"],
    ["an empty string", ""],
    ["undefined", undefined],
    ["null", null],
    ["a number", 12345678901234567890123456789012],
  ])("is treated as unset when the configured key is %s", async (_label, key) => {
    const stub = getFamilyHubStub();
    const bearer = typeof key === "string" && key !== "" ? key : "anything";

    expect(await authenticateWith(stub, key, bearer)).toBeNull();
  });

  it("is rejected for a wrong key of the same length, one differing in the last character and a prefix", async () => {
    const stub = getFamilyHubStub();

    for (const wrong of [
      "f".repeat(64),
      TEST_ADMIN_KEY.slice(0, 63) + "0",
      TEST_ADMIN_KEY.slice(0, 63),
      TEST_ADMIN_KEY + "0",
      TEST_ADMIN_KEY.toUpperCase(),
    ]) {
      expect(await authenticateWith(stub, TEST_ADMIN_KEY, wrong), wrong).toBeNull();
    }
  });

  it("fails every admin route closed with 401 while the configured key is too short", async () => {
    const stub = getFamilyHubStub();
    const shortKey = "short-key";

    const results = await runInDurableObject(stub, async (instance) => {
      const hub = instance as unknown as { env: Record<string, unknown> };
      const original = hub.env;
      Object.defineProperty(hub, "env", {
        value: { ...original, ADMIN_KEY: shortKey },
        configurable: true,
        writable: true,
      });
      try {
        const out: Array<[string, number]> = [];
        for (const [method, path] of [
          ["POST", "/pairing/create"],
          ["GET", "/roster"],
          ["GET", "/stations"],
          ["GET", "/killswitches"],
          ["GET", "/health"],
        ]) {
          const response = await (instance as unknown as {
            fetch(request: Request): Promise<Response>;
          }).fetch(
            new Request(`https://example.com${path}`, {
              method,
              headers: { Authorization: `Bearer ${shortKey}` },
            }),
          );
          out.push([`${method} ${path}`, response.status]);
        }
        return out;
      } finally {
        Object.defineProperty(hub, "env", {
          value: original,
          configurable: true,
          writable: true,
        });
      }
    });

    for (const [route, status] of results) {
      expect(status, route).toBe(401);
    }
  });
});

describe("deviceName on POST /pairing/claim", () => {
  async function storedName(stub: Stub, deviceId: string) {
    return runInDurableObject(
      stub,
      (_instance, state) =>
        state.storage.sql
          .exec<{ deviceName: string | null }>(
            "SELECT deviceName FROM roster WHERE deviceId = ?",
            deviceId,
          )
          .one().deviceName,
    );
  }

  async function claimNamed(stub: Stub, deviceName: unknown) {
    const response = await claimWithToken(stub, await mintPairingToken(stub), {
      deviceName,
    });
    return response;
  }

  it("keeps an ordinary name as it is, Devanagari and emoji included", async () => {
    const stub = getFamilyHubStub();
    for (const name of ["Mummy's Pixel", "दादी का फ़ोन", "Papa 📱"]) {
      const response = await claimNamed(stub, name);
      expect(response.status, name).toBe(200);
      const { deviceId } = (await response.json()) as { deviceId: string };
      expect(await storedName(stub, deviceId)).toBe(name);
    }
  });

  it("keeps ZWJ and ZWNJ, which Devanagari and emoji need, and the names around them", async () => {
    const stub = getFamilyHubStub();
    const names = [
      "\u0926\u093E\u0926\u0940 \u0915\u093E \u095E\u094B\u0928", // composed form of the Hindi "phone"
      "\u0926\u093E\u0926\u0940 \u0915\u093E \u092B\u093C\u094B\u0928", // the same with a separate nukta
      "\u0915\u094D\u200D\u0937", // virama + ZWJ (half form)
      "\u0915\u094D\u200C\u0937", // virama + ZWNJ (explicit virama)
      "\u{1F468}\u200D\u{1F469}\u200D\u{1F467} Family phone", // ZWJ emoji sequence
    ];
    for (const name of names) {
      const response = await claimNamed(stub, name);
      expect(response.status, name).toBe(200);
      const { deviceId } = (await response.json()) as { deviceId: string };
      expect(await storedName(stub, deviceId)).toBe(name);
    }
  });

  it("still strips every bidi control, even next to ZWJ and ZWNJ", async () => {
    const stub = getFamilyHubStub();
    const bidi = [
      "\u200E",
      "\u200F",
      "\u202A",
      "\u202B",
      "\u202C",
      "\u202D",
      "\u202E",
      "\u2066",
      "\u2067",
      "\u2068",
      "\u2069",
      "\u061C",
    ].join("");
    const name = `${bidi}\u0915\u094D${bidi}\u200D${bidi}\u0937${bidi}`;

    const response = await claimNamed(stub, name);

    expect(response.status).toBe(200);
    const { deviceId } = (await response.json()) as { deviceId: string };
    expect(await storedName(stub, deviceId)).toBe("\u0915\u094D\u200D\u0937");
  });

  it("caps the name at 64 characters, counting characters and not UTF-16 units", async () => {
    const stub = getFamilyHubStub();

    const long = await claimNamed(stub, "a".repeat(200));
    const { deviceId: longId } = (await long.json()) as { deviceId: string };
    expect(await storedName(stub, longId)).toBe("a".repeat(64));

    const emoji = await claimNamed(stub, "📱".repeat(100));
    const { deviceId: emojiId } = (await emoji.json()) as { deviceId: string };
    expect(await storedName(stub, emojiId)).toBe("📱".repeat(64));

    const exact = await claimNamed(stub, "b".repeat(64));
    const { deviceId: exactId } = (await exact.json()) as { deviceId: string };
    expect(await storedName(stub, exactId)).toBe("b".repeat(64));
  });

  it("strips control characters, newlines and bidi overrides", async () => {
    const stub = getFamilyHubStub();
    const dirty =
      "Ma\u0000m\u0007my\n\r\t\u202E\u2066's\u200B \u200FPixel\u202C\u007F\u0085";

    const response = await claimNamed(stub, dirty);

    expect(response.status).toBe(200);
    const { deviceId } = (await response.json()) as { deviceId: string };
    expect(await storedName(stub, deviceId)).toBe("Mammy's Pixel");
  });

  it("strips before capping, so hidden characters never use up the 64", async () => {
    const stub = getFamilyHubStub();
    const name = "\u202E".repeat(80) + "c".repeat(64);

    const response = await claimNamed(stub, name);

    const { deviceId } = (await response.json()) as { deviceId: string };
    expect(await storedName(stub, deviceId)).toBe("c".repeat(64));
  });

  it.each([
    ["an empty string", ""],
    ["only spaces", "   "],
    ["only control characters", "\u0000\u0001\n\t"],
    ["only bidi overrides", "\u202E\u202D\u2066\u2069"],
    ["only invisible format characters", "\u200B\u200C\u200D\u2060\uFEFF"],
    ["only ZWJ and ZWNJ", "\u200D\u200C \u200D"],
    ["64 ZWJ and then a letter, which the cap cuts down to 64 ZWJ", "\u200D".repeat(64) + "A"],
    ["70 ZWNJ and then a letter", "\u200C".repeat(70) + "A"],
    ["joiners and spaces that fill the cap before the first letter", "\u200D ".repeat(40) + "A"],
    ["only bidi controls", "\u200E\u200F\u061C\u202A\u202E\u2066\u2069"],
  ])("answers 400 for %s, without burning the pairing token", async (_label, name) => {
    const stub = getFamilyHubStub();
    const token = await mintPairingToken(stub);

    const rejected = await claimWithToken(stub, token, { deviceName: name });

    expect(rejected.status).toBe(400);
    const retry = await claimWithToken(stub, token, { deviceName: "Fine" });
    expect(retry.status).toBe(200);
  });

  it("keeps a name whose visible letters are inside the cap", async () => {
    const stub = getFamilyHubStub();
    const name = "\u200D".repeat(63) + "A";

    const response = await claimNamed(stub, name);

    expect(response.status).toBe(200);
    const { deviceId } = (await response.json()) as { deviceId: string };
    expect(await storedName(stub, deviceId)).toBe(name);
  });

  it("still accepts a claim that sends no deviceName at all", async () => {
    const stub = getFamilyHubStub();
    const token = await mintPairingToken(stub);

    const response = await stub.fetch("https://example.com/pairing/claim", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token }),
    });

    expect(response.status).toBe(200);
    const { deviceId } = (await response.json()) as { deviceId: string };
    expect(await storedName(stub, deviceId)).toBeNull();
  });
});

describe("a malformed percent-escape in a roster path", () => {
  const BAD = "%E0%A4%A";

  it.each([
    ["POST", "approve"],
    ["POST", "revoke"],
    ["PUT", "role"],
  ])("%s /roster/{bad}/%s answers 404 and not 500, with or without auth", async (method, action) => {
    const stub = getFamilyHubStub();

    for (const headers of [adminHeaders, {}]) {
      const response = await stub.fetch(
        `https://example.com/roster/${BAD}/${action}`,
        { method, headers, body: method === "PUT" ? '{"role":"adult"}' : undefined },
      );

      expect(response.status, `${method} ${action}`).toBe(404);
    }
  });

  it("still decodes a well-formed escape", async () => {
    const stub = getFamilyHubStub();
    const deviceId = "device with space";
    await seedPendingDevice(stub, deviceId, "Spaced", Date.now());

    const response = await stub.fetch(
      `https://example.com/roster/${encodeURIComponent(deviceId)}/approve`,
      { method: "POST", headers: adminHeaders },
    );

    expect(response.status).toBe(200);
  });
});
