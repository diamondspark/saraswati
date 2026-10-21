import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";
import type { HubContext, RouteHandler } from "./hubContext";
import { bindingRunner } from "./ask/ai";
import {
  isNonEmptyString,
  json,
  parseJsonObject,
  readBoundedBody,
  readJsonBody,
  readStrictJsonObject,
} from "./http";
import { migrate } from "./migrations";
import { bearerToken, matchRoute } from "./routes";
import { tooLarge } from "./routes/common";
import {
  addFamilyPlaylist,
  deleteFamilyPlaylist,
  listFamilyPlaylists,
} from "./routes/familyPlaylists";
import { handleAsk } from "./routes/ask";
import { listFeedback, postFeedback, setFeedbackStatus } from "./routes/feedback";
import { listParivaarPlaylists, putParivaarPlaylist } from "./routes/parivaar";
import { CLOSE_DEVICE_REVOKED, CLOSE_SANGAT_DISABLED } from "./sangat/limits";
import { Sangat, type LiveRoom } from "./sangat/Sangat";
import { normalizeRoomCode } from "./sangat/sanitize";
import { randomHex, secretsMatch, sha256Hex } from "./secrets";

const PAIRING_TOKEN_TTL_MS = 15 * 60 * 1000;
// A claimed phone waits for the owner's approval. Past this it can no longer be
// approved, so a request nobody noticed cannot be waved through hours later.
const PENDING_APPROVAL_TTL_MS = 60 * 60 * 1000;
// A configured ADMIN_KEY shorter than this (after trimming) is treated as not set at all, so a
// weak or truncated secret fails closed instead of being brute-forceable.
const MIN_ADMIN_KEY_LENGTH = 32;
// Names are shown to the owner when approving. Cap them, and strip anything
// that can reorder, hide or forge what is displayed (control and format
// characters, which include bidi overrides and zero-width characters; see
// STRIPPED_NAME_CHARACTERS for the two joiners that are kept).
const MAX_DEVICE_NAME_LENGTH = 64;
const MAX_STATIONS = 6;
// PUT /sangat/car: a room code and a boolean. The code is checked at 32 characters before it
// is normalized (which cuts it to 10 bytes), so a huge string is never cleaned.
const MAX_CAR_BODY_BYTES = 1024;
const MAX_CAR_ROOM_CODE_LENGTH = 32;

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

// Mirrors the app's FamilyFeature enum (which gains ASK_SARASWATI with the Ask client). Order
// here is the order the kill-switch map is returned in.
const FAMILY_FEATURE_KEYS = [
  "sangat",
  "parivaar_mix",
  "family_playlists",
  "cast_relay",
  "ask_saraswati",
  // SARASWATI: feedback — the in-app bug/idea inbox.
  "feedback",
] as const;
export type FamilyFeatureKey = (typeof FAMILY_FEATURE_KEYS)[number];

// What the owner has set a phone up as. Not to be confused with AuthResult's
// "admin" | "device" `role`, which says who is calling.
const DEVICE_ROLES = ["adult", "grandparent"] as const;
type DeviceRole = (typeof DEVICE_ROLES)[number];
const DEFAULT_DEVICE_ROLE: DeviceRole = "adult";
const ROLE_ERROR = `role must be one of: ${DEVICE_ROLES.join(", ")}`;

export type AuthResult =
  | { role: "admin" }
  | { role: "device"; deviceId: string }
  | null;

// Row shapes extend Record<string, SqlStorageValue> (rather than being
// plain interfaces) because SqlStorage.exec<T>()'s generic constraint
// requires an explicit index signature, not just structurally-compatible
// named properties.
interface PairingTokenRow extends Record<string, SqlStorageValue> {
  expiresAt: number;
  usedAt: number | null;
}

interface RosterRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  deviceName: string | null;
  status: string;
  approvedAt: number | null;
  role: string;
  claimedAt: number | null;
}

interface RosterStatusRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  status: string;
}

interface RosterApprovalRow extends RosterStatusRow {
  claimedAt: number | null;
}

interface DeviceAuthRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  sessionTokenHash: string;
  claimSecretHash: string | null;
  pendingSessionToken: string | null;
}

interface ClaimStatusRow extends Record<string, SqlStorageValue> {
  status: string;
  pendingSessionToken: string | null;
  claimSecretHash: string | null;
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

// Control (Cc) and format (Cf) characters, except the two joiners. Cf holds the
// bidi controls (U+200E/F, U+202A-E, U+2066-9, U+061C) and zero-width
// characters, which can reorder or hide what the owner reads. ZWJ (U+200D) and
// ZWNJ (U+200C) are Cf too, but Devanagari conjuncts and emoji sequences are
// spelled with them, so they stay.
const STRIPPED_NAME_CHARACTERS = /(?![\u200C\u200D])[\p{Cc}\p{Cf}]/gu;

/**
 * The name to store, or null when nothing is left of it. Stripped characters
 * go first, then surrounding whitespace, then the cap, counted in characters
 * rather than UTF-16 units so an emoji is never cut in half. Emptiness is
 * judged on the final capped name, so a run of joiners that pushes the visible
 * letters past the cap cannot slip through as an invisible name: a name made
 * only of joiners and whitespace counts as empty.
 */
function cleanDeviceName(raw: string): string | null {
  const cleaned = raw.replace(STRIPPED_NAME_CHARACTERS, "").trim();
  const capped = Array.from(cleaned)
    .slice(0, MAX_DEVICE_NAME_LENGTH)
    .join("")
    .trim();
  return capped.replace(/[\u200C\u200D\s]/g, "") === "" ? null : capped;
}

function isDeviceRole(value: unknown): value is DeviceRole {
  return (DEVICE_ROLES as readonly unknown[]).includes(value);
}

function isFamilyFeatureKey(key: string): key is FamilyFeatureKey {
  return (FAMILY_FEATURE_KEYS as readonly string[]).includes(key);
}

/** A room as the hub's JSON routes show it: LiveRoom with startedAt as an ISO time. */
function liveRoomJson(room: LiveRoom) {
  return { ...room, startedAt: new Date(room.startedAt).toISOString() };
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
  private readonly hubContext: HubContext;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sangat = new Sangat(ctx);
    this.hubContext = {
      sql: ctx.storage.sql,
      rateLimit: (key, windowSeconds, max) =>
        this.rateLimit(key, windowSeconds, max),
      featureEnabled: (key) => this.readKillSwitches()[key],
      isApproved: (deviceId) =>
        ctx.storage.sql
          .exec("SELECT 1 FROM roster WHERE deviceId = ? AND status = 'approved'", deviceId)
          .toArray().length > 0,
      now: () => Date.now(),
    };

    // Nothing is served until the schema is current. A migration that throws
    // resets the object, so a half-migrated hub answers nothing at all.
    ctx.blockConcurrencyWhile(async () => {
      await migrate(ctx.storage);
      // INSERT OR IGNORE keeps any key the owner has already flipped; a
      // feature key added to FAMILY_FEATURE_KEYS later seeds itself as enabled.
      for (const key of FAMILY_FEATURE_KEYS) {
        ctx.storage.sql.exec(
          "INSERT OR IGNORE INTO killswitches (key, enabled) VALUES (?, 1)",
          key,
        );
      }
    });
  }

  /**
   * Shared auth check for every route: the admin bearer token first, then
   * an approved roster row's own session token. `null` means
   * unauthenticated. Tasks 4 and 5 reuse this rather than re-checking auth
   * their own way.
   *
   * The roster read must stay the last thing it does: the digests below are
   * awaited before it, and nothing is awaited after it. A revoke runs between
   * two events of the Durable Object; if this yielded to anything that opens
   * the input gate (a fetch, a timer, a stream) after the read, a revoke could
   * land between a device being authenticated and its socket being accepted,
   * and /ws would hand a socket to a device that was just cut off. A revoke
   * that lands during the digests is harmless: the read then sees it.
   */
  private async authenticate(
    request: Request,
    env: Env,
  ): Promise<AuthResult> {
    const token = bearerToken(request);
    if (token === null) {
      return null;
    }

    // Both sides are hashed first, so the comparison is over fixed-length
    // digests and says nothing about the length of either secret.
    const presented = await sha256Hex(token);

    // The configured key is trimmed the way bearerToken() trims the presented
    // one, so stray whitespace around the stored secret (a leading space
    // from a terminal paste; `wrangler secret put` only trims the end) cannot
    // make the two differ for good. The length floor applies to what is left.
    const configured: unknown = env.ADMIN_KEY;
    const adminKey = typeof configured === "string" ? configured.trim() : "";
    if (
      adminKey.length >= MIN_ADMIN_KEY_LENGTH &&
      secretsMatch(presented, await sha256Hex(adminKey))
    ) {
      return { role: "admin" };
    }

    // Only the hash of a session token is stored, so every approved device's
    // hash is compared in constant time, none of them skipped once one matches.
    const rows = this.ctx.storage.sql
      .exec<DeviceAuthRow>(
        "SELECT deviceId, sessionTokenHash, claimSecretHash, pendingSessionToken FROM roster WHERE status = 'approved' AND sessionTokenHash IS NOT NULL",
      )
      .toArray();
    let matched: DeviceAuthRow | null = null;
    for (const row of rows) {
      if (secretsMatch(presented, row.sessionTokenHash)) {
        matched = row;
      }
    }

    if (matched) {
      // The phone has now proven it holds its sessionToken, so the plaintext
      // copy kept for /pairing/status and the claimSecret that fetched it have
      // done their job. One write per device, ever: the guard skips every
      // later authentication.
      if (
        matched.claimSecretHash !== null ||
        matched.pendingSessionToken !== null
      ) {
        this.ctx.storage.sql.exec(
          "UPDATE roster SET claimSecretHash = NULL, pendingSessionToken = NULL WHERE deviceId = ?",
          matched.deviceId,
        );
      }
      return { role: "device", deviceId: matched.deviceId };
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

    // The owner gets the token once, in this response; only its hash is kept.
    this.ctx.storage.sql.exec(
      "INSERT INTO pairing_tokens (tokenHash, expiresAt, usedAt) VALUES (?, ?, NULL)",
      await sha256Hex(token),
      expiresAtMs,
    );

    return json({ token, expiresAt: new Date(expiresAtMs).toISOString() });
  }

  /**
   * Spends a pairing token and creates its pending device in one transaction.
   * The UPDATE only matches a token that is still unused and unexpired, so a
   * token already spent (or one that lapsed) since the caller looked at it
   * changes no row, and then no device is created either. True means this call
   * won the token.
   */
  private claimPairingToken(
    tokenHash: string,
    now: number,
    device: {
      deviceId: string;
      deviceName: string | null;
      claimSecretHash: string;
      role: DeviceRole;
    },
  ): boolean {
    return this.ctx.storage.transactionSync(() => {
      const spend = this.ctx.storage.sql.exec(
        "UPDATE pairing_tokens SET usedAt = ? WHERE tokenHash = ? AND usedAt IS NULL AND expiresAt >= ?",
        now,
        tokenHash,
        now,
      );
      spend.toArray();
      if (spend.rowsWritten === 0) {
        return false;
      }
      this.ctx.storage.sql.exec(
        "INSERT INTO roster (deviceId, deviceName, status, sessionTokenHash, pendingSessionToken, approvedAt, claimSecretHash, role, claimedAt) VALUES (?, ?, 'pending', NULL, NULL, NULL, ?, ?, ?)",
        device.deviceId,
        device.deviceName,
        device.claimSecretHash,
        device.role,
        now,
      );
      return true;
    });
  }

  private async handlePairingClaim(request: Request): Promise<Response> {
    const body = await readJsonBody(request);
    const token = typeof body.token === "string" ? body.token : "";

    if (!token) {
      return json({ error: "not found" }, 404);
    }

    // Like the role below, checked before the pairing token is looked up or
    // spent, so a bad name never burns the owner's link.
    let deviceName: string | null = null;
    if (typeof body.deviceName === "string") {
      deviceName = cleanDeviceName(body.deviceName);
      if (deviceName === null) {
        return json({ error: "deviceName must not be empty" }, 400);
      }
    }

    // Checked before the token is looked up or spent, so a malformed role
    // never burns the owner's pairing token and the phone can simply retry.
    const role: unknown = "role" in body ? body.role : DEFAULT_DEVICE_ROLE;
    if (!isDeviceRole(role)) {
      return json({ error: ROLE_ERROR }, 400);
    }

    const tokenHash = await sha256Hex(token);
    const rows = this.ctx.storage.sql
      .exec<PairingTokenRow>(
        "SELECT expiresAt, usedAt FROM pairing_tokens WHERE tokenHash = ?",
        tokenHash,
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
        `claim:${tokenHash}`,
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
    // Hashed before the write below, so nothing is awaited between the last
    // check and the write that depends on it.
    const claimSecretHash = await sha256Hex(claimSecret);
    const spent = this.claimPairingToken(tokenHash, Date.now(), {
      deviceId,
      deviceName,
      claimSecretHash,
      role,
    });
    if (!spent) {
      // Lost a race for the same token between the check and the write.
      return json({ error: "gone" }, 410);
    }

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
        "SELECT status, pendingSessionToken, claimSecretHash FROM roster WHERE deviceId = ?",
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
      row.claimSecretHash === null ||
      !secretsMatch(await sha256Hex(claimSecret), row.claimSecretHash) ||
      (row.status !== "pending" && row.status !== "approved")
    ) {
      return json({ error: "not found" }, 404);
    }

    if (row.status === "approved" && row.pendingSessionToken !== null) {
      return json({
        status: "approved",
        sessionToken: row.pendingSessionToken,
      });
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
        "SELECT deviceId, deviceName, status, approvedAt, role, claimedAt FROM roster",
      )
      .toArray()
      .map((row) => ({
        deviceId: row.deviceId,
        deviceName: row.deviceName,
        status: row.status,
        approvedAt: row.approvedAt,
        role: row.role,
        claimedAt: row.claimedAt,
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

    const refusal = this.approvalRefusal(deviceId);
    if (refusal) {
      return refusal;
    }

    // The owner is never shown the phone's credential. The plaintext waits in
    // pendingSessionToken only until the phone collects it through
    // /pairing/status and first uses it; the lookup key is its hash. Hashed
    // before the write, so nothing is awaited between the last check and it.
    const sessionToken = randomHex(32);
    const approved = this.commitApproval(
      deviceId,
      await sha256Hex(sessionToken),
      sessionToken,
      Date.now(),
    );
    if (!approved) {
      // The row changed (revoked, approved, or lapsed) since the check above;
      // answer for what it is now.
      return this.approvalRefusal(deviceId) ?? json({ error: "not found" }, 404);
    }

    return json({ deviceId, status: "approved" });
  }

  /** The 404 or 410 that stops an approval, or null when it may go ahead. */
  private approvalRefusal(deviceId: string): Response | null {
    const rows = this.ctx.storage.sql
      .exec<RosterApprovalRow>(
        "SELECT deviceId, status, claimedAt FROM roster WHERE deviceId = ?",
        deviceId,
      )
      .toArray();

    if (rows.length === 0 || rows[0].status !== "pending") {
      return json({ error: "not found" }, 404);
    }

    // A request with no recorded claim time is as untrustworthy as an old one.
    const claimedAt = rows[0].claimedAt;
    if (claimedAt === null || Date.now() - claimedAt > PENDING_APPROVAL_TTL_MS) {
      return json({ error: "expired" }, 410);
    }
    return null;
  }

  /**
   * The write that approves a device. It only matches a row that is still
   * pending and still inside the hour, so a device revoked, already approved
   * or lapsed since the caller's check is left exactly as it is (a revoked
   * row is never brought back). True means this call approved it.
   */
  private commitApproval(
    deviceId: string,
    sessionTokenHash: string,
    sessionToken: string,
    now: number,
  ): boolean {
    const update = this.ctx.storage.sql.exec(
      "UPDATE roster SET status = 'approved', sessionTokenHash = ?, pendingSessionToken = ?, approvedAt = ? WHERE deviceId = ? AND status = 'pending' AND claimedAt IS NOT NULL AND claimedAt >= ?",
      sessionTokenHash,
      sessionToken,
      now,
      deviceId,
      now - PENDING_APPROVAL_TTL_MS,
    );
    update.toArray();
    return update.rowsWritten > 0;
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
      "UPDATE roster SET status = 'revoked', sessionTokenHash = NULL, pendingSessionToken = NULL, claimSecretHash = NULL WHERE deviceId = ?",
      deviceId,
    );
    // The same turn as the update, with nothing awaited in between: no upgrade can
    // authenticate the device in the gap. Its sockets close at once (4001) and its seats,
    // and with them its reconnect tokens, go; waiting for a reconnect would be too late.
    this.sangat.closeDevice(deviceId, CLOSE_DEVICE_REVOKED, "revoked");
    // Its Parivaar row goes too (the list hides it anyway). Its family playlists stay: they
    // belong to the family, and only the admin can remove them now.
    this.ctx.storage.sql.exec(
      "DELETE FROM parivaar_playlists WHERE deviceId = ?",
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
    // Switching Sangat off ends it now: every socket closes (4003, which the app does
    // not retry) and every room goes, so a re-enable starts with none.
    if (changes.some(([key, enabled]) => key === "sangat" && !enabled)) {
      this.sangat.closeAll(CLOSE_SANGAT_DISABLED, "sangat disabled");
    }

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

    if (!this.sangat.hasRoomForSocket(auth.deviceId)) {
      return json({ error: "too many connections" }, 429);
    }

    return this.sangat.accept(auth.deviceId);
  }

  /** GET /sangat/live: the rooms whose host is connected. A device or the admin may ask. */
  private async handleSangatLive(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (!auth) {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    if (!this.readKillSwitches().sangat) {
      return json({ error: "feature disabled" }, 503);
    }

    return json({ rooms: this.sangat.liveRooms().map(liveRoomJson) });
  }

  /**
   * PUT /sangat/car: the host's phone says it is driving. Car mode is set over
   * HTTP rather than the WebSocket protocol, which has no message for it.
   */
  private async handleSangatCar(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "device") {
      return json({ error: "unauthorized" }, 401);
    }

    if (!this.readKillSwitches().sangat) {
      return json({ error: "feature disabled" }, 503);
    }

    const bytes = await readBoundedBody(request, MAX_CAR_BODY_BYTES);
    if (bytes === null) {
      return tooLarge();
    }
    const body = parseJsonObject(bytes);
    const rawRoomCode = body?.roomCode;
    if (typeof rawRoomCode === "string" && rawRoomCode.length > MAX_CAR_ROOM_CODE_LENGTH) {
      return json({ error: `roomCode must be at most ${MAX_CAR_ROOM_CODE_LENGTH} characters` }, 400);
    }
    // The code is read as join_room reads it, so what opens a room opens its car switch.
    const roomCode = typeof rawRoomCode === "string" ? normalizeRoomCode(rawRoomCode) : "";
    const carMode = body?.carMode;
    if (roomCode === "" || typeof carMode !== "boolean") {
      return json({ error: "roomCode and a boolean carMode are required" }, 400);
    }

    const result = this.sangat.setCarMode(roomCode, auth.deviceId, carMode);
    if (result === "not_found") {
      return json({ error: "room not found" }, 404);
    }
    if (result === "not_host") {
      return json({ error: "only the host device can change car mode" }, 403);
    }

    const room = this.sangat.roomView(roomCode);
    return room ? json(liveRoomJson(room)) : json({ error: "room not found" }, 404);
  }

  /**
   * Runs one of the Parivaar / family-playlist handlers. "device" routes are closed to the
   * admin bearer, so an admin never reaches them or spends the shared admin budget on them;
   * "any" routes take a device or the admin, and the admin's call is counted as everywhere
   * else. The handler does the rest: kill switch, body (size, then JSON), and for a write the
   * roster re-check after the body is read, then the per-device write limit.
   */
  private async runRoute(
    request: Request,
    access: "device" | "any",
    handler: RouteHandler,
  ): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (!auth || (access === "device" && auth.role !== "device")) {
      return json({ error: "unauthorized" }, 401);
    }

    const throttled = this.throttleAdmin(auth);
    if (throttled) {
      return throttled;
    }

    return handler(this.hubContext, request, auth);
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
    const route = matchRoute(request.method, new URL(request.url).pathname);
    if (!route) {
      return new Response("not found", { status: 404 });
    }

    switch (route.name) {
      case "ping":
        return new Response("ok", { status: 200 });
      case "pairingCreate":
        return this.handlePairingCreate(request);
      case "pairingClaim":
        return this.handlePairingClaim(request);
      case "pairingStatus":
        return this.handlePairingStatus(request);
      case "rosterList":
        return this.handleRosterList(request);
      case "rosterApprove":
        return this.handleRosterApprove(request, route.deviceId as string);
      case "rosterRevoke":
        return this.handleRosterRevoke(request, route.deviceId as string);
      case "rosterRole":
        return this.handleRosterRole(request, route.deviceId as string);
      case "me":
        return this.handleMe(request);
      case "stationsGet":
        return this.handleStationsGet(request);
      case "stationsPut":
        return this.handleStationsPut(request);
      case "healthPost":
        return this.handleHealthPost(request);
      case "healthGet":
        return this.handleHealthGet(request);
      case "killswitchesGet":
        return this.handleKillSwitchesGet(request);
      case "killswitchesPut":
        return this.handleKillSwitchesPut(request);
      case "sangatSocket":
        return this.handleSangatSocket(request);
      case "sangatLive":
        return this.handleSangatLive(request);
      case "sangatCar":
        return this.handleSangatCar(request);
      case "parivaarPlaylistPut":
        return this.runRoute(request, "device", putParivaarPlaylist);
      case "parivaarPlaylistsList":
        return this.runRoute(request, "any", listParivaarPlaylists);
      case "ask":
        return this.runRoute(request, "device", (ctx, req, auth) =>
          handleAsk(ctx, req, auth, bindingRunner(this.env.AI)),
        );
      // SARASWATI: feedback. POST takes a device or the admin; the handlers of the other two
      // answer 401 to a device themselves.
      case "feedbackPost":
        return this.runRoute(request, "any", postFeedback);
      case "feedbackList":
        return this.runRoute(request, "any", listFeedback);
      case "feedbackSetStatus": {
        const id = route.feedbackId as string;
        return this.runRoute(request, "any", (ctx, req, auth) =>
          setFeedbackStatus(ctx, req, auth, id),
        );
      }
      case "familyPlaylistsList":
        return this.runRoute(request, "any", listFamilyPlaylists);
      case "familyPlaylistsAdd":
        return this.runRoute(request, "device", addFamilyPlaylist);
      case "familyPlaylistsDelete": {
        const id = route.familyPlaylistId as string;
        return this.runRoute(request, "any", (ctx, req, auth) =>
          deleteFamilyPlaylist(ctx, req, auth, id),
        );
      }
    }
  }
}
