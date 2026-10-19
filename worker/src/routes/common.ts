// What the Parivaar and family-playlist routes share: the playlistId and text rules,
// the stock error answers, and the ISO conversion for times stored as epoch milliseconds.

import { json } from "../http";

// A YouTube playlist id as the app sends it. No trimming: a stray space is a 400.
const PLAYLIST_ID = /^[A-Za-z0-9_-]{10,64}$/;
export const PLAYLIST_ID_ERROR =
  "playlistId must be 10-64 characters: letters, digits, _ or -";

export function isPlaylistId(value: unknown): value is string {
  return typeof value === "string" && PLAYLIST_ID.test(value);
}

/** The trimmed text when it has 1 to `maxLength` characters once trimmed, else null. */
export function cleanText(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const trimmed = value.trim();
  return trimmed.length >= 1 && trimmed.length <= maxLength ? trimmed : null;
}

export const unauthorized = () => json({ error: "unauthorized" }, 401);
export const featureDisabled = () => json({ error: "feature disabled" }, 503);
export const tooManyRequests = () => json({ error: "too many requests" }, 429);

export const isoTime = (epochMs: number) => new Date(epochMs).toISOString();
