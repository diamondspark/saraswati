import { describe, expect, it } from "vitest";
import { GOLDENS } from "./traces/goldens.ts";
import { replayAgainstWorker } from "./traces/replay.ts";
import { SCENARIO_NAMES } from "./traces/scenarios.ts";

// Parity with metroserver (MetrolistGroup/metroserver @ 9da6f9d): each golden is
// what the Go server sent three clients for one scenario, normalized. The
// Worker must send the same messages, in the same order per client.
//
// They run with the rest of the suite (`npx vitest run`), so a change that breaks
// parity fails the default gate.
//
// To re-record the goldens, run metroserver in Docker and point the recorder at it;
// the instructions are in scripts/record-traces.ts.
describe("metroserver reference traces", () => {
  for (const name of SCENARIO_NAMES) {
    it(name, async () => {
      expect(await replayAgainstWorker(name)).toEqual(GOLDENS[name]);
    }, 60_000);
  }
});
