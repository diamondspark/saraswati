import { env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { LATEST_SCHEMA_VERSION, migrate } from "../src/migrations";
import { Store } from "../src/sangat/store";
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

// What v3 added: the family features' own tables.
const V3_TABLES = [
  "parivaar_playlists",
  "family_playlists",
  "sangat_rooms",
  "sangat_members",
  "sangat_pending_joins",
  "sangat_suggestions",
];

// What v4 added: Ask Saraswati's daily counters.
const V4_TABLES = ["ask_usage"];

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
    for (const table of [...TABLES, ...V3_TABLES, ...V4_TABLES]) {
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

function tableNames(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) =>
    state.storage.sql
      .exec<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' ORDER BY name",
      )
      .toArray()
      .map((row) => row.name),
  );
}

function v3Rows(stub: Stub) {
  return runInDurableObject(stub, (_instance, state) =>
    Object.fromEntries(
      V3_TABLES.map((table) => [
        table,
        state.storage.sql
          .exec(`SELECT * FROM ${table} ORDER BY rowid`)
          .toArray(),
      ]),
    ),
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
  it("reports the latest version and lands a brand-new hub on it", async () => {
    const stub = freshHub();

    // The constructor has already run the migrations on an empty database.
    expect(LATEST_SCHEMA_VERSION).toBe(5);
    expect(await schemaVersion(stub)).toBe(5);
    expect(await tableNames(stub)).toEqual(
      [...TABLES, ...V3_TABLES, ...V4_TABLES, "schema_version"].sort(),
    );
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
    ).toBe(5);
  });

  for (const startVersion of [0, 1] as const) {
    describe(`from the live v1 schema, recorded version ${startVersion}`, () => {
      it("hashes plaintext tokens in place and drops every plaintext credential", async () => {
        const stub = freshHub();
        await resetToV1(stub, startVersion, seedLiveData);

        await runMigrate(stub);

        expect(await schemaVersion(stub)).toBe(5);
        expect(await tableNames(stub)).toEqual(
          [...TABLES, ...V3_TABLES, ...V4_TABLES, "schema_version"].sort(),
        );
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
        expect(await schemaVersion(stub)).toBe(5);
        // Re-hashing would have made the stored value the hash of a hash.
        expect(
          (afterFirst.roster as Array<{ sessionTokenHash: string | null }>)[0]
            .sessionTokenHash,
        ).toBe(await sha256Hex(SESSION_APPROVED));
      });
    });
  }

  describe("from the deployed v2 schema", () => {
    const DEVICE_TOKEN = "77".repeat(32);

    /**
     * A hub as it is live today: Phase 3's tables and data, the version
     * recorded as 2, and none of the family features' tables yet.
     */
    async function resetToV2(stub: Stub, extra: (sql: SqlStorage) => void = () => {}) {
      const deviceTokenHash = await sha256Hex(DEVICE_TOKEN);
      await runInDurableObject(stub, (_instance, state) => {
        const sql = state.storage.sql;
        for (const table of [...V3_TABLES, ...V4_TABLES]) {
          sql.exec(`DROP TABLE IF EXISTS ${table}`);
        }
        sql.exec(
          `INSERT INTO roster (deviceId, deviceName, status, sessionTokenHash, pendingSessionToken, approvedAt, claimSecretHash, role, claimedAt) VALUES
             ('dev-live',    'Live phone',    'approved', ?,    NULL, ?,    NULL, 'grandparent', ?),
             ('dev-waiting', 'Waiting phone', 'pending',  NULL, NULL, NULL, ?,    'adult',       ?)`,
          deviceTokenHash,
          SEEDED_AT,
          SEEDED_AT,
          "88".repeat(32),
          SEEDED_AT + 1,
        );
        sql.exec(
          "INSERT INTO stations (id, title, seedVideoId, iconIndex, position) VALUES ('s1', 'Bhajan', 'abcdefghijk', 2, 0)",
        );
        sql.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'");
        extra(sql);
        sql.exec("UPDATE schema_version SET version = 2");
      });
    }

    it("creates the family tables empty and records the latest version", async () => {
      const stub = freshHub();
      await resetToV2(stub);
      expect(await schemaVersion(stub)).toBe(2);
      expect(await tableNames(stub)).not.toContain("sangat_rooms");

      await runMigrate(stub);

      expect(await schemaVersion(stub)).toBe(5);
      expect(await tableNames(stub)).toEqual(
        [...TABLES, ...V3_TABLES, ...V4_TABLES, "schema_version"].sort(),
      );
      expect(Object.values(await v3Rows(stub)).flat()).toEqual([]);
    });

    it("keeps every existing roster row and the other Phase 3 data as it was", async () => {
      const stub = freshHub();
      await resetToV2(stub);
      const before = await dump(stub);
      expect(before.roster).toHaveLength(2);

      await runMigrate(stub);

      expect(await dump(stub)).toEqual(before);
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
    });

    it("leaves the phones paired at v2 working, through the hashed lookup", async () => {
      const stub = freshHub();
      await resetToV2(stub);
      await runMigrate(stub);

      const me = await stub.fetch("https://example.com/me", {
        headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
      });
      expect(await me.json()).toEqual({
        deviceId: "dev-live",
        role: "grandparent",
      });
      const playlists = await stub.fetch("https://example.com/family-playlists", {
        headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
      });
      expect(playlists.status).toBe(200);
      expect(await playlists.json()).toEqual({ playlists: [] });
    });

    it("is idempotent: tables that already hold rows keep them, and a second pass changes nothing", async () => {
      const stub = freshHub();
      await resetToV2(stub, (sql) => {
        // A database that got some of the family tables before the ledger knew.
        sql.exec(`CREATE TABLE parivaar_playlists (
          deviceId TEXT PRIMARY KEY,
          memberName TEXT NOT NULL,
          playlistId TEXT NOT NULL,
          updatedAt INTEGER NOT NULL
        )`);
        sql.exec(
          "INSERT INTO parivaar_playlists (deviceId, memberName, playlistId, updatedAt) VALUES ('dev-live', 'Dadi', 'PLabcdefghijk', ?)",
          SEEDED_AT,
        );
      });

      await runMigrate(stub);
      const afterFirst = { roster: await dump(stub), family: await v3Rows(stub) };
      await runMigrate(stub);
      await runMigrate(stub);

      expect(afterFirst.family.parivaar_playlists).toEqual([
        {
          deviceId: "dev-live",
          memberName: "Dadi",
          playlistId: "PLabcdefghijk",
          updatedAt: SEEDED_AT,
        },
      ]);
      expect({ roster: await dump(stub), family: await v3Rows(stub) }).toEqual(
        afterFirst,
      );
      expect(await schemaVersion(stub)).toBe(5);
    });

    it("rolls back completely when a table cannot be made, leaving version 2", async () => {
      const stub = freshHub();
      await resetToV2(stub, (sql) => {
        // Not the shape Sangat expects: its index on roomCode cannot be made,
        // which fails after the Parivaar and family-playlist tables were.
        sql.exec("CREATE TABLE sangat_members (userId TEXT PRIMARY KEY)");
      });
      const before = await dump(stub);

      await expect(runMigrate(stub)).rejects.toThrow();

      expect(await schemaVersion(stub)).toBe(2);
      expect(await dump(stub)).toEqual(before);
      const names = await tableNames(stub);
      expect(names).not.toContain("parivaar_playlists");
      expect(names).not.toContain("family_playlists");
    });
  });

  describe("from the Phase 4 v3 schema", () => {
    /** A v3 hub with data in the family tables and in every Phase 3 table, and no ask_usage. */
    async function resetToV3(stub: Stub) {
      await runInDurableObject(stub, (_instance, state) => {
        const sql = state.storage.sql;
        sql.exec("DROP TABLE IF EXISTS ask_usage");
        sql.exec(
          "INSERT INTO roster (deviceId, deviceName, status, role) VALUES ('dev-v3', 'V3 phone', 'approved', 'adult')",
        );
        sql.exec(
          "INSERT INTO parivaar_playlists (deviceId, memberName, playlistId, updatedAt) VALUES ('dev-v3', 'Dadi', 'PLabcdefghijk', ?)",
          SEEDED_AT,
        );
        sql.exec(
          "INSERT INTO family_playlists (id, playlistId, title, addedByDeviceId, addedAt) VALUES ('fp1', 'PLzyxwvutsrq', 'Bhajans', 'dev-v3', ?)",
          SEEDED_AT,
        );
        sql.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'");
        sql.exec("UPDATE schema_version SET version = 3");
      });
    }

    it("adds ask_usage, keeps every existing row and records the latest version", async () => {
      const stub = freshHub();
      await resetToV3(stub);
      expect(await tableNames(stub)).not.toContain("ask_usage");
      const before = { roster: await dump(stub), family: await v3Rows(stub) };

      await runMigrate(stub);

      expect(await schemaVersion(stub)).toBe(5);
      expect(await tableNames(stub)).toContain("ask_usage");
      expect({ roster: await dump(stub), family: await v3Rows(stub) }).toEqual(
        before,
      );
      expect(
        await runInDurableObject(stub, (_instance, state) =>
          state.storage.sql
            .exec<{ n: number }>("SELECT COUNT(*) AS n FROM ask_usage")
            .one().n,
        ),
      ).toBe(0);
    });

    it("is idempotent: a second pass keeps the counts it has recorded", async () => {
      const stub = freshHub();
      await resetToV3(stub);
      await runMigrate(stub);
      await runInDurableObject(stub, (_instance, state) => {
        state.storage.sql.exec(
          "INSERT INTO ask_usage (day, key, count) VALUES ('2026-10-19', 'family', 7)",
        );
      });

      await runMigrate(stub);
      await runMigrate(stub);

      expect(await schemaVersion(stub)).toBe(5);
      expect(
        await runInDurableObject(stub, (_instance, state) =>
          state.storage.sql
            .exec("SELECT day, key, count FROM ask_usage")
            .toArray(),
        ),
      ).toEqual([{ day: "2026-10-19", key: "family", count: 7 }]);
    });

    it("tolerates ask_usage already existing at v3 (a hub that ran Phase 6 code first)", async () => {
      const stub = freshHub();
      await resetToV3(stub);
      await runInDurableObject(stub, (_instance, state) => {
        const sql = state.storage.sql;
        sql.exec(
          "CREATE TABLE ask_usage (day TEXT, key TEXT, count INTEGER NOT NULL, PRIMARY KEY (day, key))",
        );
        sql.exec(
          "INSERT INTO ask_usage (day, key, count) VALUES ('2026-10-19', 'family', 3)",
        );
      });

      await runMigrate(stub);

      expect(await schemaVersion(stub)).toBe(5);
      expect(
        await runInDurableObject(stub, (_instance, state) =>
          state.storage.sql.exec("SELECT count FROM ask_usage").toArray(),
        ),
      ).toEqual([{ count: 3 }]);
    });
  });

  describe("from a v4 hub with a live Sangat room", () => {
    const LIVE_SESSION_TOKEN = "aa".repeat(32);

    /** A v4 hub: sangat_members still has the old plaintext column, with one live seat in it. */
    async function resetToV4(stub: Stub) {
      await runInDurableObject(stub, (_instance, state) => {
        const sql = state.storage.sql;
        sql.exec("ALTER TABLE sangat_members RENAME COLUMN sessionTokenHash TO sessionToken");
        sql.exec(
          "INSERT INTO sangat_rooms (code, hostUserId, hostDeviceId, carMode, state, createdAt, emptySince, hostDisconnectedAt) VALUES ('ROOM0001', 'user_1', 'dev-1', 0, x'', ?, NULL, NULL)",
          SEEDED_AT,
        );
        sql.exec(
          "INSERT INTO sangat_members (userId, roomCode, deviceId, username, sessionToken, isHost, connected, disconnectedAt) VALUES ('user_1', 'ROOM0001', 'dev-1', 'Asha', ?, 1, 1, NULL)",
          LIVE_SESSION_TOKEN,
        );
        sql.exec("UPDATE schema_version SET version = 4");
      });
    }

    it("renames sessionToken to sessionTokenHash and hashes the value in place, and records version 5", async () => {
      const stub = freshHub();
      await resetToV4(stub);
      expect(await columns(stub, "sangat_members")).toContain("sessionToken");

      await runMigrate(stub);

      expect(await schemaVersion(stub)).toBe(5);
      const cols = await columns(stub, "sangat_members");
      expect(cols).toContain("sessionTokenHash");
      expect(cols).not.toContain("sessionToken");
      // Hashed in place, like the roster's v2: a deploy drops every
      // hibernated socket, and the phone in this room is expected to recover
      // by reconnecting with the real token it already holds, so the stored
      // value must be that token's hash from the moment this migration
      // finishes, not still the plaintext under a renamed column.
      const stored = await runInDurableObject(stub, (_instance, state) =>
        state.storage.sql
          .exec<{ sessionTokenHash: string }>("SELECT sessionTokenHash FROM sangat_members WHERE userId = 'user_1'")
          .one().sessionTokenHash,
      );
      expect(stored).toBe(await sha256Hex(LIVE_SESSION_TOKEN));
      expect(stored).not.toBe(LIVE_SESSION_TOKEN);

      // The real token still looks the member up, the way handleReconnect does.
      const found = await runInDurableObject(stub, async (_instance, state) =>
        new Store(state.storage).memberByTokenHash(await sha256Hex(LIVE_SESSION_TOKEN)),
      );
      expect(found?.userId).toBe("user_1");
    });

    it("is idempotent: a second pass changes nothing", async () => {
      const stub = freshHub();
      await resetToV4(stub);
      await runMigrate(stub);
      const after = await runInDurableObject(stub, (_instance, state) =>
        state.storage.sql.exec("SELECT * FROM sangat_members").toArray(),
      );

      await runMigrate(stub);

      expect(
        await runInDurableObject(stub, (_instance, state) =>
          state.storage.sql.exec("SELECT * FROM sangat_members").toArray(),
        ),
      ).toEqual(after);
      expect(await schemaVersion(stub)).toBe(5);
    });
  });

  it("leaves a database that is already newer than this code untouched", async () => {
    const stub = freshHub();
    await runInDurableObject(stub, (_instance, state) => {
      state.storage.sql.exec("UPDATE schema_version SET version = 9");
    });
    const before = await dump(stub);

    await runMigrate(stub);

    expect(await dump(stub)).toEqual(before);
    expect(await schemaVersion(stub)).toBe(9);
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
