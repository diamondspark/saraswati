import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { EnvelopeSchema } from "../gen/listentogether_pb";
import {
  COMPRESSION_THRESHOLD_BYTES,
  ENVELOPE_TYPE_MAX,
  MAX_DECODED_PAYLOAD_BYTES,
} from "./limits";

export class CodecError extends Error {
  readonly code = "invalid_message";

  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "CodecError";
  }
}

function checkType(type: string): void {
  if (type.length === 0) throw new CodecError("empty envelope type");
  if (type.length > ENVELOPE_TYPE_MAX) {
    throw new CodecError(`envelope type longer than ${ENVELOPE_TYPE_MAX} chars`);
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

// Counts inflated bytes as chunks arrive and stops reading the moment the cap
// is crossed, so a gzip bomb never gets buffered whole.
async function gunzipCapped(bytes: Uint8Array, cap: number): Promise<Uint8Array> {
  const reader = streamOf(bytes)
    .pipeThrough(new DecompressionStream("gzip"))
    .getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > cap) {
        await reader.cancel().catch(() => undefined);
        throw new CodecError(`payload inflates past ${cap} bytes`);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof CodecError) throw error;
    throw new CodecError("payload is not valid gzip", { cause: error });
  }
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
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
  let envelope;
  try {
    envelope = fromBinary(EnvelopeSchema, bytes);
  } catch (error) {
    throw new CodecError("bytes are not an Envelope", { cause: error });
  }
  checkType(envelope.type);
  if (envelope.compressed) {
    return {
      type: envelope.type,
      payload: await gunzipCapped(envelope.payload, MAX_DECODED_PAYLOAD_BYTES),
    };
  }
  if (envelope.payload.byteLength > MAX_DECODED_PAYLOAD_BYTES) {
    throw new CodecError(`payload larger than ${MAX_DECODED_PAYLOAD_BYTES} bytes`);
  }
  return { type: envelope.type, payload: envelope.payload };
}
