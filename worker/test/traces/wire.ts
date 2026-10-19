// Client-side wire format for the trace harness: Envelope framing, optional
// gzip, and proto3 JSON <-> protobuf for every listen-together payload.
//
// This file is shared by the Node recorder (scripts/record-traces.ts, run with
// `node --experimental-strip-types`) and by the Worker-side replay, so it uses
// explicit `.ts` import extensions and only web-standard APIs. It is
// deliberately independent of src/sangat/codec.ts: the traces check the server
// against metroserver, so the client that drives them must not share code with
// the server under test.

import {
  create,
  fromBinary,
  fromJson,
  toBinary,
  toJson,
  type DescMessage,
  type JsonObject,
} from "@bufbuild/protobuf";
import {
  ApproveJoinPayloadSchema,
  ApproveSuggestionPayloadSchema,
  BufferCompletePayloadSchema,
  BufferReadyPayloadSchema,
  BufferWaitPayloadSchema,
  ClientCapabilitiesSchema,
  CreateRoomPayloadSchema,
  EnvelopeSchema,
  ErrorPayloadSchema,
  HostChangedPayloadSchema,
  JoinApprovedPayloadSchema,
  JoinRejectedPayloadSchema,
  JoinRequestPayloadSchema,
  JoinRoomPayloadSchema,
  KickedPayloadSchema,
  KickUserPayloadSchema,
  LeaveRoomPayloadSchema,
  PingPayloadSchema,
  PlaybackActionPayloadSchema,
  PongPayloadSchema,
  ReconnectedPayloadSchema,
  ReconnectPayloadSchema,
  RejectJoinPayloadSchema,
  RejectSuggestionPayloadSchema,
  RoomCreatedPayloadSchema,
  ServerCapabilitiesSchema,
  SuggestionApprovedPayloadSchema,
  SuggestionReceivedPayloadSchema,
  SuggestionRejectedPayloadSchema,
  SuggestTrackPayloadSchema,
  SyncStatePayloadSchema,
  TransferHostPayloadSchema,
  UserDisconnectedPayloadSchema,
  UserJoinedPayloadSchema,
  UserLeftPayloadSchema,
  UserReconnectedPayloadSchema,
} from "../../src/gen/listentogether_pb.ts";

// Every message type that crosses the wire, client->server and server->client.
// `null` marks a type whose payload is always empty (request_sync has no
// message in the .proto).
const PAYLOAD_SCHEMAS: Record<string, DescMessage | null> = {
  // client -> server
  create_room: CreateRoomPayloadSchema,
  join_room: JoinRoomPayloadSchema,
  leave_room: LeaveRoomPayloadSchema,
  approve_join: ApproveJoinPayloadSchema,
  reject_join: RejectJoinPayloadSchema,
  playback_action: PlaybackActionPayloadSchema,
  buffer_ready: BufferReadyPayloadSchema,
  kick_user: KickUserPayloadSchema,
  transfer_host: TransferHostPayloadSchema,
  ping: PingPayloadSchema,
  request_sync: null,
  reconnect: ReconnectPayloadSchema,
  suggest_track: SuggestTrackPayloadSchema,
  approve_suggestion: ApproveSuggestionPayloadSchema,
  reject_suggestion: RejectSuggestionPayloadSchema,
  client_capabilities: ClientCapabilitiesSchema,
  // server -> client
  room_created: RoomCreatedPayloadSchema,
  join_request: JoinRequestPayloadSchema,
  join_approved: JoinApprovedPayloadSchema,
  join_rejected: JoinRejectedPayloadSchema,
  user_joined: UserJoinedPayloadSchema,
  user_left: UserLeftPayloadSchema,
  sync_playback: PlaybackActionPayloadSchema,
  buffer_wait: BufferWaitPayloadSchema,
  buffer_complete: BufferCompletePayloadSchema,
  error: ErrorPayloadSchema,
  pong: PongPayloadSchema,
  host_changed: HostChangedPayloadSchema,
  kicked: KickedPayloadSchema,
  sync_state: SyncStatePayloadSchema,
  reconnected: ReconnectedPayloadSchema,
  user_reconnected: UserReconnectedPayloadSchema,
  user_disconnected: UserDisconnectedPayloadSchema,
  suggestion_received: SuggestionReceivedPayloadSchema,
  suggestion_approved: SuggestionApprovedPayloadSchema,
  suggestion_rejected: SuggestionRejectedPayloadSchema,
  server_capabilities: ServerCapabilitiesSchema,
};

// Same threshold as metroserver and the Worker codec: gzip only above 100 bytes
// and only when it shrinks the payload.
const COMPRESSION_THRESHOLD_BYTES = 100;

// Scenario escape hatches for malformed traffic, which proto3 JSON cannot
// express:
//   { type: "<any>",   payload: { $rawHex: "ff" } }  raw payload bytes inside a valid Envelope
//   { type: "$frame",  payload: { $rawHex: "ff" } }  raw WebSocket frame, no Envelope at all
export const RAW_FRAME_TYPE = "$frame";

export function toHex(bytes: Uint8Array): string {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

export function fromHex(hex: string): Uint8Array {
  if (hex.length % 2 !== 0 || /[^0-9a-f]/i.test(hex)) {
    throw new Error(`bad hex string: ${hex}`);
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

function streamOf(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    },
  });
}

async function pipe(
  bytes: Uint8Array,
  transform: CompressionStream | DecompressionStream,
): Promise<Uint8Array> {
  const out = streamOf(bytes).pipeThrough(transform as TransformStream<Uint8Array, Uint8Array>);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

function rawHexOf(payload: object): string | undefined {
  const raw = (payload as { $rawHex?: unknown }).$rawHex;
  return typeof raw === "string" ? raw : undefined;
}

export function encodePayload(type: string, payload: object): Uint8Array {
  const raw = rawHexOf(payload);
  if (raw !== undefined) return fromHex(raw);
  const schema = PAYLOAD_SCHEMAS[type];
  if (schema === undefined) {
    if (Object.keys(payload).length > 0) {
      throw new Error(`no payload schema for message type "${type}"`);
    }
    return new Uint8Array();
  }
  if (schema === null) return new Uint8Array();
  return toBinary(schema, fromJson(schema, payload as JsonObject));
}

/** Builds the exact bytes of one WebSocket binary frame for a scenario `send`. */
export async function encodeFrame(type: string, payload: object): Promise<Uint8Array> {
  if (type === RAW_FRAME_TYPE) {
    const raw = rawHexOf(payload);
    if (raw === undefined) throw new Error(`${RAW_FRAME_TYPE} needs { $rawHex }`);
    return fromHex(raw);
  }
  const bytes = encodePayload(type, payload);
  let body = bytes;
  let compressed = false;
  if (bytes.byteLength > COMPRESSION_THRESHOLD_BYTES) {
    const candidate = await pipe(bytes, new CompressionStream("gzip"));
    if (candidate.byteLength < bytes.byteLength) {
      body = candidate;
      compressed = true;
    }
  }
  return toBinary(EnvelopeSchema, create(EnvelopeSchema, { type, payload: body, compressed }));
}

/** Decodes one server frame to its type and canonical proto3 JSON (snake_case, defaults included). */
export async function decodeFrame(
  frame: Uint8Array,
): Promise<{ type: string; payload: JsonObject }> {
  const envelope = fromBinary(EnvelopeSchema, frame);
  const bytes = envelope.compressed
    ? await pipe(envelope.payload, new DecompressionStream("gzip"))
    : envelope.payload;
  const schema = PAYLOAD_SCHEMAS[envelope.type];
  if (schema === undefined) {
    throw new Error(`server sent unknown message type "${envelope.type}"`);
  }
  if (schema === null) return { type: envelope.type, payload: {} };
  const payload = toJson(schema, fromBinary(schema, bytes), {
    alwaysEmitImplicit: true,
    useProtoFieldName: true,
  });
  return { type: envelope.type, payload: payload as JsonObject };
}
