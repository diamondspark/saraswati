// Small helpers for the hub's JSON routes.

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export async function readJsonBody(
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
export async function readStrictJsonObject(
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

/**
 * The request body as bytes, or null once it is more than `maxBytes`. It counts what is
 * actually read, never the Content-Length header (a streamed body has none and a header can
 * lie), and stops at the first chunk that crosses the cap, so at most one chunk past it is
 * ever held. A body that cannot be read is empty.
 */
export async function readBoundedBody(
  request: Request,
  maxBytes: number,
): Promise<Uint8Array | null> {
  if (request.body === null) {
    return new Uint8Array(0);
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      total += value.byteLength;
      if (total > maxBytes) {
        void reader.cancel().catch(() => undefined);
        return null;
      }
      chunks.push(value);
    }
  } catch {
    return new Uint8Array(0);
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return bytes;
}

/** The JSON object in `bytes`, or null for anything else (bad JSON, an array, a scalar, null). */
export function parseJsonObject(bytes: Uint8Array): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

export function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}
