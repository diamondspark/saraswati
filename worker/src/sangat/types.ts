import type { Store } from "./store";

/** What the hibernation API keeps for a socket (serializeAttachment, under 2 KiB). */
export interface Attachment {
  userId: string;
  deviceId: string;
  /** client_capabilities was accepted on this socket. */
  capsSet?: boolean;
  /** The socket has asked to join, create or rejoin a room; capabilities are then too late. */
  started?: boolean;
  /** The client declined compression: send it plain envelopes. */
  plain?: boolean;
  /** When a frame last arrived on this socket (ms). Absent until the socket first speaks or is accepted. */
  lastSeen?: number;
  /** Start of the current rate-limit window (ms) and the messages counted in it. */
  rateStart?: number;
  rateCount?: number;
  /** A message was refused in the current window; and how many windows in a row have had one. */
  rateStruck?: boolean;
  rateStrikes?: number;
  /** Tombstone: the server has closed this socket and it is waiting out the close handshake. It no longer counts as live. */
  closed?: boolean;
}

/** The socket a message arrived on. Handlers edit `att` and set `dirty`; Sangat saves it. */
export interface Conn {
  ws: WebSocket;
  att: Attachment;
  dirty: boolean;
  /** When the frame reached the Durable Object, before it waited its turn. */
  receivedAt: number;
}

/** One message to send, already serialized, to the socket that holds `to`. */
export interface Out {
  to: string;
  type: string;
  payload: Uint8Array;
}

/** What handlers may ask of the Sangat instance that runs them. */
export interface Runtime {
  readonly store: Store;
  now(): number;
  /**
   * Gives the live socket that holds `userId` (if any) a new, unused id and
   * closes it, so a newer socket can take the id over. The old socket's own
   * close event then finds no member. It happens once the handler's changes are
   * committed, not at the call: a handler that fails afterwards leaves the old
   * socket alone.
   */
  detachSocket(userId: string, code: number, reason: string): void;
  /** False when `userId` was answered a request_sync less than a second ago. */
  allowSyncResponse(userId: string, now: number): boolean;
}

export type Handler = (rt: Runtime, conn: Conn, payload: Uint8Array) => Out[];
