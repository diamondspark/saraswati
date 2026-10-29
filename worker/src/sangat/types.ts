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
  /**
   * A room was just created by a member on `hostDeviceId`. Like detachSocket it happens once
   * the handler's changes are committed, never inside the transaction.
   */
  roomStarted(hostDeviceId: string): void;
  /** The room hosted from `hostDeviceId` stopped being live for good: its host left on purpose, or its grace ran out with no one to take over. After the commit, too. */
  roomEnded(hostDeviceId: string): void;
}

/**
 * What the hub wants to hear about, called after the change is committed (so never inside a
 * storage transaction) and expected not to throw or to wait. Optional: tests build a Sangat
 * without it.
 */
export interface SangatHooks {
  roomStarted?(hostDeviceId: string): void;
  roomEnded?(hostDeviceId: string): void;
}

/**
 * What Sangat.prepareHash computes for a handler before its storage transaction
 * starts, since hashing a session token awaits and a transaction cannot. Empty
 * for every handler that does not touch one.
 */
export interface PreHashed {
  /** A session token minted for this message, with its hash: set only for create_room and approve_join. */
  newToken?: { token: string; hash: string };
  /** The hash of the session token the payload presented, for reconnect; null if there was none to hash. */
  presentedTokenHash?: string | null;
}

export type Handler = (rt: Runtime, conn: Conn, payload: Uint8Array, pre: PreHashed) => Out[];
