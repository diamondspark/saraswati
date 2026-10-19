import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";

const PAIRING_TOKEN_TTL_MS = 15 * 60 * 1000;

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
}

interface RosterStatusRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  status: string;
}

interface RosterIdRow extends Record<string, SqlStorageValue> {
  deviceId: string;
}

function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
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
 * Single global Durable Object backing the Saraswati family hub.
 *
 * Routing to the one instance (idFromName("family")) happens in
 * index.ts, not here. This class owns the instance's SQLite storage
 * and request handling.
 */
export class FamilyHub extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    // Later tasks add stations, killswitches, health, and rate_limits.
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
      approvedAt INTEGER
    )`);
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
      .exec<RosterRow>(
        "SELECT deviceId, deviceName, status, sessionToken, approvedAt FROM roster WHERE sessionToken = ? AND status = 'approved'",
        token,
      )
      .toArray();

    if (rows.length === 1) {
      return { role: "device", deviceId: rows[0].deviceId };
    }

    return null;
  }

  private async handlePairingCreate(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
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

    const rows = this.ctx.storage.sql
      .exec<PairingTokenRow>(
        "SELECT token, expiresAt, usedAt FROM pairing_tokens WHERE token = ?",
        token,
      )
      .toArray();

    if (rows.length === 0) {
      return json({ error: "not found" }, 404);
    }

    const row = rows[0];
    if (row.usedAt !== null || row.expiresAt < Date.now()) {
      return json({ error: "gone" }, 410);
    }

    const deviceId = crypto.randomUUID();
    this.ctx.storage.sql.exec(
      "INSERT INTO roster (deviceId, deviceName, status, sessionToken, approvedAt) VALUES (?, ?, 'pending', NULL, NULL)",
      deviceId,
      deviceName,
    );
    this.ctx.storage.sql.exec(
      "UPDATE pairing_tokens SET usedAt = ? WHERE token = ?",
      Date.now(),
      token,
    );

    return json({ deviceId, status: "pending" });
  }

  private async handleRosterList(request: Request): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
    }

    const devices = this.ctx.storage.sql
      .exec<RosterRow>(
        "SELECT deviceId, deviceName, status, sessionToken, approvedAt FROM roster",
      )
      .toArray()
      .map((row) => ({
        deviceId: row.deviceId,
        deviceName: row.deviceName,
        status: row.status,
        approvedAt: row.approvedAt,
      }));

    return json({ devices });
  }

  private async handleRosterApprove(
    request: Request,
    deviceId: string,
  ): Promise<Response> {
    const auth = await this.authenticate(request, this.env);
    if (auth?.role !== "admin") {
      return json({ error: "unauthorized" }, 401);
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
      "UPDATE roster SET status = 'revoked', sessionToken = NULL WHERE deviceId = ?",
      deviceId,
    );

    return json({ deviceId, status: "revoked" });
  }

  async fetch(request: Request): Promise<Response> {
    const { pathname } = new URL(request.url);

    if (request.method === "GET" && pathname === "/ping") {
      return new Response("ok", { status: 200 });
    }

    if (request.method === "POST" && pathname === "/pairing/create") {
      return this.handlePairingCreate(request);
    }

    if (request.method === "POST" && pathname === "/pairing/claim") {
      return this.handlePairingClaim(request);
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

    return new Response("not found", { status: 404 });
  }
}
