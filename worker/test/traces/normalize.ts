// Turns the raw messages a scenario produced into the form stored in a golden
// and compared against the Worker. Values that differ on every run (ids,
// tokens, clocks) become stable placeholders; everything else, including error
// codes and messages, stays verbatim.

export type TraceMessage = { client: string; type: string; payload: object };

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
type Kind = "ROOM" | "USER" | "TOKEN" | "SUGG";

// Field names, in the proto3 JSON the harness decodes to (snake_case).
const ID_FIELDS: Record<string, Kind> = {
  room_code: "ROOM",
  user_id: "USER",
  host_id: "USER",
  new_host_id: "USER",
  from_user_id: "USER",
  waiting_for: "USER",
  session_token: "TOKEN",
  suggestion_id: "SUGG",
};

const TIME_FIELDS = new Set([
  "server_time",
  "server_receive_time",
  "server_send_time",
  "last_update",
  "captured_at_server_time",
]);

function isZero(value: Json): boolean {
  return value === 0 || value === "0";
}

/**
 * Placeholders are numbered per kind in order of first appearance across the
 * whole message list, so `$USER2` means "the second distinct user id seen".
 * Empty strings are not ids and are left alone. A time field that is zero stays
 * zero, so a server that forgets to stamp one still differs from the golden.
 */
export function normalize(messages: TraceMessage[]): object[] {
  const seen: Record<Kind, Map<string, string>> = {
    ROOM: new Map(),
    USER: new Map(),
    TOKEN: new Map(),
    SUGG: new Map(),
  };

  const placeholder = (kind: Kind, value: string): string => {
    if (value === "") return value;
    const known = seen[kind].get(value);
    if (known !== undefined) return known;
    const next = `$${kind}${seen[kind].size + 1}`;
    seen[kind].set(value, next);
    return next;
  };

  const walk = (node: Json): Json => {
    if (Array.isArray(node)) return node.map(walk);
    if (node === null || typeof node !== "object") return node;

    const playing = node.is_playing === true;
    const out: { [key: string]: Json } = {};
    for (const [key, value] of Object.entries(node)) {
      const kind = ID_FIELDS[key];
      if (kind !== undefined && typeof value === "string") {
        out[key] = placeholder(kind, value);
      } else if (kind !== undefined && Array.isArray(value)) {
        out[key] = value.map((v) => (typeof v === "string" ? placeholder(kind, v) : walk(v)));
      } else if (TIME_FIELDS.has(key)) {
        out[key] = isZero(value) ? value : "$TIME";
      } else if (key === "position" && playing) {
        out[key] = "$POS";
      } else {
        out[key] = walk(value);
      }
    }
    return out;
  };

  return messages.map((m) => ({
    client: m.client,
    type: m.type,
    payload: walk(m.payload as Json) as object,
  }));
}
