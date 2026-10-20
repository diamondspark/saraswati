import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { LATEST_SCHEMA_VERSION, migrate } from "../src/migrations";
import { adminHeaders, sha256Hex } from "./helpers";

// The schema exactly as the live hub (versions up to 4308392a) created it, kept
// here as a literal so the test does not depend on the code it is testing.
const V1_SCHEMA = [
  `CREATE TABLE pairing_tokens (
      token TEXT PRIMARY KEY,
      expiresAt INTEGER,
      usedAt INTEGER
    )`,
  `CREATE TABLE roster (
      deviceId TEXT PRIMARY KEY,
      deviceName TEXT,
      status TEXT,
      sessionToken TEXT,
      approvedAt INTEGER,
      claimSecret TEXT,
      role TEXT NOT NULL DEFAULT 'adult'
    )`,
  `CREATE TABLE stations (
      id TEXT PRIMARY KEY,
      title TEXT,
      seedVideoId TEXT,
      iconIndex INTEGER,
      position INTEGER
    )`,
  `CREATE TABLE killswitches (
      key TEXT PRIMARY KEY,
      enabled INTEGER
    )`,
  `CREATE TABLE health (
      deviceId TEXT PRIMARY KEY,
      versionCode INTEGER,
      versionName TEXT,
      timezone TEXT,
      lastSeenAt INTEGER
    )`,
  `CREATE TABLE rate_limits (
      key TEXT PRIMARY KEY,
      windowStart INTEGER,
      count INTEGER
    )`,
];

const TABLES = [
  "pairing_tokens",
  "roster",
  "stations",
  "killswitches",
  "health",
  "rate_limits",
];

let hubCounter = 0;

/** A hub instance of its own, so one test's tables never leak into another's. */
function freshHub() {
  hubCounter += 1;
  return env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName(`migration-${hubCounter}`));
}

type Stub = ReturnType<typeof freshHub>;

/** Replaces whatever the constructor made with the live, pre-migration shape. */
function resetToV1(
  stub: Stub,
  recordedVersion: 0 | 1,
  seed: (sql: SqlStorage) => void,
) {
  return runInDurableObject(stub, (_instance, state) => {
    const sql = state.storage.sql;
    for (const table of TABLES) {
      sql.exec(`DROP TABLE IF EXISTS ${table}`);
    }
    for (const ddl of V1_SCHEMA) {
      sql.exec(ddl);
    }
    seed(sql);
    // 0 is the hub as it is live today: it never recorded a version at all.
    sql.exec("DROP TABLE IF EXISTS schema_version");
    if (recordedVersion === 1) {
      sql.exec("CREATE TABLE schema_version (version INTEGER NOT NULL)");
      sql.exec("INSERT INTO schema_version (version) VALUES (1)");
    }
  });
}

function runMigrate(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) => migrate(state.storage));
}

function schemaVersion(stub: Stub) {
  return runInDurableObject(
    stub,
    (_instance, state) =>
      state.storage.sql
        .exec<{ version: number }>("SELECT version FROM schema_version")
        .one().version,
  );
}

function dump(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) =>
    Object.fromEntries(
      TABLES.map((table) => [
        table,
        state.storage.sql
          .exec(`SELECT * FROM ${table} ORDER BY rowid`)
          .toArray(),
      ]),
    ),
  );
}

function columns(stub: Stub, table: string) {
  return runInDurableObject(stub, (_instance, state) =>
    state.storage.sql
      .exec<{ name: string }>(`PRAGMA table_info(${table})`)
      .toArray()
      .map((row) => row.name),
  );
}

const UNUSED_PAIRING_TOKEN = "11".repeat(32);
const USED_PAIRING_TOKEN = "22".repeat(32);
const SESSION_APPROVED = "33".repeat(32);
const SESSION_UNCOLLECTED = "44".repeat(32);
const SECRET_PENDING = "55".repeat(32);
const SECRET_UNCOLLECTED = "66".repeat(32);
const SEEDED_AT = 1_700_000_000_000;
const NOW_ISH = Date.now();

function seedLiveData(sql: SqlStorage) {
  sql.exec(
    "INSERT INTO pairing_tokens (token, expiresAt, usedAt) VALUES (?, ?, NULL), (?, ?, ?)",
    UNUSED_PAIRING_TOKEN,
    NOW_ISH + 600_000,
    USED_PAIRING_TOKEN,
    NOW_ISH + 600_000,
    NOW_ISH - 1000,
  );
  sql.exec(
    `INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt, claimSecret, role) VALUES
       ('dev-approved',   'Collected phone',   'approved', ?, ?, NULL, 'grandparent'),
       ('dev-uncollected','Uncollected phone', 'approved', ?, ?, ?,    'adult'),
       ('dev-pending',    'Pending phone',     'pending',  NULL, NULL, ?, 'adult'),
       ('dev-revoked',    'Revoked phone',     'revoked',  NULL, ?, NULL, 'adult')`,
    SESSION_APPROVED,
    SEEDED_AT,
    SESSION_UNCOLLECTED,
    SEEDED_AT + 5,
    SECRET_UNCOLLECTED,
    SECRET_PENDING,
    SEEDED_AT + 9,
  );
  sql.exec(
    "INSERT INTO stations (id, title, seedVideoId, iconIndex, position) VALUES ('s1', 'Bhajan', 'abcdefghijk', 2, 0)",
  );
  sql.exec("INSERT INTO killswitches (key, enabled) VALUES ('sangat', 0)");
  sql.exec(
    "INSERT INTO health (deviceId, versionCode, versionName, timezone, lastSeenAt) VALUES ('dev-approved', 7, '1.0', 'Asia/Kolkata', ?)",
    SEEDED_AT,
  );
  sql.exec(
    "INSERT INTO rate_limits (key, windowStart, count) VALUES (?, ?, 4), ('status:dev-pending', ?, 2), ('admin', ?, 1)",
    `claim:${UNUSED_PAIRING_TOKEN}`,
    NOW_ISH,
    NOW_ISH,
    NOW_ISH,
  );
}

describe("schema migrations", () => {
  it("reports version 2 as the latest and lands a brand-new hub on it", async () => {
    const stub = freshHub();

    // The constructor has already run the migrations on an empty database.
    expect(LATEST_SCHEMA_VERSION).toBe(2);
    expect(await schemaVersion(stub)).toBe(2);
    expect(await columns(stub, "roster")).toEqual([
      "deviceId",
      "deviceName",
      "status",
      "sessionTokenHash",
      "approvedAt",
      "claimSecretHash",
      "role",
      "pendingSessionToken",
      "claimedAt",
    ]);
    expect(await columns(stub, "pairing_tokens")).toEqual([
      "tokenHash",
      "expiresAt",
      "usedAt",
    ]);
    expect(
      await runInDurableObject(
        stub,
        (_instance, state) =>
          state.storage.sql
            .exec<{ n: number }>("SELECT COUNT(*) AS n FROM killswitches")
            .one().n,
      ),
    ).toBe(4);
  });

  for (const startVersion of [0, 1] as const) {
    describe(`from the live v1 schema, recorded version ${startVersion}`, () => {
      it("hashes plaintext tokens in place and drops every plaintext credential", async () => {
        const stub = freshHub();
        await resetToV1(stub, startVersion, seedLiveData);

        await runMigrate(stub);

        expect(await schemaVersion(stub)).toBe(2);
        const tables = await dump(stub);
        const text = JSON.stringify(tables);
        for (const plaintext of [
          UNUSED_PAIRING_TOKEN,
          USED_PAIRING_TOKEN,
          SESSION_APPROVED,
          SESSION_UNCOLLECTED,
          SECRET_PENDING,
          SECRET_UNCOLLECTED,
        ]) {
          expect(text, plaintext).not.toContain(plaintext);
        }

        expect(tables.pairing_tokens).toEqual([
          {
            tokenHash: await sha256Hex(UNUSED_PAIRING_TOKEN),
            expiresAt: NOW_ISH + 600_000,
            usedAt: null,
          },
          {
            tokenHash: await sha256Hex(USED_PAIRING_TOKEN),
            expiresAt: NOW_ISH + 600_000,
            usedAt: NOW_ISH - 1000,
          },
        ]);

        expect(tables.roster).toEqual([
          {
            deviceId: "dev-approved",
            deviceName: "Collected phone",
            status: "approved",
            sessionTokenHash: await sha256Hex(SESSION_APPROVED),
            approvedAt: SEEDED_AT,
            claimSecretHash: null,
            role: "grandparent",
            pendingSessionToken: null,
            claimedAt: SEEDED_AT,
          },
          {
            deviceId: "dev-uncollected",
            deviceName: "Uncollected phone",
            status: "approved",
            sessionTokenHash: await sha256Hex(SESSION_UNCOLLECTED),
            approvedAt: SEEDED_AT + 5,
            claimSecretHash: null,
            role: "adult",
            pendingSessionToken: null,
            claimedAt: SEEDED_AT + 5,
          },
          {
            deviceId: "dev-pending",
            deviceName: "Pending phone",
            status: "pending",
            sessionTokenHash: null,
            approvedAt: null,
            claimSecretHash: null,
            role: "adult",
            pendingSessionToken: null,
            claimedAt: expect.any(Number),
          },
          {
            deviceId: "dev-revoked",
            deviceName: "Revoked phone",
            status: "revoked",
            sessionTokenHash: null,
            approvedAt: SEEDED_AT + 9,
            claimSecretHash: null,
            role: "adult",
            pendingSessionToken: null,
            claimedAt: SEEDED_AT + 9,
          },
        ]);
        // A pending row is aged from the migration, not from the epoch.
        const pending = (tables.roster as Array<{ claimedAt: number }>)[2];
        expect(pending.claimedAt).toBeGreaterThanOrEqual(NOW_ISH);
      });

      it("rehashes the claim rate-limit keys and leaves every other table alone", async () => {
        const stub = freshHub();
        await resetToV1(stub, startVersion, seedLiveData);

        await runMigrate(stub);

        const tables = await dump(stub);
        expect(tables.rate_limits).toEqual([
          {
            key: `claim:${await sha256Hex(UNUSED_PAIRING_TOKEN)}`,
            windowStart: NOW_ISH,
            count: 4,
          },
          { key: "status:dev-pending", windowStart: NOW_ISH, count: 2 },
          { key: "admin", windowStart: NOW_ISH, count: 1 },
        ]);
        expect(tables.stations).toEqual([
          {
            id: "s1",
            title: "Bhajan",
            seedVideoId: "abcdefghijk",
            iconIndex: 2,
            position: 0,
          },
        ]);
        expect(tables.killswitches).toEqual([{ key: "sangat", enabled: 0 }]);
        expect(tables.health).toEqual([
          {
            deviceId: "dev-approved",
            versionCode: 7,
            versionName: "1.0",
            timezone: "Asia/Kolkata",
            lastSeenAt: SEEDED_AT,
          },
        ]);
      });

      it("keeps the phones that already collect their token working, and revives nothing else", async () => {
        const stub = freshHub();
        await resetToV1(stub, startVersion, seedLiveData);
        await runMigrate(stub);

        // A device that had its token keeps it: the old plaintext still logs in.
        const stations = await stub.fetch("https://example.com/stations", {
          headers: { Authorization: `Bearer ${SESSION_APPROVED}` },
        });
        expect(stations.status).toBe(200);
        const me = await stub.fetch("https://example.com/me", {
          headers: { Authorization: `Bearer ${SESSION_APPROVED}` },
        });
        expect(await me.json()).toEqual({
          deviceId: "dev-approved",
          role: "grandparent",
        });

        // A pending phone's claimSecret was cleared, so it must claim again.
        const status = await stub.fetch("https://example.com/pairing/status", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            deviceId: "dev-pending",
            claimSecret: SECRET_PENDING,
          }),
        });
        expect(status.status).toBe(404);

        // A link the owner minted but nobody opened yet still works once.
        const claim = await stub.fetch("https://example.com/pairing/claim", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            token: UNUSED_PAIRING_TOKEN,
            deviceName: "New phone",
          }),
        });
        expect(claim.status).toBe(200);
        const used = await stub.fetch("https://example.com/pairing/claim", {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ token: USED_PAIRING_TOKEN }),
        });
        expect(used.status).toBe(410);

        // The hashed hub is a normal hub: it still pairs a phone end to end.
        const created = await stub.fetch("https://example.com/pairing/create", {
          method: "POST",
          headers: adminHeaders,
        });
        expect(created.status).toBe(200);
      });

      it("never runs twice: a second pass changes nothing and does not re-hash", async () => {
        const stub = freshHub();
        await resetToV1(stub, startVersion, seedLiveData);
        await runMigrate(stub);
        const afterFirst = await dump(stub);

        await runMigrate(stub);
        await runMigrate(stub);

        expect(await dump(stub)).toEqual(afterFirst);
        expect(await schemaVersion(stub)).toBe(2);
        // Re-hashing would have made the stored value the hash of a hash.
        expect(
          (afterFirst.roster as Array<{ sessionTokenHash: string | null }>)[0]
            .sessionTokenHash,
        ).toBe(await sha256Hex(SESSION_APPROVED));
      });
    });
  }

  it("leaves a database that is already newer than this code untouched", async () => {
    const stub = freshHub();
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec("UPDATE schema_version SET version = 7");
    });
    const before = await dump(stub);

    await runMigrate(stub);

    expect(await dump(stub)).toEqual(before);
    expect(await schemaVersion(stub)).toBe(7);
  });

  it("rolls back completely when a step fails, leaving the old version in place", async () => {
    const stub = freshHub();
    await resetToV1(stub, 1, (sql) => {
      seedLiveData(sql);
      // A trigger that aborts the very first write the migration makes, so the
      // failure lands after the schema changes have started.
      sql.exec(
        "CREATE TRIGGER block_hash BEFORE UPDATE ON roster BEGIN SELECT RAISE(ABORT, 'blocked'); END",
      );
    });
    const before = await dump(stub);

    await expect(runMigrate(stub)).rejects.toThrow();

    expect(await schemaVersion(stub)).toBe(1);
    expect(await dump(stub)).toEqual(before);
    expect(await columns(stub, "roster")).not.toContain("pendingSessionToken");
  });
});
