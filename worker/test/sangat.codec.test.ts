import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { EnvelopeSchema } from "../src/gen/listentogether_pb";
import {
  CodecError,
  decodeEnvelope,
  encodeEnvelope,
  gunzipCapped,
} from "../src/sangat/codec";
import {
  COMPRESSION_THRESHOLD_BYTES,
  ENVELOPE_TYPE_MAX,
  MAX_DECODED_PAYLOAD_BYTES,
  MAX_READ_MESSAGE_BYTES,
} from "../src/sangat/limits";

async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
  const stream = source.pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

// Gzips `totalBytes` of zeros without ever holding them: a pull-based source
// feeds one reused 64 KiB chunk at a time into CompressionStream.
async function gzipZeros(totalBytes: number): Promise<Uint8Array> {
  const chunk = new Uint8Array(64 * 1024);
  let remaining = totalBytes;
  const source = new ReadableStream<Uint8Array>(
    {
      pull(controller) {
        if (remaining <= 0) {
          controller.close();
          return;
        }
        const n = Math.min(chunk.byteLength, remaining);
        remaining -= n;
        controller.enqueue(chunk.subarray(0, n));
      },
    },
    { highWaterMark: 0 },
  );
  const stream = source.pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function envelopeBytes(
  type: string,
  payload: Uint8Array,
  compressed: boolean,
): Uint8Array {
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload, compressed }));
}

// Deterministic, effectively incompressible bytes (no flaky randomness).
function noise(length: number): Uint8Array {
  const out = new Uint8Array(length);
  let x = 0x9e3779b9;
  for (let i = 0; i < length; i++) {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    out[i] = x & 0xff;
  }
  return out;
}

async function expectInvalid(promise: Promise<unknown>, kind: CodecError["kind"]): Promise<void> {
  const error = await promise.then(
    () => null,
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(CodecError);
  expect((error as CodecError).code).toBe("invalid_message");
  expect((error as CodecError).kind).toBe(kind);
}

describe("sangat envelope codec", () => {
  it("exposes the Global Constraints limits it relies on", () => {
    expect(COMPRESSION_THRESHOLD_BYTES).toBe(100);
    expect(ENVELOPE_TYPE_MAX).toBe(64);
    expect(MAX_DECODED_PAYLOAD_BYTES).toBe(1048576);
  });

  it("round-trips an uncompressed envelope", async () => {
    const payload = new Uint8Array([1, 2, 3, 4, 5]);
    const wire = await encodeEnvelope("ping", payload);

    const parsed = fromBinary(EnvelopeSchema, wire);
    expect(parsed.type).toBe("ping");
    expect(parsed.compressed).toBe(false);
    expect(Array.from(parsed.payload)).toEqual([1, 2, 3, 4, 5]);

    const decoded = await decodeEnvelope(wire);
    expect(decoded.type).toBe("ping");
    expect(Array.from(decoded.payload)).toEqual([1, 2, 3, 4, 5]);
  });

  it("gzips a payload over 100 bytes only when that makes it smaller and sets compressed", async () => {
    const squashable = new Uint8Array(4000).fill(7);
    const wire = await encodeEnvelope("sync_state", squashable);
    const parsed = fromBinary(EnvelopeSchema, wire);
    expect(parsed.compressed).toBe(true);
    expect(parsed.payload.length).toBeLessThan(squashable.length);
    const decoded = await decodeEnvelope(wire);
    expect(decoded.type).toBe("sync_state");
    expect(Array.from(decoded.payload)).toEqual(Array.from(squashable));

    const incompressible = noise(300);
    const plainWire = await encodeEnvelope("sync_state", incompressible);
    const plainParsed = fromBinary(EnvelopeSchema, plainWire);
    expect(plainParsed.compressed).toBe(false);
    expect(Array.from(plainParsed.payload)).toEqual(Array.from(incompressible));
    const plainDecoded = await decodeEnvelope(plainWire);
    expect(Array.from(plainDecoded.payload)).toEqual(Array.from(incompressible));
  });

  it("leaves a 100-byte payload uncompressed", async () => {
    const wire = await encodeEnvelope("ping", new Uint8Array(100).fill(7));
    const parsed = fromBinary(EnvelopeSchema, wire);
    expect(parsed.compressed).toBe(false);
    expect(parsed.payload.length).toBe(100);

    const over = await encodeEnvelope("ping", new Uint8Array(101).fill(7));
    expect(fromBinary(EnvelopeSchema, over).compressed).toBe(true);
  });

  it("decodes a compressed envelope", async () => {
    const original = noise(64).map((b) => b % 4);
    const wire = envelopeBytes("buffer_wait", await gzip(original), true);
    const decoded = await decodeEnvelope(wire);
    expect(decoded.type).toBe("buffer_wait");
    expect(Array.from(decoded.payload)).toEqual(Array.from(original));
  });

  it("decodes a compressed payload that spans many input slices", async () => {
    const original = noise(100_000);
    const compressed = await gzip(original);
    expect(compressed.length).toBeGreaterThan(50_000);
    const decoded = await decodeEnvelope(envelopeBytes("big_queue", compressed, true));
    expect(decoded.payload.length).toBe(original.length);
    expect(decoded.payload.every((b, i) => b === original[i])).toBe(true);
  });

  it("decodes a payload that inflates to exactly 1048576 bytes", async () => {
    const original = new Uint8Array(MAX_DECODED_PAYLOAD_BYTES).fill(9);
    const wire = envelopeBytes("big", await gzip(original), true);
    const decoded = await decodeEnvelope(wire);
    expect(decoded.payload.length).toBe(MAX_DECODED_PAYLOAD_BYTES);
  });

  it("rejects a payload that inflates past 1048576 bytes", async () => {
    const bomb = await gzip(new Uint8Array(MAX_DECODED_PAYLOAD_BYTES + 1).fill(0));
    expect(bomb.length).toBeLessThan(100_000);
    await expectInvalid(decodeEnvelope(envelopeBytes("bomb", bomb, true)), "too_large");

    const hugeBomb = await gzip(new Uint8Array(16 * 1024 * 1024));
    await expectInvalid(decodeEnvelope(envelopeBytes("bomb", hugeBomb, true)), "too_large");
  });

  it("rejects a compressed flag on bytes that are not gzip", async () => {
    await expectInvalid(
      decodeEnvelope(envelopeBytes("ping", new Uint8Array([1, 2, 3, 4]), true)),
      "bad_bytes",
    );
  });

  it("rejects an empty type", async () => {
    await expectInvalid(decodeEnvelope(envelopeBytes("", new Uint8Array([1]), false)), "empty_type");
    await expectInvalid(encodeEnvelope("", new Uint8Array([1])), "empty_type");
  });

  it("rejects a type longer than 64 chars", async () => {
    const atLimit = "a".repeat(ENVELOPE_TYPE_MAX);
    const decoded = await decodeEnvelope(envelopeBytes(atLimit, new Uint8Array(), false));
    expect(decoded.type).toBe(atLimit);

    const tooLong = "a".repeat(ENVELOPE_TYPE_MAX + 1);
    await expectInvalid(decodeEnvelope(envelopeBytes(tooLong, new Uint8Array(), false)), "too_long");
    await expectInvalid(encodeEnvelope(tooLong, new Uint8Array()), "too_long");
  });

  it("rejects bytes that are not an Envelope", async () => {
    await expectInvalid(decodeEnvelope(new Uint8Array([0xff, 0xff, 0xff, 0xff])), "bad_bytes");
    await expectInvalid(decodeEnvelope(new Uint8Array([0x0a, 0x7f, 0x61])), "bad_bytes");
  });

  it("rejects a message longer than the 524288-byte read limit before decoding it", async () => {
    await expectInvalid(decodeEnvelope(new Uint8Array(MAX_READ_MESSAGE_BYTES + 1)), "too_large");

    // 10 bytes of framing for type "ping" plus a 3-byte payload length.
    const payload = new Uint8Array(MAX_READ_MESSAGE_BYTES - 10).fill(3);
    const atLimit = envelopeBytes("ping", payload, false);
    expect(atLimit.length).toBe(MAX_READ_MESSAGE_BYTES);
    const decoded = await decodeEnvelope(atLimit);
    expect(decoded.payload.length).toBe(payload.length);

    const over = envelopeBytes("ping", new Uint8Array(payload.length + 1).fill(3), false);
    expect(over.length).toBe(MAX_READ_MESSAGE_BYTES + 1);
    await expectInvalid(decodeEnvelope(over), "too_large");
  });

  it("rejects a 256 MiB gzip bomb after reading only a sliver of its input", async () => {
    const bomb = await gzipZeros(256 * 1024 * 1024);
    expect(bomb.length).toBeLessThan(MAX_READ_MESSAGE_BYTES);

    let fed = 0;
    const error = await gunzipCapped(bomb, MAX_DECODED_PAYLOAD_BYTES, (n) => {
      fed += n;
    }).then(
      () => null,
      (e: unknown) => e,
    );
    expect(error).toBeInstanceOf(CodecError);
    expect((error as CodecError).code).toBe("invalid_message");
    expect((error as CodecError).kind).toBe("too_large");
    // Whole bomb is ~256 KB; a bounded reader stops within a few slices.
    expect(fed).toBeGreaterThan(0);
    expect(fed).toBeLessThan(bomb.length / 8);

    await expectInvalid(decodeEnvelope(envelopeBytes("bomb", bomb, true)), "too_large");
  }, 60_000);
});
