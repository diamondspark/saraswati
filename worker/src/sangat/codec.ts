import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { EnvelopeSchema } from "../gen/listentogether_pb";
import {
  COMPRESSION_THRESHOLD_BYTES,
  ENVELOPE_TYPE_MAX,
  MAX_DECODED_PAYLOAD_BYTES,
  MAX_READ_MESSAGE_BYTES,
} from "./limits";

/** Why a frame was refused, for callers that word their answer differently per cause. */
export type CodecErrorKind = "empty_type" | "too_long" | "bad_bytes" | "too_large";

export class CodecError extends Error {
  readonly code = "invalid_message";

  constructor(
    readonly kind: CodecErrorKind,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "CodecError";
  }
}

function checkType(type: string): void {
  if (type.length === 0) throw new CodecError("empty_type", "empty envelope type");
  if (type.length > ENVELOPE_TYPE_MAX) {
    throw new CodecError("too_long", `envelope type longer than ${ENVELOPE_TYPE_MAX} chars`);
  }
}

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

async function gzip(bytes: Uint8Array): Promise<Uint8Array> {
  const compressed = streamOf(bytes).pipeThrough(new CompressionStream("gzip"));
  return new Uint8Array(await new Response(compressed).arrayBuffer());
}

// Inflating untrusted gzip inside workerd, found by measuring (a 260 KB bomb
// that inflates to 256 MiB lifted worker RSS by ~280 MB when written as one
// chunk, against the Durable Object's 128 MB cap):
//
// 1. DecompressionStream inflates a whole write before it yields anything, so
//    the compressed bytes go in as small slices. One slice inflates to at most
//    SLICE_BYTES * 1032 (deflate's best ratio).
// 2. The reader drains output chunks only as the event loop turns, so the
//    writer waits a turn after every slice. Otherwise it races far ahead of
//    the byte count and the cap never gets a chance to stop it.
// 3. A breach is not cancelled. abort()/cancel() left ~1-2 MB per bomb
//    unreclaimed across a loop of bombs; closing the stream and reading it to
//    its end (discarding the at most one slice of output in flight) did not.
// 4. Held chunk arrays are emptied explicitly: after this function returned,
//    an array kept in its scope still leaked ~1 MiB per call.
const SLICE_BYTES = 4096;
const POST_BREACH_DISCARD_MAX = 8 * 1024 * 1024;

const nextTurn = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

// Exported for tests; `onSlice` reports each slice fed to the inflater.
export async function gunzipCapped(
  bytes: Uint8Array,
  cap: number,
  onSlice?: (sliceBytes: number) => void,
): Promise<Uint8Array> {
  const stream = new DecompressionStream("gzip");
  const writer = stream.writable.getWriter();
  const reader = stream.readable.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  let overCap = false;
  let abandoned = false;

  const draining = (async () => {
    for (;;) {
      let result: ReadableStreamReadResult<Uint8Array>;
      try {
        result = await reader.read();
      } catch (error) {
        // Closing mid-stream after a breach ends in a truncation error; expected.
        if (overCap) return;
        throw error;
      }
      const { done, value } = result;
      if (done) return;
      total += value.byteLength;
      if (overCap) {
        if (total > cap + POST_BREACH_DISCARD_MAX) {
          abandoned = true;
          return;
        }
      } else if (total > cap) {
        overCap = true;
        chunks.length = 0;
      } else {
        chunks.push(value);
      }
    }
  })();

  const feeding = (async () => {
    try {
      for (let offset = 0; offset < bytes.byteLength && !overCap; offset += SLICE_BYTES) {
        const slice = bytes.subarray(offset, offset + SLICE_BYTES);
        onSlice?.(slice.byteLength);
        await writer.write(slice);
        await nextTurn();
      }
      await writer.close();
    } catch (error) {
      // Closing mid-stream after a breach rejects as truncated; draining decides.
      if (!overCap) throw error;
    }
  })();
  // feeding only matters when it fails; the read side decides when we are done.
  const feedFailed = new Promise<never>((_, reject) => {
    feeding.catch(reject);
  });
  feedFailed.catch(() => undefined);

  const release = () =>
    Promise.allSettled([writer.abort(), reader.cancel()]).then(() => undefined);

  try {
    await Promise.race([draining, feedFailed]);
  } catch (error) {
    await release();
    if (overCap) throw new CodecError("too_large", `payload inflates past ${cap} bytes`);
    throw new CodecError("bad_bytes", "payload is not valid gzip", { cause: error });
  }
  if (abandoned) await release();
  if (overCap) throw new CodecError("too_large", `payload inflates past ${cap} bytes`);

  const out = new Uint8Array(total);
  let pos = 0;
  for (const chunk of chunks) {
    out.set(chunk, pos);
    pos += chunk.byteLength;
  }
  chunks.length = 0;
  return out;
}

export async function encodeEnvelope(
  type: string,
  payload: Uint8Array,
): Promise<Uint8Array> {
  checkType(type);
  let body = payload;
  let compressed = false;
  if (payload.byteLength > COMPRESSION_THRESHOLD_BYTES) {
    const candidate = await gzip(payload);
    if (candidate.byteLength < payload.byteLength) {
      body = candidate;
      compressed = true;
    }
  }
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload: body, compressed }));
}

export async function decodeEnvelope(
  bytes: Uint8Array,
): Promise<{ type: string; payload: Uint8Array }> {
  if (bytes.byteLength > MAX_READ_MESSAGE_BYTES) {
    throw new CodecError("too_large", `message longer than ${MAX_READ_MESSAGE_BYTES} bytes`);
  }
  let envelope;
  try {
    envelope = fromBinary(EnvelopeSchema, bytes);
  } catch (error) {
    throw new CodecError("bad_bytes", "bytes are not an Envelope", { cause: error });
  }
  checkType(envelope.type);
  if (envelope.compressed) {
    return {
      type: envelope.type,
      payload: await gunzipCapped(envelope.payload, MAX_DECODED_PAYLOAD_BYTES),
    };
  }
  if (envelope.payload.byteLength > MAX_DECODED_PAYLOAD_BYTES) {
    throw new CodecError("too_large", `payload larger than ${MAX_DECODED_PAYLOAD_BYTES} bytes`);
  }
  return { type: envelope.type, payload: envelope.payload };
}
