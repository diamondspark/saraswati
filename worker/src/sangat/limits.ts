// Limits for Sangat (listen-together). Values marked "metroserver" are copied
// from MetrolistGroup/metroserver at 9da6f9da1f3ec92183a26dc8f6fbf9c8b3f0ede8
// so Metrolist clients see identical behavior; the rest are Saraswati's own.

// Room codes (metroserver)
export const ROOM_CODE_LENGTH = 8;
export const ROOM_CODE_ALPHABET = "1234567890QWERTYUPASDFGHJLKZXCVBNM";

// Timers (metroserver)
export const RECONNECT_GRACE_MS = 15 * 60 * 1000;
export const EMPTY_ROOM_CLEANUP_MS = 5 * 60 * 1000;

// Text limits (metroserver)
export const USERNAME_MAX = 50;
export const ROOM_CODE_INPUT_MAX = 10;
export const TRACK_ID_MAX = 200;
export const TRACK_TITLE_MAX = 200;
export const TRACK_ARTIST_MAX = 200;
export const TRACK_ALBUM_MAX = 200;
export const TRACK_THUMBNAIL_MAX = 2048;

// Track duration in ms (metroserver): <= 0 becomes the default, then capped.
export const DEFAULT_TRACK_DURATION_MS = 180000;
export const MAX_TRACK_DURATION_MS = 86400000;

// Room limits (metroserver)
export const MAX_QUEUE = 1000;
export const MAX_PENDING_JOINS = 100;
export const MAX_PENDING_SUGGESTIONS = 100;
export const MAX_CLIENTS_PER_ROOM = 100;
// A client may send up to twice MAX_QUEUE tracks; the server keeps at most MAX_QUEUE (metroserver).
export const MAX_QUEUE_INPUT = MAX_QUEUE * 2;
// metroserver allows 10000 rooms; a family hub has a handful of phones.
export const MAX_ROOMS = 100;

// Message limits (metroserver)
export const MAX_READ_MESSAGE_BYTES = 524288;
export const MAX_DECODED_PAYLOAD_BYTES = 1048576;
export const ENVELOPE_TYPE_MAX = 64;

// Rates (metroserver)
export const RATE_LIMIT_MESSAGES_PER_SECOND = 60;
export const RATE_LIMITED_ERROR_CODE = "rate_limited";
export const REQUEST_SYNC_MIN_INTERVAL_MS = 1000;

// Gzip a payload only when it is over this many bytes and gzip makes it
// smaller (metroserver).
export const COMPRESSION_THRESHOLD_BYTES = 100;

// WebSocket close codes (Saraswati)
export const CLOSE_DEVICE_REVOKED = 4001;
export const CLOSE_SANGAT_DISABLED = 4003;

// Phase 4 hub routes (Saraswati)
export const PLAYLIST_ID_PATTERN = /^[A-Za-z0-9_-]{10,64}$/;
export const MEMBER_NAME_MAX = 50;
export const FAMILY_PLAYLIST_TITLE_MAX = 150;
export const FAMILY_PLAYLISTS_MAX = 20;
export const PARIVAAR_WRITES_PER_WINDOW = 10;
export const FAMILY_PLAYLIST_WRITES_PER_WINDOW = 20;
export const WRITE_RATE_WINDOW_SECONDS = 3600;
