// Drives one scenario against any server through a small transport interface.
// The Node recorder plugs in real WebSockets to metroserver; the Worker replay
// plugs in FamilyHub WebSockets. Both go through this one interpreter, so a
// scenario means exactly the same thing on both sides.

import type { TraceMessage } from "./normalize.ts";
import type { TraceStep } from "./scenarios.ts";
import { decodeFrame, encodeFrame } from "./wire.ts";

export interface TraceConnection {
  send(frame: Uint8Array): void;
  close(): void;
}

export interface TraceTransport {
  /**
   * Opens a socket for the named trace client. Every binary frame it receives goes
   * to `onFrame`; anything that is not a binary frame is reported to `onProblem`
   * rather than thrown, so one bad frame fails the scenario, not the whole run.
   */
  connect(
    client: string,
    onFrame: (frame: Uint8Array) => void,
    onProblem: (problem: string) => void,
  ): Promise<TraceConnection>;
}

type ClientState = {
  connection: TraceConnection | null;
  username?: string;
  userId?: string;
  token?: string;
  // Frames are decoded in arrival order even when gunzip is asynchronous.
  decoding: Promise<void>;
};

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

function label(step: TraceStep, index: number): string {
  const what = step.send ? `send ${step.send.type}` : step.close ? "close" : step.reconnect ? "reconnect" : "wait";
  return `step ${index + 1} (${step.client} ${what})`;
}

export type RunOptions = {
  /** How long the whole room must stay silent before the next step is sent. */
  quietMs: number;
  /**
   * When a step needs an id the server never produced (say, "<room>" because
   * room_created never came), send a visible marker instead of throwing, so the
   * run finishes and the comparison with the golden shows what went wrong. The
   * recorder runs strict: a missing id there is a bug in the scenario.
   */
  lenient: boolean;
};

/**
 * Runs the steps in order. After each step it waits until no client has
 * received anything for `quietMs`, then files that step's messages ordered by
 * client name (and, within a client, by arrival). Ordering between different
 * clients is not observable to a real client, so the batching only exists to
 * make the output deterministic.
 */
export async function runScenario(
  steps: TraceStep[],
  transport: TraceTransport,
  { quietMs, lenient }: RunOptions,
): Promise<TraceMessage[]> {
  const names = [...new Set(steps.map((s) => s.client))].sort();
  const clients = new Map<string, ClientState>();
  let roomCode: string | undefined;
  let suggestionId: string | undefined;
  let activity = 0;
  let inFlight = 0;
  let decodeFailure: unknown;
  let batch: TraceMessage[] = [];
  const output: TraceMessage[] = [];

  const learn = (client: string, message: TraceMessage) => {
    const state = clients.get(client)!;
    const payload = message.payload as Record<string, unknown>;
    const str = (key: string) => (typeof payload[key] === "string" ? (payload[key] as string) : undefined);
    switch (message.type) {
      case "room_created":
        roomCode ??= str("room_code");
        state.userId = str("user_id");
        state.token = str("session_token");
        break;
      case "join_approved":
        state.userId = str("user_id");
        state.token = str("session_token");
        break;
      case "reconnected":
        state.userId = str("user_id");
        break;
      case "join_request": {
        for (const other of clients.values()) {
          if (other.username !== undefined && other.username === str("username")) {
            other.userId = str("user_id");
          }
        }
        break;
      }
      case "suggestion_received":
        suggestionId = str("suggestion_id");
        break;
    }
  };

  const open = async (client: string) => {
    const state = clients.get(client)!;
    if (state.connection) throw new Error(`client ${client} is already connected`);
    state.connection = await transport.connect(
      client,
      (frame) => {
        activity++;
        inFlight++;
        state.decoding = state.decoding.then(async () => {
          try {
            const { type, payload } = await decodeFrame(frame);
            const message = { client, type, payload };
            learn(client, message);
            batch.push(message);
          } catch (error) {
            decodeFailure ??= error;
          } finally {
            inFlight--;
            activity++;
          }
        });
      },
      (problem) => {
        decodeFailure ??= new Error(`client ${client}: ${problem}`);
        activity++;
      },
    );
  };

  // Returns once nothing has arrived for `quietMs` and no frame is mid-decode.
  // Polls in short slices instead of reading the clock: Date.now() does not
  // advance on its own inside workerd.
  const settle = async () => {
    const slice = Math.min(25, quietMs);
    let quiet = 0;
    let seen = activity;
    while (quiet < quietMs || inFlight > 0) {
      await sleep(slice);
      if (activity !== seen) {
        seen = activity;
        quiet = 0;
      } else {
        quiet += slice;
      }
    }
  };

  const missing = (what: string): string => {
    if (!lenient) throw new Error(`scenario uses ${what} before the server produced it`);
    return `<missing ${what}>`;
  };

  const fill = (value: unknown): unknown => {
    if (typeof value === "string") {
      const room = /^<room(\|lower)?>$/.exec(value);
      if (room) {
        return roomCode === undefined ? missing(value) : room[1] ? roomCode.toLowerCase() : roomCode;
      }
      const ref = /^<(user|token):([A-Z])>$/.exec(value);
      if (ref) {
        const owner = clients.get(ref[2]);
        return (ref[1] === "user" ? owner?.userId : owner?.token) ?? missing(value);
      }
      if (value === "<suggestion>") return suggestionId ?? missing(value);
      return value;
    }
    if (Array.isArray(value)) return value.map(fill);
    if (value !== null && typeof value === "object") {
      return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, fill(v)]));
    }
    return value;
  };

  for (const name of names) {
    clients.set(name, { connection: null, decoding: Promise.resolve() });
  }

  try {
    for (const name of names) await open(name);
    await settle();
    // Neither metroserver nor the hub says anything until it is spoken to.
    if (decodeFailure !== undefined) throw decodeFailure;
    if (batch.length > 0) {
      const heard = batch.map((m) => `${m.client}:${m.type}`).join(", ");
      throw new Error(`unsolicited frames right after connecting: ${heard}`);
    }

    for (const [index, step] of steps.entries()) {
      const state = clients.get(step.client)!;
      try {
        if (step.send) {
          const payload = fill(step.send.payload) as { username?: unknown };
          if (
            (step.send.type === "create_room" || step.send.type === "join_room") &&
            typeof payload.username === "string"
          ) {
            state.username = payload.username;
          }
          if (!state.connection) throw new Error(`${step.client} has no open connection`);
          state.connection.send(await encodeFrame(step.send.type, payload));
        } else if (step.close) {
          if (!state.connection) throw new Error(`${step.client} has no open connection`);
          state.connection.close();
          state.connection = null;
        } else if (step.reconnect) {
          await open(step.client);
        }
        if (step.waitMs) await sleep(step.waitMs);
        await settle();
        if (decodeFailure !== undefined) throw decodeFailure;
      } catch (error) {
        throw new Error(`${label(step, index)}: ${error instanceof Error ? error.message : String(error)}`, {
          cause: error,
        });
      }
      const byClient = (a: TraceMessage, b: TraceMessage) =>
        a.client < b.client ? -1 : a.client > b.client ? 1 : 0;
      // Array.prototype.sort is stable, so arrival order survives within a client.
      output.push(...[...batch].sort(byClient));
      batch = [];
    }
  } finally {
    for (const state of clients.values()) {
      try {
        state.connection?.close();
      } catch {
        // already closed
      }
    }
  }
  return output;
}
