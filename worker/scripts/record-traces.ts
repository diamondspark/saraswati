// Records metroserver's reference behavior as golden traces.
//
//   node --experimental-strip-types scripts/record-traces.ts ws://localhost:8080/ws [scenario ...]
//   node --experimental-strip-types scripts/record-traces.ts ws://localhost:8080/ws --check
//
// The host must be localhost, 127.0.0.1 or [::1]; pass --allow-remote to record
// from anything else. Every requested scenario is recorded into memory first and
// the goldens are written only if all of them succeeded, so a failed run never
// leaves a mix of old and new files. A scenario that yields no server messages
// at all counts as a failure (a wrong URL or a dead server must not become a golden).
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
// Under 25 ms settle() stops waiting for quiet; past 900 ms the request_sync
// suppression scenario's back-to-back steps drift outside metroserver's 1 s window.
const QUIET_MS = (() => {
  const raw = process.env.TRACE_QUIET_MS;
  if (raw === undefined || raw === "") return 500;
  const ms = Number(raw);
  if (!Number.isFinite(ms) || ms < 25 || ms > 900) {
    throw new Error(`TRACE_QUIET_MS must be a number from 25 to 900, got "${raw}"`);
  }
  return ms;
})();
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

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);
const FLAGS = new Set(["--check", "--allow-remote"]);

async function main(): Promise<number> {
  const usage = "usage: record-traces.ts <ws://localhost:port/ws> [--check] [--allow-remote] [scenario ...]";
  const [url, ...rest] = process.argv.slice(2) as string[];
  let target: URL;
  try {
    target = new URL(url ?? "");
  } catch {
    console.error(usage);
    return 2;
  }
  if (target.protocol !== "ws:" && target.protocol !== "wss:") {
    console.error(usage);
    return 2;
  }
  const flags = rest.filter((arg) => arg.startsWith("--"));
  const badFlags = flags.filter((flag) => !FLAGS.has(flag));
  if (badFlags.length > 0) {
    console.error(`unknown flag: ${badFlags.join(", ")}\n${usage}`);
    return 2;
  }
  if (!LOCAL_HOSTS.has(target.hostname) && !flags.includes("--allow-remote")) {
    console.error(
      `refusing to record from ${target.hostname}: goldens must come from a local, disposable metroserver. ` +
        "Pass --allow-remote if that host really is the reference server.",
    );
    return 2;
  }
  const check = flags.includes("--check");
  const requested = rest.filter((arg) => !arg.startsWith("--"));
  const unknown = requested.filter((arg) => !(SCENARIO_NAMES as readonly string[]).includes(arg));
  if (unknown.length > 0) {
    console.error(`unknown scenario: ${unknown.join(", ")}`);
    return 2;
  }
  const names = (requested.length > 0 ? requested : [...SCENARIO_NAMES]) as ScenarioName[];

  const transport = nodeTransport(target.href);
  const recorded = new Map<ScenarioName, { text: string; count: number }>();
  for (const name of names) {
    const text = await record(name, transport);
    const count = (JSON.parse(text) as unknown[]).length;
    if (count === 0) {
      console.error(`${name}: the server sent no messages at all; is ${target.href} really metroserver?`);
      return 1;
    }
    recorded.set(name, { text, count });
  }

  let differing = 0;
  for (const [name, { text, count }] of recorded) {
    if (check) {
      const stored = await readFile(goldenPath(name), "utf8").catch(() => undefined);
      const same = stored === text;
      if (!same) differing++;
      console.log(`${same ? "same     " : "DIFFERENT"} ${name} (${count} messages)`);
    } else {
      await writeFile(goldenPath(name), text);
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
