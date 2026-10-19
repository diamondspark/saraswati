// Port of metroserver's validation.go and the queue helpers in playback.go.
// Lengths are in UTF-8 bytes, as in Go.

import { create } from "@bufbuild/protobuf";
import { TrackInfoSchema, type TrackInfo } from "../gen/listentogether_pb";
import {
  DEFAULT_TRACK_DURATION_MS,
  MAX_QUEUE,
  MAX_TRACK_DURATION_MS,
  TRACK_ALBUM_MAX,
  TRACK_ARTIST_MAX,
  TRACK_ID_MAX,
  TRACK_THUMBNAIL_MAX,
  TRACK_TITLE_MAX,
  USERNAME_MAX,
} from "./limits";

const encoder = new TextEncoder();
const strictDecoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
const lenientDecoder = new TextDecoder("utf-8", { fatal: false, ignoreBOM: true });

// The characters Go's strings.TrimSpace removes (unicode.IsSpace). JavaScript's
// trim() differs at U+0085 and U+FEFF.
const GO_SPACE = "\\t\\n\\v\\f\\r \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000";
const TRIM = new RegExp(`^[${GO_SPACE}]+|[${GO_SPACE}]+$`, "g");

function isValidUtf8(bytes: Uint8Array): boolean {
  try {
    strictDecoder.decode(bytes);
    return true;
  } catch {
    return false;
  }
}

/**
 * Strips control characters (keeping tab, newline and carriage return), trims
 * whitespace, then cuts to `maxBytes` UTF-8 bytes without splitting a character.
 */
export function sanitizeString(input: string, maxBytes: number): string {
  let cleaned = "";
  for (const ch of input) {
    const cp = ch.codePointAt(0) as number;
    if (cp === 0 || (cp < 32 && cp !== 9 && cp !== 10 && cp !== 13)) continue;
    cleaned += ch;
  }
  cleaned = cleaned.replace(TRIM, "");

  const bytes = encoder.encode(cleaned);
  if (bytes.byteLength <= maxBytes) return cleaned;
  for (let i = maxBytes; i > 0 && i > maxBytes - 4; i--) {
    const head = bytes.subarray(0, i);
    if (isValidUtf8(head)) return lenientDecoder.decode(head);
  }
  return lenientDecoder.decode(bytes.subarray(0, maxBytes));
}

/** A cleaned copy of `track`, or null when it has no id or no title. */
export function sanitizeTrackInfo(track: TrackInfo): TrackInfo | null {
  const id = sanitizeString(track.id, TRACK_ID_MAX);
  const title = sanitizeString(track.title, TRACK_TITLE_MAX);
  if (id === "" || title === "") return null;

  let duration = track.duration;
  if (duration <= 0n) duration = BigInt(DEFAULT_TRACK_DURATION_MS);
  else if (duration > BigInt(MAX_TRACK_DURATION_MS)) duration = BigInt(MAX_TRACK_DURATION_MS);

  return create(TrackInfoSchema, {
    id,
    title,
    artist: sanitizeString(track.artist, TRACK_ARTIST_MAX),
    album: sanitizeString(track.album, TRACK_ALBUM_MAX),
    duration,
    thumbnail: sanitizeString(track.thumbnail, TRACK_THUMBNAIL_MAX),
    suggestedBy: sanitizeString(track.suggestedBy, USERNAME_MAX),
  });
}

/** Valid tracks only, first occurrence of each id, at most MAX_QUEUE of them. */
export function sanitizeQueue(queue: TrackInfo[]): TrackInfo[] {
  const seen = new Set<string>();
  const out: TrackInfo[] = [];
  for (const entry of queue) {
    const track = sanitizeTrackInfo(entry);
    if (!track || seen.has(track.id)) continue;
    seen.add(track.id);
    out.push(track);
    if (out.length === MAX_QUEUE) break;
  }
  return out;
}

/** sanitizeQueue, after dropping the entries that are the track now playing. */
export function sanitizeUpcomingQueue(queue: TrackInfo[], currentTrackId: string): TrackInfo[] {
  if (currentTrackId === "") return sanitizeQueue(queue);
  return sanitizeQueue(queue.filter((track) => track.id !== currentTrackId));
}
