// Records metroserver's reference behavior as golden traces.
//
//   node --experimental-strip-types scripts/record-traces.ts ws://localhost:8080/ws [scenario ...]
//   node --experimental-strip-types scripts/record-traces.ts ws://localhost:8080/ws --check
//
// Each scenario (test/traces/scenarios.ts) is played against the server on fresh
// sockets, the messages every client receives are normalized
// (test/traces/normalize.ts) and written to test/traces/<scenario>.golden.json.
// With --check nothing is written: every scenario is recorded again and compared
// with the stored golden, which is how the goldens were shown to be deterministic.
// TRACE_QUIET_MS (default 500) is how long the room must stay silent between steps.
//
// The reference server is metroserver at commit 9da6f9da1f3ec92183a26dc8f6fbf9c8b3f0ede8,
// read as data and run only inside Docker, never vendored:
//
//   git clone https://github.com/MetrolistGroup/metroserver.git && cd metroserver
//   git checkout 9da6f9da1f3ec92183a26dc8f6fbf9c8b3f0ede8 && git submodule update --init
//   docker build -t metroserver:9da6f9d .
//   docker run -d --name metroserver-traces --read-only \
//     --tmpfs /app/data:uid=1000,gid=1000,mode=0700 --cap-drop ALL \
//     --security-opt no-new-privileges --memory 256m --pids-limit 256 \
//     -e PORT=8080 -e UA_POLICY_FILE=/config/ua_policy.json -e DATABASE_FILE=/app/data/metroserver.db \
//     -v "$PWD/scripts/record-traces.ua-policy.json:/config/ua_policy.json:ro" \
//     -p 127.0.0.1:8080:8080 metroserver:9da6f9d
//   ...record...
//   docker rm -f metroserver-traces
//
// (Run the docker command from this worker/ directory, or give the policy file's full path.)
// The recorder announces itself as "saraswati-trace-recorder/1". metroserver puts
// unknown User-Agents in its "advert" tier, which may not host rooms, so the policy
// file allows that User-Agent.

import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { normalize } from "../test/traces/normalize.ts";
import { runScenario, type TraceTransport } from "../test/traces/runner.ts";
import { SCENARIO_NAMES, SCENARIOS, type ScenarioName } from "../test/traces/scenarios.ts";

const USER_AGENT = "saraswati-trace-recorder/1";
const QUIET_MS = Number(process.env.TRACE_QUIET_MS ?? 500);
const OUT_DIR = fileURLToPath(new URL("../test/traces/", import.meta.url));

// Node's global WebSocket (undici) accepts a second argument with request headers.
interface NodeSocket {
  binaryType: string;
  send(data: Uint8Array): void;
  close(code?: number): void;
  addEventListener(type: string, listener: (event: { data?: unknown; message?: string }) => void): void;
}
type NodeSocketConstructor = new (url: string, init: { headers: Record<string, string> }) => NodeSocket;

function nodeTransport(url: string): TraceTransport {
  const Socket = WebSocket as unknown as NodeSocketConstructor;
  return {
    connect(client, onFrame, onProblem) {
      return new Promise((resolve, reject) => {
        const socket = new Socket(url, { headers: { "User-Agent": USER_AGENT } });
        socket.binaryType = "arraybuffer";
        socket.addEventListener("message", (event) => {
          if (event.data instanceof ArrayBuffer) {
            onFrame(new Uint8Array(event.data));
          } else {
            onProblem("server sent a non-binary frame");
          }
        });
        socket.addEventListener("open", () => {
          resolve({ send: (frame) => socket.send(frame), close: () => socket.close(1000) });
        });
        socket.addEventListener("error", (event) => {
          reject(new Error(`websocket error for client ${client}: ${event.message ?? "unknown"}`));
        });
      });
    },
  };
}

function goldenPath(name: string): string {
  return join(OUT_DIR, `${name}.golden.json`);
}

async function record(name: ScenarioName, transport: TraceTransport): Promise<string> {
  const messages = await runScenario(SCENARIOS[name], transport, { quietMs: QUIET_MS, lenient: false });
  return `${JSON.stringify(normalize(messages), null, 2)}\n`;
}

async function main(): Promise<number> {
  const [url, ...rest] = process.argv.slice(2) as string[];
  if (!url || !/^wss?:\/\//.test(url)) {
    console.error("usage: record-traces.ts <ws://host:port/ws> [--check] [scenario ...]");
    return 2;
  }
  const check = rest.includes("--check");
  const requested = rest.filter((arg) => arg !== "--check");
  const unknown = requested.filter((arg) => !(SCENARIO_NAMES as readonly string[]).includes(arg));
  if (unknown.length > 0) {
    console.error(`unknown scenario: ${unknown.join(", ")}`);
    return 2;
  }
  const names = (requested.length > 0 ? requested : [...SCENARIO_NAMES]) as ScenarioName[];

  const transport = nodeTransport(url);
  let differing = 0;
  for (const name of names) {
    const recorded = await record(name, transport);
    const count = (JSON.parse(recorded) as unknown[]).length;
    if (check) {
      const stored = await readFile(goldenPath(name), "utf8").catch(() => undefined);
      const same = stored === recorded;
      if (!same) differing++;
      console.log(`${same ? "same     " : "DIFFERENT"} ${name} (${count} messages)`);
    } else {
      await writeFile(goldenPath(name), recorded);
      console.log(`recorded  ${name} (${count} messages)`);
    }
  }
  return differing > 0 ? 1 : 0;
}

main().then(
  (code) => process.exit(code),
  (error: unknown) => {
    console.error(error);
    process.exit(1);
  },
);
