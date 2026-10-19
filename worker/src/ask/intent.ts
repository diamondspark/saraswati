// What the model said, turned into one of five intents or a plain search for the person's
// own words. The model never chooses a URL, a station outside the family's, or a kind
// outside the five: anything else is the fallback. parseIntent never throws.

export type AskIntent =
  | { kind: "search"; query: string }
  | { kind: "play_station"; stationId: string }
  | { kind: "parivaar_mix" }
  | { kind: "sangat" }
  | { kind: "answer"; text: string };

const QUERY_MAX = 100;
const TEXT_MAX = 280;
// A 96-token reply is a few hundred characters; anything near this is not an intent.
const RAW_MAX = 1000;

// \p{Cc} covers newline and tab too. Joiners (Cf) stay: Devanagari conjuncts need them.
const CONTROL = /[\p{Cc}\u2028\u2029]/u;
const FENCED = /^```(?:json)?\s*\n?([\s\S]*?)\s*```$/i;

/** Trimmed text of 1 to `max` characters without control characters, else null. */
function cleanField(value: unknown, max: number): string | null {
  if (typeof value !== "string") {
    return null;
  }
  const text = value.trim();
  return text.length >= 1 && text.length <= max && !CONTROL.test(text) ? text : null;
}

/** NFKC folds full-width letters and dots, so "ｗｗｗ．" counts as "www.". */
function hasLink(text: string): boolean {
  const folded = text.normalize("NFKC").toLowerCase();
  return ["://", "www.", "mailto:", "javascript:"].some((marker) => folded.includes(marker));
}

const isPlainObject = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

/** The JSON text of a reply, tolerating exactly one surrounding code fence. */
function jsonText(text: string): string | null {
  if (text.length > RAW_MAX) {
    return null;
  }
  const trimmed = text.trim();
  // A second fence, or prose around the first, is left inside and fails to parse.
  return FENCED.exec(trimmed)?.[1] ?? trimmed;
}

/** What the reply claims to be: a string response, an object response, or the string itself. */
function candidate(raw: unknown): unknown {
  let answer: unknown = raw;
  if (isPlainObject(raw)) {
    answer = raw.response;
  }
  if (typeof answer === "string") {
    const text = jsonText(answer);
    return text === null ? null : JSON.parse(text);
  }
  return isPlainObject(raw) ? answer : null;
}

function validate(value: unknown, stationIds: ReadonlySet<string>): AskIntent | null {
  if (!isPlainObject(value)) {
    return null;
  }
  switch (value.kind) {
    case "search": {
      const query = cleanField(value.query, QUERY_MAX);
      return query === null ? null : { kind: "search", query };
    }
    case "play_station": {
      const stationId = value.stationId;
      return typeof stationId === "string" && stationIds.has(stationId)
        ? { kind: "play_station", stationId }
        : null;
    }
    case "parivaar_mix":
      return { kind: "parivaar_mix" };
    case "sangat":
      return { kind: "sangat" };
    case "answer": {
      const text = cleanField(value.text, TEXT_MAX);
      return text === null || hasLink(text) ? null : { kind: "answer", text };
    }
    default:
      return null;
  }
}

/** A search for the question as the person typed it, cut to 100 characters. */
export function searchFallback(question: string): AskIntent {
  let query = question.trim().slice(0, QUERY_MAX);
  const last = query.charCodeAt(query.length - 1);
  if (last >= 0xd800 && last <= 0xdbff) {
    query = query.slice(0, -1);
  }
  return { kind: "search", query: query.trimEnd() };
}

export function parseIntent(
  raw: unknown,
  question: string,
  stationIds: ReadonlySet<string>,
): { intent: AskIntent; fallback: boolean } {
  try {
    const intent = validate(candidate(raw), stationIds);
    if (intent !== null) {
      return { intent, fallback: false };
    }
  } catch {
    // Not JSON, or an object that throws when read: the same as a bad reply.
  }
  return { intent: searchFallback(question), fallback: true };
}
