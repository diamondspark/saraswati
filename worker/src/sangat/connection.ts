// Messages about the connection itself rather than a room: ping and capability
// negotiation.

import { ClientCapabilitiesSchema, PingPayloadSchema, PongPayloadSchema, ServerCapabilitiesSchema } from "../gen/listentogether_pb";
import { decode, fail, send } from "./roomOps";
import type { Handler } from "./types";

export const handlePing: Handler = (rt, conn, payload) => {
  const me = conn.att.userId;
  // An empty payload is a ping with every field zero.
  const ping = payload.byteLength === 0 ? { clientTime: 0n, sequence: 0n } : decode(PingPayloadSchema, payload);
  if (!ping) return [fail(me, "invalid_payload", "Invalid ping payload")];
  return [
    send(me, "pong", PongPayloadSchema, {
      clientTime: ping.clientTime,
      serverReceiveTime: BigInt(conn.receivedAt),
      serverSendTime: BigInt(rt.now()),
      sequence: ping.sequence,
    }),
  ];
};

export const handleClientCapabilities: Handler = (_rt, conn, payload) => {
  const me = conn.att.userId;
  const caps = decode(ClientCapabilitiesSchema, payload);
  if (!caps) return [fail(me, "invalid_payload", "Invalid client capabilities payload")];
  if (!caps.supportsProtobuf) return [fail(me, "unsupported_client", "Protobuf support is required")];
  if (conn.att.started) {
    return [fail(me, "capabilities_too_late", "Client capabilities must be sent before joining a room")];
  }
  if (conn.att.capsSet) {
    return [fail(me, "capabilities_already_set", "Client capabilities have already been configured")];
  }
  conn.att.capsSet = true;
  conn.att.plain = !caps.supportsCompression;
  conn.dirty = true;
  return [
    send(me, "server_capabilities", ServerCapabilitiesSchema, {
      supportsProtobuf: true,
      supportsCompression: true,
      serverVersion: "1",
    }),
  ];
};
