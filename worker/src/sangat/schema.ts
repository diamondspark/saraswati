// SQLite tables for Sangat rooms. They live in the FamilyHub Durable Object's
// own storage; wrangler.toml needs no migration for them. Everything in memory
// (Sangat's caches) can be rebuilt from these rows.

export function createSangatTables(sql: SqlStorage): void {
  // state is a listentogether.RoomState (protobuf bytes): users, current track,
  // queue, volume, revision. hostUserId is "" while the room has no host.
  // emptySince marks a room with no members left: nobody can find or join it
  // from then on, and the alarm deletes it EMPTY_ROOM_CLEANUP_MS later.
  sql.exec(`CREATE TABLE IF NOT EXISTS sangat_rooms (
    code TEXT PRIMARY KEY,
    hostUserId TEXT NOT NULL DEFAULT '',
    hostDeviceId TEXT NOT NULL DEFAULT '',
    carMode INTEGER NOT NULL DEFAULT 0,
    state BLOB NOT NULL,
    createdAt INTEGER NOT NULL,
    emptySince INTEGER,
    hostDisconnectedAt INTEGER
  )`);
  // One row per person in a room, connected or inside the reconnect grace.
  // rowid order is join order, which is how the next host is chosen.
  // sessionToken is stored as plaintext by design. It is scoped to one room and bound to
  // the device it was issued to (useless without that device's hashed hub bearer), and it
  // is deleted on leave, expiry, revoke and the kill switch. Hashing it would put an
  // await inside the synchronous handler transaction.
  sql.exec(`CREATE TABLE IF NOT EXISTS sangat_members (
    userId TEXT PRIMARY KEY,
    roomCode TEXT NOT NULL,
    deviceId TEXT NOT NULL,
    username TEXT NOT NULL,
    sessionToken TEXT NOT NULL UNIQUE,
    isHost INTEGER NOT NULL DEFAULT 0,
    connected INTEGER NOT NULL DEFAULT 1,
    disconnectedAt INTEGER
  )`);
  sql.exec("CREATE INDEX IF NOT EXISTS sangat_members_room ON sangat_members (roomCode)");
  sql.exec("CREATE INDEX IF NOT EXISTS sangat_members_device ON sangat_members (deviceId)");
  sql.exec(`CREATE TABLE IF NOT EXISTS sangat_pending_joins (
    roomCode TEXT NOT NULL,
    userId TEXT NOT NULL,
    username TEXT NOT NULL,
    deviceId TEXT NOT NULL,
    PRIMARY KEY (roomCode, userId)
  )`);
  sql.exec("CREATE INDEX IF NOT EXISTS sangat_pending_user ON sangat_pending_joins (userId)");
  // track is a listentogether.TrackInfo (protobuf bytes).
  sql.exec(`CREATE TABLE IF NOT EXISTS sangat_suggestions (
    id TEXT PRIMARY KEY,
    roomCode TEXT NOT NULL,
    fromUserId TEXT NOT NULL,
    fromUsername TEXT NOT NULL,
    track BLOB NOT NULL,
    createdAt INTEGER NOT NULL
  )`);
  sql.exec("CREATE INDEX IF NOT EXISTS sangat_suggestions_room ON sangat_suggestions (roomCode)");
}

export const SANGAT_TABLES = [
  "sangat_rooms",
  "sangat_members",
  "sangat_pending_joins",
  "sangat_suggestions",
] as const;
