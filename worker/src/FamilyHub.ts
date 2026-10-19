import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";
import { Sangat } from "./sangat/Sangat";
import { createSangatTables } from "./sangat/schema";

const PAIRING_TOKEN_TTL_MS = 15 * 60 * 1000;
const MAX_STATIONS = 6;

// /health: at most one accepted report per device per 5 minutes.
const HEALTH_WINDOW_SECONDS = 5 * 60;
const HEALTH_MAX_REPORTS = 1;
// /pairing/claim: the window matches a pairing token's own TTL, so a token
// whose attempts are spent stays blocked until it would have expired anyway.
const CLAIM_WINDOW_SECONDS = 15 * 60;
const CLAIM_MAX_ATTEMPTS = 10;
// /pairing/status: a phone polls this while it waits for the owner's approval.
const STATUS_WINDOW_SECONDS = 60;
const STATUS_MAX_POLLS = 12;
// Every admin-authenticated request, across all routes, shares one budget.
const ADMIN_RATE_KEY = "admin";
const ADMIN_WINDOW_SECONDS = 60;
const ADMIN_MAX_REQUESTS = 20;
// Storage hygiene for the free-text fields of a health report.
const MAX_HEALTH_TEXT_LENGTH = 128;

// Mirrors the app's FamilyFeature enum. Order here is the order the
// kill-switch map is returned in.
const FAMILY_FEATURE_KEYS = [
  "sangat",
  "parivaar_mix",
  "family_playlists",
  "cast_relay",
] as const;
type FamilyFeatureKey = (typeof FAMILY_FEATURE_KEYS)[number];

// What the owner has set a phone up as. Not to be confused with AuthResult's
// "admin" | "device" `role`, which says who is calling.
const DEVICE_ROLES = ["adult", "grandparent"] as const;
type DeviceRole = (typeof DEVICE_ROLES)[number];
const DEFAULT_DEVICE_ROLE: DeviceRole = "adult";
const ROLE_ERROR = `role must be one of: ${DEVICE_ROLES.join(", ")}`;

type AuthResult =
  | { role: "admin" }
  | { role: "device"; deviceId: string }
  | null;

// Row shapes extend Record<string, SqlStorageValue> (rather than being
// plain interfaces) because SqlStorage.exec<T>()'s generic constraint
// requires an explicit index signature, not just structurally-compatible
// named properties.
interface PairingTokenRow extends Record<string, SqlStorageValue> {
  token: string;
  expiresAt: number;
  usedAt: number | null;
}

interface RosterRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  deviceName: string | null;
  status: string;
  sessionToken: string | null;
  approvedAt: number | null;
  role: string;
}

interface RosterStatusRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  status: string;
}

interface DeviceAuthRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  claimSecret: string | null;
}

interface ClaimStatusRow extends Record<string, SqlStorageValue> {
  status: string;
  sessionToken: string | null;
  claimSecret: string | null;
}

interface RosterIdRow extends Record<string, SqlStorageValue> {
  deviceId: string;
}

interface DeviceRoleRow extends Record<string, SqlStorageValue> {
  role: string;
}

interface StationRow extends Record<string, SqlStorageValue> {
  id: string;
  title: string;
  seedVideoId: string;
  iconIndex: number;
}

interface KillswitchRow extends Record<string, SqlStorageValue> {
  key: string;
  enabled: number;
}

interface RateLimitRow extends Record<string, SqlStorageValue> {
  windowStart: number;
  count: number;
}

interface HealthRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  deviceName: string | null;
  versionCode: number;
  versionName: string;
  timezone: string;
  lastSeenAt: number;
}

interface Station {
  id: string;
  title: string;
  seedVideoId: string;
  iconIndex: number;
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

/**
 * Compares two secrets without bailing out at the first differing byte. The
 * length check is not constant-time, which is fine: the stored secret is
 * always 64 hex chars, so a length mismatch reveals nothing.
 */
function secretsMatch(provided: string, stored: string): boolean {
  const encoder = new TextEncoder();
  const a = encoder.encode(provided);
  const b = encoder.encode(stored);
  return a.byteLength === b.byteLength && crypto.subtle.timingSafeEqual(a, b);
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

async function readJsonBody(
  request: Request,
): Promise<Record<string, unknown>> {
  try {
    const parsed = await request.json();
    return typeof parsed === "object" && parsed !== null
      ? (parsed as Record<string, unknown>)
      : {};
  } catch {
    return {};
  }
}

/**
 * Unlike readJsonBody, which maps anything unparseable to `{}`, this tells
 * "no valid JSON object" (null) apart from an empty object, so a PUT that
 * replaces stored state can reject a malformed body instead of treating it
 * as a no-op.
 */
async function readStrictJsonObject(
  request: Request,
): Promise<Record<string, unknown> | null> {
  try {
    const parsed: unknown = await request.json();
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      !Array.isArray(parsed)
    ) {
      return parsed as Record<string, unknown>;
    }
    return null;
  } catch {
    return null;
  }
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

/** Returns the cleaned station list, or null if any part of it is invalid. */
function parseStations(value: unknown): Station[] | null {
  if (
    !Array.isArray(value) ||
    value.length < 1 ||
    value.length > MAX_STATIONS
  ) {
    return null;
  }

  const seenIds = new Set<string>();
  const stations: Station[] = [];
  for (const entry of value as unknown[]) {
    if (typeof entry !== "object" || entry === null) {
      return null;
    }
    const { id, title, seedVideoId, iconIndex } = entry as Record<
      string,
      unknown
    >;
    if (
      !isNonEmptyString(id) ||
      !isNonEmptyString(title) ||
      !isNonEmptyString(seedVideoId) ||
      typeof iconIndex !== "number" ||
      !Number.isInteger(iconIndex) ||
      iconIndex < 0 ||
      seenIds.has(id)
    ) {
      return null;
    }
    seenIds.add(id);
    stations.push({ id, title, seedVideoId, iconIndex });
  }
  return stations;
}

interface HealthReport {
  deviceId: string;
  versionCode: number;
  versionName: string;
  timezone: string;
}

function isBoundedNonEmptyString(value: unknown): value is string {
  return isNonEmptyString(value) && value.length <= MAX_HEALTH_TEXT_LENGTH;
}

/** Returns the cleaned report, or null if any field is missing or malformed. */
function parseHealthReport(
  body: Record<string, unknown>,
): HealthReport | null {
  const { deviceId, versionCode, versionName, timezone } = body;
  if (
    !isBoundedNonEmptyString(deviceId) ||
    typeof versionCode !== "number" ||
    !Number.isSafeInteger(versionCode) ||
    versionCode < 0 ||
    !isBoundedNonEmptyString(versionName) ||
    !isBoundedNonEmptyString(timezone)
  ) {
    return null;
  }
  return { deviceId, versionCode, versionName, timezone };
}

function isDeviceRole(value: unknown): value is DeviceRole {
  return (DEVICE_ROLES as readonly unknown[]).includes(value);
}

function isFamilyFeatureKey(key: string): key is FamilyFeatureKey {
  return (FAMILY_FEATURE_KEYS as readonly string[]).includes(key);
}

/**
 * Single global Durable Object backing the Saraswati family hub.
 *
 * Routing to the one instance (idFromName("family")) happens in
 * index.ts, not here. This class owns the instance's SQLite storage
 * and request handling.
 */
export class FamilyHub extends DurableObject<Env> {
  private readonly sangat: Sangat;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sangat = new Sangat(ctx);

    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS pairing_tokens (
      token TEXT PRIMARY KEY,
      expiresAt INTEGER,
      usedAt INTEGER
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS roster (
      deviceId TEXT PRIMARY KEY,
      deviceName TEXT,
      status TEXT,
      sessionToken TEXT,
      approvedAt INTEGER,
      claimSecret TEXT,
      role TEXT NOT NULL DEFAULT 'adult'
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS stations (
      id TEXT PRIMARY KEY,
      title TEXT,
      seedVideoId TEXT,
      iconIndex INTEGER,
      position INTEGER
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS killswitches (
      key TEXT PRIMARY KEY,
      enabled INTEGER
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS health (
      deviceId TEXT PRIMARY KEY,
      versionCode INTEGER,
      versionName TEXT,
      timezone TEXT,
      lastSeenAt INTEGER
    )`);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS rate_limits (
      key TEXT PRIMARY KEY,
      windowStart INTEGER,
      count INTEGER
    )`);
    createSangatTables(ctx.storage.sql);
    // INSERT OR IGNORE keeps any key the owner has already flipped; a feature
    // key added to FAMILY_FEATURE_KEYS later seeds itself as enabled.
    for (const key of FAMILY_FEATURE_KEYS) {
      ctx.storage.sql.exec(
        "INSERT OR IGNORE INTO killswitches (key, enabled) VALUES (?, 1)",
        key,
      );
    }
  }

  /**
   * Shared auth check for every route: the admin bearer token first, then
   * an approved roster row's own session token. `null` means
   * unauthenticated. Tasks 4 and 5 reuse this rather than re-checking auth
   * their own way.
   */
  private async authenticate(
    request: Request,
    env: Env,
  ): Promise<AuthResult> {
    const header = request.headers.get("Authorization");
    if (!header?.startsWith("Bearer ")) {
      return null;
    }

    const token = header.slice("Bearer ".length).trim();
    if (!token) {
      return null;
    }

    if (env.ADMIN_KEY && token === env.ADMIN_KEY) {
      return { role: "admin" };
    }

    const rows = this.ctx.storage.sql
      .exec<DeviceAuthRow>(
        "SELECT deviceId, claimSecret FROM roster WHERE sessionToken = ? AND status = 'approved'",
        token,
      )
      .toArray();

    if (rows.length === 1) {
      // The phone has now proven it holds its sessionToken, so the claimSecret
      // that fetched it via /pairing/status has done its job. One write per
      // device, ever: the guard skips every later authentication.
      if (rows[0].claimSecret !== null) {
        this.ctx.storage.sql.exec(
          "UPDATE roster SET claimSecret = NULL WHERE deviceId = ?",
          rows[0].deviceId,
        );
      }
      return { role: "device", deviceId: rows[0].deviceId };
    }

    return null;
  }

  /**
   * Fixed-window counter: true means the call is allowed. The first call in a
   * window (or the first after `windowSeconds` have elapsed) opens a new
   * window at count 1; once `maxCount` calls have been allowed in a window,
   * further calls return false and write nothing at all.
   */
  private rateLimit(
    key: string,
    windowSeconds: number,
    maxCount: number,
  ): boolean {
    const now = Date.now();
    return this.ctx.storage.transactionSync(() => {
      const row = this.ctx.storage.sql
        .exec<RateLimitRow>(
          "SELECT windowStart, count FROM rate_limits WHERE key = ?",
          key,
        )
        .toArray()[0];

      if (!row || now - row.windowStart >= windowSeconds * 1000) {
        this.ctx.storage.sql.exec(
          "INSERT OR REPLACE INTO rate_limits (key, windowStart, count) VALUES (?, ?, 1)",
          key,
          now,
        );
        return true;
      }

      if (row.count >= maxCount) {
        return false;
      }

      this.ctx.storage.sql.exec(
        "UPDATE rate_limits SET count = count + 1 WHERE key = ?",
        key,
      );
      return true;
    });
  }

  /**
   * Spends one request from the shared admin budget and returns the 429 to
   * send once it is gone. Call it only after authenticate() succeeded, so a
   * request with a bad bearer can never use up the owner's budget; device
   * requests are not admin requests and are never counted.
   */
  private throttleAdmin(auth: AuthResult): Response | null {
    if (
      auth?.role !== "admin" ||
      this.rateLimit(ADMIN_RATE_KEY, ADMIN_WINDOW_SECONDS, ADMIN_MAX_REQUESTS)
    ) {
      return null;
    }
    return json({ error: "too many requests" }, 429);
  }

  private async handlePairingCreate(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const token = randomHex(32);
    const expiresAtMs = Date.now() + PAIRING_TOKEN_TTL_MS;

    this.ctx.storage.sql.exec(
      "INSERT INTO pairing_tokens (token, expiresAt, usedAt) VALUES (?, ?, NULL)",
      token,
      expiresAtMs,
    );

    return json({ token, expiresAt: new Date(expiresAtMs).toISOString() });
  }

  private async handlePairingClaim(request: Request): Promise<Response> {
    const body = await readJsonBody(request);
    const token = typeof body.token === "string" ? body.token : "";
    const deviceName =
      typeof body.deviceName === "string" ? body.deviceName : null;

    if (!token) {
      return json({ error: "not found" }, 404);
    }

    // Checked before the token is looked up or spent, so a malformed role
    // never burns the owner's pairing token and the phone can simply retry.
    const role: unknown = "role" in body ? body.role : DEFAULT_DEVICE_ROLE;
    if (!isDeviceRole(role)) {
      return json({ error: ROLE_ERROR }, 400);
    }

    const rows = this.ctx.storage.sql
      .exec<PairingTokenRow>(
        "SELECT token, expiresAt, usedAt FROM pairing_tokens WHERE token = ?",
        token,
      )
      .toArray();

    if (rows.length === 0) {
      return json({ error: "not found" }, 404);
    }

    // Only a token the owner really minted gets a counter row. Counting
    // before this lookup would let anyone mint unbounded rate_limits rows by
    // guessing random tokens, which is exactly the write amplification the
    // limiter exists to prevent.
    if (
      !this.rateLimit(
        `claim:${token}`,
        CLAIM_WINDOW_SECONDS,
        CLAIM_MAX_ATTEMPTS,
      )
    ) {
      return json({ error: "too many attempts" }, 429);
    }

    const row = rows[0];
    if (row.usedAt !== null || row.expiresAt < Date.now()) {
      return json({ error: "gone" }, 410);
    }

    const deviceId = crypto.randomUUID();
    const claimSecret = randomHex(32);
    this.ctx.storage.sql.exec(
      "INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt, claimSecret, role) VALUES (?, ?, 'pending', NULL, NULL, ?, ?)",
      deviceId,
      deviceName,
      claimSecret,
      role,
    );
    this.ctx.storage.sql.exec(
      "UPDATE pairing_tokens SET usedAt = ? WHERE token = ?",
      Date.now(),
      token,
    );

    return json({ deviceId, status: "pending", claimSecret });
  }

  /**
   * How a remote phone learns it was approved and collects its own
   * sessionToken. No bearer: the phone has none yet. The claimSecret handed
   * out by /pairing/claim is the credential; the deviceId alone is not.
   */
  private async handlePairingStatus(request: Request): Promise<Response> {
    const body = await readStrictJsonObject(request);
    const deviceId = body?.deviceId;
    const claimSecret = body?.claimSecret;
    if (!isNonEmptyString(deviceId) || !isNonEmptyString(claimSecret)) {
      return json({ error: "deviceId and claimSecret are required" }, 400);
    }

    const rows = this.ctx.storage.sql
      .exec<ClaimStatusRow>(
        "SELECT status, sessionToken, claimSecret FROM roster WHERE deviceId = ?",
        deviceId,
      )
      .toArray();

    // Unknown ids return before the limiter, so guessing ids cannot mint
    // rate_limits rows (same reasoning as /pairing/claim).
    if (rows.length === 0) {
      return json({ error: "not found" }, 404);
    }

    if (
      !this.rateLimit(
        `status:${deviceId}`,
        STATUS_WINDOW_SECONDS,
        STATUS_MAX_POLLS,
      )
    ) {
      return json({ error: "too many requests" }, 429);
    }

    // Every way of being refused answers the same 404, so a caller cannot
    // tell a wrong secret from a revoked device or one that already
    // collected its token.
    const row = rows[0];
    if (
      row.claimSecret === null ||
      !secretsMatch(claimSecret, row.claimSecret) ||
      (row.status !== "pending" && row.status !== "approved")
    ) {
      return json({ error: "not found" }, 404);
    }

    if (row.status === "approved" && row.sessionToken !== null) {
      return json({ status: "approved", sessionToken: row.sessionToken });
    }
    return json({ status: "pending" });
  }

  private async handleRosterList(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const devices = this.ctx.storage.sql
      .exec<RosterRow>(
        "SELECT deviceId, deviceName, status, sessionToken, approvedAt, role FROM roster",
      )
      .toArray()
      .map((row) => ({
        deviceId: row.deviceId,
        deviceName: row.deviceName,
        status: row.status,
        approvedAt: row.approvedAt,
        role: row.role,
      }));

    return json({ devices });
  }

  /**
   * The owner's remote control for a phone's role: how a grandparent-mode
   * phone, which has no settings and no way out, is switched back. Admin
   * only, so the phone itself can never change it.
   */
  private async handleRosterRole(
    request: Request,
    deviceId: string,
  ): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const body = await readStrictJsonObject(request);
    const role = body?.role;
    if (!isDeviceRole(role)) {
      return json({ error: ROLE_ERROR }, 400);
    }

    const rows = this.ctx.storage.sql
      .exec<RosterStatusRow>(
        "SELECT deviceId, status FROM roster WHERE deviceId = ?",
        deviceId,
      )
      .toArray();

    if (rows.length === 0 || rows[0].status === "revoked") {
      return json({ error: "not found" }, 404);
    }

    this.ctx.storage.sql.exec(
      "UPDATE roster SET role = ? WHERE deviceId = ?",
      role,
      deviceId,
    );

    return json({ deviceId, role });
  }

  /** A phone asking what the owner has set it up as. Devices only. */
  private async handleMe(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "device") {
      return json({ error: "unauthorized" }, 401);
    }

    const rows = this.ctx.storage.sql
      .exec<DeviceRoleRow>(
        "SELECT role FROM roster WHERE deviceId = ?",
        auth.deviceId,
      )
      .toArray();
    if (rows.length === 0) {
      return json({ error: "unauthorized" }, 401);
    }

    return json({ deviceId: auth.deviceId, role: rows[0].role });
  }

  private async handleRosterApprove(
    request: Request,
    deviceId: string,
  ): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const rows = this.ctx.storage.sql
      .exec<RosterStatusRow>(
        "SELECT deviceId, status FROM roster WHERE deviceId = ?",
        deviceId,
      )
      .toArray();

    if (rows.length === 0 || rows[0].status !== "pending") {
      return json({ error: "not found" }, 404);
    }

    const sessionToken = randomHex(32);
    const approvedAtMs = Date.now();
    this.ctx.storage.sql.exec(
      "UPDATE roster SET status = 'approved', sessionToken = ?, approvedAt = ? WHERE deviceId = ?",
      sessionToken,
      approvedAtMs,
      deviceId,
    );

    return json({ deviceId, sessionToken, status: "approved" });
  }

  private async handleRosterRevoke(
    request: Request,
    deviceId: string,
  ): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const rows = this.ctx.storage.sql
      .exec<RosterIdRow>(
        "SELECT deviceId FROM roster WHERE deviceId = ?",
        deviceId,
      )
      .toArray();

    if (rows.length === 0) {
      return json({ error: "not found" }, 404);
    }

    this.ctx.storage.sql.exec(
      "UPDATE roster SET status = 'revoked', sessionToken = NULL, claimSecret = NULL WHERE deviceId = ?",
      deviceId,
    );

    return json({ deviceId, status: "revoked" });
  }

  private readStations(): Station[] {
    return this.ctx.storage.sql
      .exec<StationRow>(
        "SELECT id, title, seedVideoId, iconIndex FROM stations ORDER BY position",
      )
      .toArray()
      .map((row) => ({
        id: row.id,
        title: row.title,
        seedVideoId: row.seedVideoId,
        iconIndex: row.iconIndex,
      }));
  }

  private readKillSwitches(): Record<FamilyFeatureKey, boolean> {
    const enabledByKey = new Map(
      this.ctx.storage.sql
        .exec<KillswitchRow>("SELECT key, enabled FROM killswitches")
        .toArray()
        .map((row) => [row.key, row.enabled !== 0]),
    );
    return Object.fromEntries(
      FAMILY_FEATURE_KEYS.map((key) => [key, enabledByKey.get(key) ?? true]),
    ) as Record<FamilyFeatureKey, boolean>;
  }

  private async handleStationsGet(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (!auth) {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    return json({ stations: this.readStations() });
  }

  private async handleStationsPut(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const body = await readStrictJsonObject(request);
    const stations = body ? parseStations(body.stations) : null;
    if (!stations) {
      return json(
        { error: `stations must be 1-${MAX_STATIONS} valid entries` },
        400,
      );
    }

    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec("DELETE FROM stations");
      stations.forEach((station, position) => {
        this.ctx.storage.sql.exec(
          "INSERT INTO stations (id, title, seedVideoId, iconIndex, position) VALUES (?, ?, ?, ?, ?)",
          station.id,
          station.title,
          station.seedVideoId,
          station.iconIndex,
          position,
        );
      });
    });

    return json({ stations: this.readStations() });
  }

  private async handleKillSwitchesGet(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (!auth) {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    return json(this.readKillSwitches());
  }

  private async handleKillSwitchesPut(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const body = await readStrictJsonObject(request);
    if (!body) {
      return json({ error: "body must be a JSON object" }, 400);
    }

    const changes: Array<[FamilyFeatureKey, boolean]> = [];
    for (const [key, value] of Object.entries(body)) {
      if (!isFamilyFeatureKey(key) || typeof value !== "boolean") {
        return json(
          { error: "keys must be known features with boolean values" },
          400,
        );
      }
      changes.push([key, value]);
    }

    this.ctx.storage.transactionSync(() => {
      for (const [key, enabled] of changes) {
        this.ctx.storage.sql.exec(
          "UPDATE killswitches SET enabled = ? WHERE key = ?",
          enabled ? 1 : 0,
          key,
        );
      }
    });

    return json(this.readKillSwitches());
  }

  private async handleHealthPost(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "device") {
      return json({ error: "unauthorized" }, 401);
    }

    const body = await readStrictJsonObject(request);
    const report = body ? parseHealthReport(body) : null;
    if (!report) {
      return json({ error: "body must be a valid health report" }, 400);
    }

    // The row and the rate-limit window are keyed on the authenticated
    // device, never on whatever deviceId the body claims, so one phone can
    // neither overwrite another's row nor burn its window.
    if (report.deviceId !== auth.deviceId) {
      return json({ error: "deviceId does not match the session" }, 400);
    }

    // A rate-limited report is dropped without any error: a phone that
    // reports too often must never see a failure, least of all in the car.
    if (
      this.rateLimit(
        `health:${auth.deviceId}`,
        HEALTH_WINDOW_SECONDS,
        HEALTH_MAX_REPORTS,
      )
    ) {
      this.ctx.storage.sql.exec(
        `INSERT INTO health (deviceId, versionCode, versionName, timezone, lastSeenAt)
         VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(deviceId) DO UPDATE SET
           versionCode = excluded.versionCode,
           versionName = excluded.versionName,
           timezone = excluded.timezone,
           lastSeenAt = excluded.lastSeenAt`,
        auth.deviceId,
        report.versionCode,
        report.versionName,
        report.timezone,
        Date.now(),
      );
    }

    return new Response(null, { status: 204 });
  }

  private async handleHealthGet(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    const devices = this.ctx.storage.sql
      .exec<HealthRow>(
        `SELECT h.deviceId, r.deviceName, h.versionCode, h.versionName, h.timezone, h.lastSeenAt
         FROM health h LEFT JOIN roster r ON r.deviceId = h.deviceId
         ORDER BY h.lastSeenAt DESC, h.deviceId`,
      )
      .toArray();

    return json({ devices });
  }

  /**
   * GET /ws: Sangat's WebSocket. Only an approved device may connect, and the
   * checks run before the upgrade so a stranger, a pending or revoked phone, or
   * the owner's admin key never gets a socket. Knowing a room code grants nothing.
   */
  private async handleSangatSocket(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected a WebSocket upgrade", { status: 426 });
    }

    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "device") {
      return json({ error: "unauthorized" }, 401);
    }

    if (!this.readKillSwitches().sangat) {
      return json({ error: "feature disabled" }, 503);
    }

    return this.sangat.accept(auth.deviceId);
  }

  async webSocketMessage(
    ws: WebSocket,
    message: string | ArrayBuffer,
  ): Promise<void> {
    await this.sangat.onMessage(ws, message);
  }

  async webSocketClose(
    ws: WebSocket,
    code: number,
    reason: string,
  ): Promise<void> {
    // Finish the closing handshake with a code a close frame may carry.
    try {
      ws.close(code === 1000 || (code >= 3000 && code <= 4999) ? code : 1000, reason);
    } catch {
      // already closed
    }
    await this.sangat.onClose(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.sangat.onClose(ws);
  }

  async alarm(): Promise<void> {
    await this.sangat.onAlarm();
  }

  async fetch(request: Request): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (request.method === "GET" && pathname === "/ws") {
      return this.handleSangatSocket(request);
    }

    if (request.method === "GET" && pathname === "/ping") {
      return new Response("ok", { status: 200 });
    }

    if (request.method === "POST" && pathname === "/pairing/create") {
      return this.handlePairingCreate(request);
    }

    if (request.method === "POST" && pathname === "/pairing/claim") {
      return this.handlePairingClaim(request);
    }

    if (request.method === "POST" && pathname === "/pairing/status") {
      return this.handlePairingStatus(request);
    }

    if (request.method === "GET" && pathname === "/roster") {
      return this.handleRosterList(request);
    }

    const approveMatch = /^\/roster\/([^/]+)\/approve$/.exec(pathname);
    if (request.method === "POST" && approveMatch) {
      return this.handleRosterApprove(
        request,
        decodeURIComponent(approveMatch[1]),
      );
    }

    const revokeMatch = /^\/roster\/([^/]+)\/revoke$/.exec(pathname);
    if (request.method === "POST" && revokeMatch) {
      return this.handleRosterRevoke(
        request,
        decodeURIComponent(revokeMatch[1]),
      );
    }

    const roleMatch = /^\/roster\/([^/]+)\/role$/.exec(pathname);
    if (request.method === "PUT" && roleMatch) {
      return this.handleRosterRole(request, decodeURIComponent(roleMatch[1]));
    }

    if (request.method === "GET" && pathname === "/me") {
      return this.handleMe(request);
    }

    if (pathname === "/stations") {
      if (request.method === "GET") {
        return this.handleStationsGet(request);
      }
      if (request.method === "PUT") {
        return this.handleStationsPut(request);
      }
    }

    if (pathname === "/health") {
      if (request.method === "POST") {
        return this.handleHealthPost(request);
      }
      if (request.method === "GET") {
        return this.handleHealthGet(request);
      }
    }

    if (pathname === "/killswitches") {
      if (request.method === "GET") {
        return this.handleKillSwitchesGet(request);
      }
      if (request.method === "PUT") {
        return this.handleKillSwitchesPut(request);
      }
    }

    return new Response("not found", { status: 404 });
  }
}
