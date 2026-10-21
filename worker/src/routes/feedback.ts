// SARASWATI: feedback — bug reports and ideas the family sends from the app, read by the owner.
// POST /feedback takes a device or the admin; GET /feedback and PUT /feedback/{id} are the
// admin's. The text is untrusted: it only ever reaches SQL as a bound parameter and leaves as
// JSON data. Checks on POST run in this order: auth (401), kill switch (503), body size (413),
// body (400), rate limit (429). A device revoked while its body was arriving is 401. A stored
// item is mirrored to GitHub when that is switched on (feedback/githubIssue.ts); the mirror
// never changes the answer.

import { json, parseJsonObject, readBoundedBody } from "../http";
import type { AuthResult, HubContext, RouteHandler } from "../hubContext";
import { featureDisabled, tooLarge, tooManyRequests, unauthorized } from "./common";

export const FEEDBACK_KINDS = ["bug", "idea"] as const;
export type FeedbackKind = (typeof FEEDBACK_KINDS)[number];
export const FEEDBACK_STATUSES = ["new", "seen", "planned", "done", "wontfix"] as const;
export type FeedbackStatus = (typeof FEEDBACK_STATUSES)[number];

const OPEN_STATUSES: readonly FeedbackStatus[] = ["new", "seen", "planned"];

const MAX_TEXT_LENGTH = 1000;
const MAX_APP_VERSION_LENGTH = 40;
const MAX_ANDROID_LENGTH = 40;
const MAX_DEVICE_LENGTH = 80;
// The front Worker already refuses a declared body over 8 KiB; this is the same cap for a
// streamed one. 1000 characters of text, even all escaped, fit well inside it.
const MAX_POST_BODY_BYTES = 8192;
const MAX_PUT_BODY_BYTES = 1024;
// Per device: 5 an hour and 20 a day. The hour window is charged before the day window, so a
// request the day limit refuses still uses up one hour slot.
const HOUR_SECONDS = 3600;
const HOUR_MAX = 5;
const DAY_SECONDS = 86400;
const DAY_MAX = 20;

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 100;
const OWNER_DEVICE_ID = "owner";
const OWNER_NICKNAME = "Owner";
const UNNAMED_NICKNAME = "Family member";

// Every C0 and C1 control except \n, and the two Unicode line separators. \r goes, so \r\n
// becomes \n. Bidi marks and joiners are not controls and survive, so RTL text round-trips.
// Invisible characters are stripped too: they can hide instructions from the owner reading the
// inbox but not from an agent reading the same text. Tag characters, soft hyphen, Mongolian
// vowel separator, zero-width space, word joiner and invisible operators, bidi embeddings,
// overrides and isolates, the BOM and the interlinear annotation marks. U+200C/200D (ZWNJ and ZWJ,
// needed for Hindi conjuncts and emoji sequences), U+200E/200F and U+061C (the RTL marks) stay.
const TEXT_STRIP =
  /[^\P{Cc}\n]|[\u2028\u2029\u{E0000}-\u{E007F}\u00AD\u180E\u200B\u2060-\u2064\u2066-\u2069\u202A-\u202E\uFEFF\uFFF9-\uFFFB]/gu;
// Diagnostics are one line, so they lose every control, and the same invisible characters.
const LINE_STRIP = /[\p{Cc}\u2028\u2029\u{E0000}-\u{E007F}\u00AD\u180E\u200B\u2060-\u2064\u2066-\u2069\u202A-\u202E\uFEFF\uFFF9-\uFFFB]/gu;

export interface FeedbackInput {
  deviceId: string;
  nickname: string;
  kind: FeedbackKind;
  text: string;
  appVersion: string | null;
  android: string | null;
  device: string | null;
}

export interface FeedbackRow extends Record<string, SqlStorageValue> {
  id: string;
  device_id: string;
  nickname: string;
  kind: FeedbackKind;
  text: string;
  app_version: string | null;
  android: string | null;
  device: string | null;
  status: FeedbackStatus;
  created_at: number;
  updated_at: number;
  issue_url: string | null;
}

/** Stores one feedback item as 'new' and returns the stored row. Bound parameters only. */
export function storeFeedback(ctx: HubContext, input: FeedbackInput): FeedbackRow {
  const now = ctx.now();
  const row: FeedbackRow = {
    id: crypto.randomUUID(),
    device_id: input.deviceId,
    nickname: input.nickname,
    kind: input.kind,
    text: input.text,
    app_version: input.appVersion,
    android: input.android,
    device: input.device,
    status: "new",
    created_at: now,
    updated_at: now,
    issue_url: null,
  };
  ctx.sql.exec(
    `INSERT INTO feedback
       (id, device_id, nickname, kind, text, app_version, android, device, status,
        created_at, updated_at, issue_url)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    row.id,
    row.device_id,
    row.nickname,
    row.kind,
    row.text,
    row.app_version,
    row.android,
    row.device,
    row.status,
    row.created_at,
    row.updated_at,
    row.issue_url,
  );
  return row;
}

const isKind = (value: unknown): value is FeedbackKind =>
  (FEEDBACK_KINDS as readonly unknown[]).includes(value);
const isStatus = (value: unknown): value is FeedbackStatus =>
  (FEEDBACK_STATUSES as readonly unknown[]).includes(value);

/** The cleaned text (tabs to a space, other controls and invisible characters stripped but \n, trimmed, 1..1000 chars), else null. */
function cleanFeedbackText(value: unknown): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const text = value.replace(/\t/g, " ").replace(TEXT_STRIP, "").trim();
  return text.length >= 1 && text.length <= MAX_TEXT_LENGTH ? text : null;
}

/**
 * One single-line diagnostics field: undefined or null is "not sent" (null result), a string is
 * cleaned and cut to the cap, anything else is invalid (undefined result).
 */
function cleanDiagnostic(value: unknown, maxLength: number): string | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (typeof value !== "string") {
    return undefined;
  }
  let cleaned = value.replace(LINE_STRIP, "").trim();
  if (cleaned.length > maxLength) {
    cleaned = cleaned.slice(0, maxLength);
    // Never leave half of a surrogate pair at the cut.
    if (/[\ud800-\udbff]$/.test(cleaned)) {
      cleaned = cleaned.slice(0, -1);
    }
    cleaned = cleaned.trimEnd();
  }
  return cleaned === "" ? null : cleaned;
}

const invalid = (error: string) => json({ error }, 400);

const rosterNickname = (ctx: HubContext, deviceId: string): string => {
  const row = ctx.sql
    .exec<{ deviceName: string | null } & Record<string, SqlStorageValue>>(
      "SELECT deviceName FROM roster WHERE deviceId = ?",
      deviceId,
    )
    .toArray()[0];
  return row?.deviceName ? row.deviceName : UNNAMED_NICKNAME;
};

/** POST /feedback: device or admin. 201 with the new id. */
export const postFeedback: RouteHandler = async (ctx, request, auth) => {
  if (!auth) {
    return unauthorized();
  }
  if (!ctx.featureEnabled("feedback")) {
    return featureDisabled();
  }

  // Nothing is awaited after this, so the revoke check holds up to the write.
  const bytes = await readBoundedBody(request, MAX_POST_BODY_BYTES);
  if (auth.role === "device" && !ctx.isApproved(auth.deviceId)) {
    return unauthorized();
  }
  if (bytes === null) {
    return tooLarge();
  }
  const body = parseJsonObject(bytes);
  if (body === null) {
    return invalid("body must be a JSON object");
  }

  const kind = body.kind;
  if (!isKind(kind)) {
    return invalid("kind must be bug or idea");
  }
  const text = cleanFeedbackText(body.text);
  if (text === null) {
    return invalid(`text must be 1-${MAX_TEXT_LENGTH} characters`);
  }

  let appVersion: string | null = null;
  let android: string | null = null;
  let device: string | null = null;
  const diagnostics = body.diagnostics;
  if (diagnostics !== undefined && diagnostics !== null) {
    if (typeof diagnostics !== "object" || Array.isArray(diagnostics)) {
      return invalid("diagnostics must be an object");
    }
    const fields = diagnostics as Record<string, unknown>;
    const cleanedVersion = cleanDiagnostic(fields.appVersion, MAX_APP_VERSION_LENGTH);
    const cleanedAndroid = cleanDiagnostic(fields.android, MAX_ANDROID_LENGTH);
    const cleanedDevice = cleanDiagnostic(fields.device, MAX_DEVICE_LENGTH);
    if (
      cleanedVersion === undefined ||
      cleanedAndroid === undefined ||
      cleanedDevice === undefined
    ) {
      return invalid("diagnostics fields must be strings");
    }
    appVersion = cleanedVersion;
    android = cleanedAndroid;
    device = cleanedDevice;
  }

  // The admin's calls were already counted against the shared admin budget at the door.
  if (auth.role === "device") {
    if (
      !ctx.rateLimit(`feedback:hour:${auth.deviceId}`, HOUR_SECONDS, HOUR_MAX) ||
      !ctx.rateLimit(`feedback:day:${auth.deviceId}`, DAY_SECONDS, DAY_MAX)
    ) {
      return tooManyRequests();
    }
  }

  const row = storeFeedback(ctx, {
    deviceId: auth.role === "device" ? auth.deviceId : OWNER_DEVICE_ID,
    nickname: auth.role === "device" ? rosterNickname(ctx, auth.deviceId) : OWNER_NICKNAME,
    kind,
    text,
    appVersion,
    android,
    device,
  });

  // The row is already stored, so nothing below can change the answer. mirrorFeedback never
  // throws and gives up after 5 s. A Durable Object has no waitUntil, so the 201 waits for it.
  const issueUrl = await ctx.mirrorFeedback(row);
  if (issueUrl !== null) {
    ctx.sql.exec("UPDATE feedback SET issue_url = ? WHERE id = ?", issueUrl, row.id);
  }
  return json({ id: row.id }, 201);
};

/** The fields of a row as the owner's inbox gets them; a field that was not sent is left out. */
const itemJson = (row: FeedbackRow) => ({
  id: row.id,
  kind: row.kind,
  text: row.text,
  status: row.status,
  nickname: row.nickname,
  createdAt: row.created_at,
  ...(row.app_version !== null && { appVersion: row.app_version }),
  ...(row.android !== null && { android: row.android }),
  ...(row.device !== null && { device: row.device }),
  ...(row.issue_url !== null && { issueUrl: row.issue_url }),
});

function statusFilter(param: string | null): readonly FeedbackStatus[] | "all" | null {
  if (param === null || param === "open") {
    return OPEN_STATUSES;
  }
  if (param === "all") {
    return "all";
  }
  return isStatus(param) ? [param] : null;
}

function readLimit(param: string | null): number {
  if (param === null || param.trim() === "") {
    return DEFAULT_LIMIT;
  }
  const parsed = Number(param);
  if (!Number.isFinite(parsed)) {
    return DEFAULT_LIMIT;
  }
  return Math.min(MAX_LIMIT, Math.max(1, Math.floor(parsed)));
}

/** GET /feedback?status=&limit=: admin only, newest first. */
export const listFeedback: RouteHandler = async (ctx, request, auth) => {
  if (auth?.role !== "admin") {
    return unauthorized();
  }

  const params = new URL(request.url).searchParams;
  const statuses = statusFilter(params.get("status"));
  if (statuses === null) {
    return invalid("status must be open, all or one of: " + FEEDBACK_STATUSES.join(", "));
  }
  const limit = readLimit(params.get("limit"));

  // The only SQL text built here is a run of "?" placeholders, one per allow-listed status.
  const where =
    statuses === "all" ? "" : `WHERE status IN (${statuses.map(() => "?").join(", ")})`;
  const rows = ctx.sql
    .exec<FeedbackRow>(
      `SELECT * FROM feedback ${where} ORDER BY created_at DESC, rowid DESC LIMIT ?`,
      ...(statuses === "all" ? [] : statuses),
      limit,
    )
    .toArray();
  return json({ items: rows.map(itemJson) });
};

/** PUT /feedback/{id}: admin only; sets the status. */
export async function setFeedbackStatus(
  ctx: HubContext,
  request: Request,
  auth: AuthResult,
  id: string,
): Promise<Response> {
  if (auth?.role !== "admin") {
    return unauthorized();
  }

  const bytes = await readBoundedBody(request, MAX_PUT_BODY_BYTES);
  if (bytes === null) {
    return tooLarge();
  }
  const body = parseJsonObject(bytes);
  if (body === null) {
    return invalid("body must be a JSON object");
  }
  const status = body.status;
  if (!isStatus(status)) {
    return invalid(`status must be one of: ${FEEDBACK_STATUSES.join(", ")}`);
  }

  if (ctx.sql.exec("SELECT 1 FROM feedback WHERE id = ?", id).toArray().length === 0) {
    return json({ error: "feedback not found" }, 404);
  }
  ctx.sql.exec("UPDATE feedback SET status = ?, updated_at = ? WHERE id = ?", status, ctx.now(), id);
  return json({ ok: true });
}
