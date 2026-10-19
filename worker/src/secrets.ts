/** Helpers for the credentials the hub mints, hashes and compares. */

export function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join(
    "",
  );
}

/**
 * SHA-256 as lowercase hex. This is what the hub keeps instead of a token: the
 * tokens it mints are 256 random bits, so a fast unsalted hash is enough, and a
 * copy of the database cannot be replayed as a bearer.
 */
export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

/**
 * Compares two secrets without bailing out at the first differing byte. The
 * length check is not constant-time, which is fine: every stored value is a
 * fixed-length hex string, so a length mismatch reveals nothing.
 */
export function secretsMatch(provided: string, stored: string): boolean {
  const encoder = new TextEncoder();
  const a = encoder.encode(provided);
  const b = encoder.encode(stored);
  return a.byteLength === b.byteLength && crypto.subtle.timingSafeEqual(a, b);
}
