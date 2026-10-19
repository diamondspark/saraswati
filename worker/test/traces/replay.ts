// Plays a trace scenario against the real FamilyHub Durable Object over real
// WebSockets (never a mock) and returns what the clients received, normalized
// for comparison with the metroserver golden.

import { env, evictAllDurableObjects, runInDurableObject } from "cloudflare:test";
import { normalize } from "./normalize.ts";
import { runScenario, type TraceTransport } from "./runner.ts";
import { SCENARIOS, type ScenarioName } from "./scenarios.ts";

// How long the whole room must stay silent before the next step is sent. The
// goldens were checked identical against metroserver at 100 ms and at 500 ms;
// the default leaves the hub (storage writes, gzip) headroom beyond that. Raise
// it with TRACE_QUIET_MS=<ms> npx vitest run if a loaded machine makes a reply
// land in the next step (vitest.config.ts forwards it as a binding).
const DEFAULT_QUIET_MS = 250;
const HUB_ORIGIN = "https://hub.example";
const DEVICE_PREFIX = "trace-device-";

type HubStub = ReturnType<typeof hubStub>;

function quietMsFromEnv(): number {
  const raw = (env as unknown as { TRACE_QUIET_MS?: string }).TRACE_QUIET_MS;
  if (raw === undefined || raw === "") return DEFAULT_QUIET_MS;
  const ms = Number(raw);
  if (!Number.isFinite(ms) || ms < 25 || ms > 900) {
    throw new Error(`TRACE_QUIET_MS must be a number from 25 to 900, got "${raw}"`);
  }
  return ms;
}

function hubStub() {
  return env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName("family"));
}

function randomHex(bytes: number): string {
  const raw = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(raw, (b) => b.toString(16).padStart(2, "0")).join("");
}

// Removes everything a previous scenario left behind: the trace devices and the
// rows of every sangat_* table. Instances are evicted first (storage is kept)
// so that no in-memory cache of a deleted room survives into the next scenario.
// abortAllDurableObjects() is not used: it makes the next call on the stub throw.
async function clearTraceState(hub: HubStub): Promise<void> {
  await evictAllDurableObjects({ webSockets: "close" });
  await runInDurableObject(hub, (_instance, state) => {
    const sql = state.storage.sql;
    const tables = sql
      .exec<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'sangat\\_%' ESCAPE '\\'",
      )
      .toArray();
    for (const { name } of tables) sql.exec(`DELETE FROM ${name}`);
    sql.exec("DELETE FROM roster WHERE deviceId LIKE ?", `${DEVICE_PREFIX}%`);
  });
}

// Every trace client is its own approved roster device with its own bearer, as
// a real phone would be after pairing.
async function seedDevices(hub: HubStub, clients: string[]): Promise<Map<string, string>> {
  const tokens = new Map<string, string>();
  await runInDurableObject(hub, (_instance, state) => {
    for (const client of clients) {
      const token = randomHex(32);
      tokens.set(client, token);
      state.storage.sql.exec(
        "INSERT OR REPLACE INTO roster (deviceId, deviceName, status, sessionToken, approvedAt, claimSecret, role) VALUES (?, ?, 'approved', ?, ?, NULL, 'adult')",
        `${DEVICE_PREFIX}${client}`,
        `Trace phone ${client}`,
        token,
        Date.now(),
      );
    }
  });
  return tokens;
}

function hubTransport(hub: HubStub, tokens: Map<string, string>): TraceTransport {
  return {
    async connect(client, onFrame, onProblem) {
      const response = await hub.fetch(`${HUB_ORIGIN}/ws`, {
        headers: { Upgrade: "websocket", Authorization: `Bearer ${tokens.get(client)}` },
      });
      const socket = response.webSocket;
      if (response.status !== 101 || !socket) {
        throw new Error(`GET /ws for client ${client} answered ${response.status}: ${await response.text()}`);
      }
      socket.accept();
      // Binary frames arrive as Blobs by default; the decoder wants bytes.
      socket.binaryType = "arraybuffer";
      socket.addEventListener("message", (event) => {
        if (typeof event.data === "string") {
          onProblem("hub sent a text frame; Sangat speaks binary protobuf");
        } else {
          onFrame(new Uint8Array(event.data));
        }
      });
      return { send: (frame) => socket.send(frame), close: () => socket.close(1000, "trace done") };
    },
  };
}

export async function replayAgainstWorker(scenario: string): Promise<object[]> {
  const steps = SCENARIOS[scenario as ScenarioName];
  if (!steps) throw new Error(`unknown trace scenario "${scenario}"`);

  const hub = hubStub();
  await clearTraceState(hub);
  try {
    const clients = [...new Set(steps.map((step) => step.client))].sort();
    const tokens = await seedDevices(hub, clients);
    return normalize(await runScenario(steps, hubTransport(hub, tokens), { quietMs: quietMsFromEnv(), lenient: true }));
  } finally {
    await clearTraceState(hub);
  }
}
