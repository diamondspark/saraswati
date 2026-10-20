// What the Parivaar and family-playlist routes share: the playlistId and text rules,
// the stock error answers, and the ISO conversion for times stored as epoch milliseconds.

import { json, parseJsonObject, readBoundedBody } from "../http";
import type { HubContext } from "../hubContext";

// A YouTube playlist id as the app sends it. No trimming: a stray space is a 400.
const PLAYLIST_ID = /^[A-Za-z0-9_-]{10,64}$/;
export const PLAYLIST_ID_ERROR =
  "playlistId must be 10-64 characters: letters, digits, _ or -";

export function isPlaylistId(value: unknown): value is string {
  return typeof value === "string" && PLAYLIST_ID.test(value);
}

// C0, DEL and C1 controls, and the two line separators, which are not Cc but break a line.
const CONTROL_OR_SEPARATOR = /[\p{Cc}\u2028\u2029]/u;

/**
 * The trimmed text when it has 1 to `maxLength` characters once trimmed and no control
 * character or line separator in it, else null. Inner spaces are fine.
 */
export function cleanText(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length >= 1 && trimmed.length <= maxLength && !CONTROL_OR_SEPARATOR.test(trimmed)
    ? trimmed
    : null;
}

// Both write routes take two short strings; 4 KB is far more than they need.
export const MAX_BODY_BYTES = 4096;

/**
 * The JSON object a write route was sent, or the Response to answer with. This is the only
 * await in such a route. Right after it, before anything is parsed, charged or written, the
 * device is looked up again: a revoke can land while the body is arriving, and the
 * authenticate() that ran before it no longer holds. Then 413 for a body past the cap, then
 * 400 for one that is not a JSON object.
 */
export async function readWriteBody(
  ctx: HubContext,
  request: Request,
  deviceId: string,
): Promise<Record<string, unknown> | Response> {
  const bytes = await readBoundedBody(request, MAX_BODY_BYTES);
  if (!ctx.isApproved(deviceId)) {
    return unauthorized();
  }
  if (bytes === null) {
    return tooLarge();
  }
  return parseJsonObject(bytes) ?? json({ error: "body must be a JSON object" }, 400);
}

export const unauthorized = () => json({ error: "unauthorized" }, 401);
export const featureDisabled = () => json({ error: "feature disabled" }, 503);
export const tooLarge = () => json({ error: "too large" }, 413);
export const tooManyRequests = () => json({ error: "too many requests" }, 429);

export const isoTime = (epochMs: number) => new Date(epochMs).toISOString();
