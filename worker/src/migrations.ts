import { createSangatTables } from "./sangat/schema";
import { sha256Hex } from "./secrets";

// The schema version lives in a one-row `schema_version` table, written in the
// same transaction as the schema change it describes. A migration therefore
// either happened completely and is recorded, or did not happen at all, and
// can never run twice. (SQLite's own `PRAGMA user_version` would be the usual
// home for this number, but Durable Object SQLite rejects it with
// SQLITE_AUTH, as it does every PRAGMA outside a short allow-list.)
//
//   v0  an unversioned database: either brand new (no tables) or the hub as
//       deployed up to commit 4308392a, which created v1's tables itself.
//   v1  the schema the hub was live with: credentials stored in the clear.
//   v2  credentials stored as SHA-256 hashes (see FamilyHub.ts), the one
//       plaintext session token kept only in `pendingSessionToken` until the
//       phone collects it, and `claimedAt` so stale pending requests expire.
//   v3  the family features' own tables: Parivaar playlists, the shared family
//       playlists and the Sangat rooms. Nothing else changes, so a v2 hub with
//       its roster keeps every row.
export const LATEST_SCHEMA_VERSION = 3;

type Row = Record<string, SqlStorageValue>;

/** 0 for a database that has never recorded a version. */
function readVersion(sql: SqlStorage): number {
  sql.exec(
    "CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)",
  );
  const rows = sql
    .exec<{ version: number } & Row>("SELECT version FROM schema_version")
    .toArray();
  return rows.length === 0 ? 0 : rows[0].version;
}

function writeVersion(sql: SqlStorage, version: number): void {
  sql.exec("DELETE FROM schema_version");
  sql.exec("INSERT INTO schema_version (version) VALUES (?)", version);
}

/** Idempotent: a live hub already has these tables, a new one gets them now. */
function applyV1(storage: DurableObjectStorage): void {
  const sql = storage.sql;
  storage.transactionSync(() => {
    sql.exec(`CREATE TABLE IF NOT EXISTS pairing_tokens (
      token TEXT PRIMARY KEY,
      expiresAt INTEGER,
      usedAt INTEGER
    )`);
    sql.exec(`CREATE TABLE IF NOT EXISTS roster (
      deviceId TEXT PRIMARY KEY,
      deviceName TEXT,
      status TEXT,
      sessionToken TEXT,
      approvedAt INTEGER,
      claimSecret TEXT,
      role TEXT NOT NULL DEFAULT 'adult'
    )`);
    sql.exec(`CREATE TABLE IF NOT EXISTS stations (
      id TEXT PRIMARY KEY,
      title TEXT,
      seedVideoId TEXT,
      iconIndex INTEGER,
      position INTEGER
    )`);
    sql.exec(`CREATE TABLE IF NOT EXISTS killswitches (
      key TEXT PRIMARY KEY,
      enabled INTEGER
    )`);
    sql.exec(`CREATE TABLE IF NOT EXISTS health (
      deviceId TEXT PRIMARY KEY,
      versionCode INTEGER,
      versionName TEXT,
      timezone TEXT,
      lastSeenAt INTEGER
    )`);
    sql.exec(`CREATE TABLE IF NOT EXISTS rate_limits (
      key TEXT PRIMARY KEY,
      windowStart INTEGER,
      count INTEGER
    )`);
    writeVersion(sql, 1);
  });
}

const CLAIM_KEY_PREFIX = "claim:";

/**
 * Hashes every plaintext credential in place.
 *
 *  - A pairing token becomes its hash: a link the owner minted but nobody has
 *    opened yet keeps working, because the phone presents the plaintext and
 *    the hub now looks it up by hash.
 *  - A session token becomes its hash: a phone that already holds its token
 *    keeps working. It cannot become `pendingSessionToken`, because nothing
 *    says the phone ever collected it, and keeping plaintext at rest is the
 *    thing being removed.
 *  - Every claimSecret is cleared, not hashed. A phone still waiting on
 *    /pairing/status, or approved but yet to collect, gets a 404 and has to be
 *    re-paired; the owner revokes the dead entry. This is a deliberate trade:
 *    one re-pairing, only for phones caught mid-handshake at deploy time.
 *  - A claim rate-limit key embeds the plaintext pairing token, so it is
 *    rewritten around the token's hash and keeps its count and window.
 *
 * The values are hashed first (crypto.subtle is asynchronous) and applied in a
 * single transaction together with the version bump.
 */
async function applyV2(storage: DurableObjectStorage): Promise<void> {
  const sql = storage.sql;

  const pairingTokens = sql
    .exec<{ token: string } & Row>("SELECT token FROM pairing_tokens")
    .toArray();
  const sessionTokens = sql
    .exec<{ deviceId: string; sessionToken: string } & Row>(
      "SELECT deviceId, sessionToken FROM roster WHERE sessionToken IS NOT NULL",
    )
    .toArray();
  const claimKeys = sql
    .exec<{ key: string } & Row>(
      "SELECT key FROM rate_limits WHERE substr(key, 1, ?) = ?",
      CLAIM_KEY_PREFIX.length,
      CLAIM_KEY_PREFIX,
    )
    .toArray();

  const pairingTokenHashes = await Promise.all(
    pairingTokens.map((row) => sha256Hex(row.token)),
  );
  const sessionTokenHashes = await Promise.all(
    sessionTokens.map((row) => sha256Hex(row.sessionToken)),
  );
  const claimKeyHashes = await Promise.all(
    claimKeys.map((row) => sha256Hex(row.key.slice(CLAIM_KEY_PREFIX.length))),
  );
  const migratedAt = Date.now();

  storage.transactionSync(() => {
    // Checked again inside the transaction that does the work.
    if (readVersion(sql) !== 1) {
      return;
    }

    sql.exec("ALTER TABLE pairing_tokens RENAME COLUMN token TO tokenHash");
    pairingTokens.forEach((row, index) => {
      sql.exec(
        "UPDATE pairing_tokens SET tokenHash = ? WHERE tokenHash = ?",
        pairingTokenHashes[index],
        row.token,
      );
    });

    sql.exec("ALTER TABLE roster RENAME COLUMN sessionToken TO sessionTokenHash");
    sql.exec("ALTER TABLE roster RENAME COLUMN claimSecret TO claimSecretHash");
    sql.exec("ALTER TABLE roster ADD COLUMN pendingSessionToken TEXT");
    sql.exec("ALTER TABLE roster ADD COLUMN claimedAt INTEGER");
    sessionTokens.forEach((row, index) => {
      sql.exec(
        "UPDATE roster SET sessionTokenHash = ? WHERE deviceId = ?",
        sessionTokenHashes[index],
        row.deviceId,
      );
    });
    sql.exec("UPDATE roster SET claimSecretHash = NULL");
    // The best available answer for rows that predate the column: when the
    // device was approved, or else now, so a pending row gets a full hour.
    sql.exec("UPDATE roster SET claimedAt = COALESCE(approvedAt, ?)", migratedAt);

    claimKeys.forEach((row, index) => {
      sql.exec(
        "UPDATE rate_limits SET key = ? WHERE key = ?",
        `${CLAIM_KEY_PREFIX}${claimKeyHashes[index]}`,
        row.key,
      );
    });

    writeVersion(sql, 2);
  });
}

/**
 * Idempotent, like v1: every statement is CREATE ... IF NOT EXISTS, so a hub
 * that already has some of these tables (a database from before the migration
 * ledger existed, or one the Sangat code created first) is left as it is.
 *
 * Times are epoch milliseconds; the routes show them as ISO strings.
 */
function applyV3(storage: DurableObjectStorage): void {
  const sql = storage.sql;
  storage.transactionSync(() => {
    // Checked again inside the transaction that does the work.
    if (readVersion(sql) !== 2) {
      return;
    }

    // Parivaar Mix: each phone's own playlist.
    sql.exec(`CREATE TABLE IF NOT EXISTS parivaar_playlists (
      deviceId TEXT PRIMARY KEY,
      memberName TEXT NOT NULL,
      playlistId TEXT NOT NULL,
      updatedAt INTEGER NOT NULL
    )`);
    // The playlists the whole family shares.
    sql.exec(`CREATE TABLE IF NOT EXISTS family_playlists (
      id TEXT PRIMARY KEY,
      playlistId TEXT NOT NULL UNIQUE,
      title TEXT NOT NULL,
      addedByDeviceId TEXT NOT NULL,
      addedAt INTEGER NOT NULL
    )`);
    createSangatTables(sql);
    writeVersion(sql, 3);
  });
}

/**
 * Brings the database up to LATEST_SCHEMA_VERSION. Safe to call on every
 * start: a database already at (or beyond) the latest version is not touched.
 */
export async function migrate(storage: DurableObjectStorage): Promise<void> {
  const version = readVersion(storage.sql);
  if (version < 1) {
    applyV1(storage);
  }
  if (version < 2) {
    await applyV2(storage);
  }
  if (version < 3) {
    applyV3(storage);
  }
}
