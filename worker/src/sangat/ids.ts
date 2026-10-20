import { ROOM_CODE_ALPHABET, ROOM_CODE_LENGTH } from "./limits";

// Uniform integer in [0, max) from the platform CSPRNG, without modulo bias.
export function randomInt(max: number): number {
  const limit = Math.floor(0x100000000 / max) * max;
  const buffer = new Uint32Array(1);
  for (;;) {
    crypto.getRandomValues(buffer);
    if (buffer[0] < limit) return buffer[0] % max;
  }
}

export function randomHex(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function newRoomCode(): string {
  let code = "";
  for (let i = 0; i < ROOM_CODE_LENGTH; i++) {
    code += ROOM_CODE_ALPHABET[randomInt(ROOM_CODE_ALPHABET.length)];
  }
  return code;
}

// 32 random bytes as hex, like metroserver.
export function newSessionToken(): string {
  return randomHex(32);
}

// metroserver's format, with milliseconds instead of nanoseconds.
export function newUserId(now: number): string {
  return `user_${now}_${randomInt(10000)}`;
}

export function newSuggestionId(now: number): string {
  return `sug_${now}_${randomInt(10000)}`;
}
