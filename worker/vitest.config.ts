import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// Fixture-only value for the admin bearer token, injected directly into the
// Miniflare-simulated Worker environment so the tests can exercise admin-authed
// routes. This is NOT the owner's real `ADMIN_KEY` secret: that is a 64-hex
// value the owner generated and set with `wrangler secret put` on their own
// machine, and no agent has ever seen it. wrangler.toml deliberately declares
// no `ADMIN_KEY` var, so this override is additive and has nothing to conflict
// with. It is 64 characters long on purpose: the hub ignores a key shorter than
// 32. Test files that need to authenticate as admin use this exact literal
// (TEST_ADMIN_KEY in test/helpers.ts).
const TEST_ADMIN_KEY =
  "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

export default defineConfig({
  plugins: [
    cloudflareTest({
      // wrangler.test.toml is wrangler.toml minus [ai]. The AI binding is remote-only: the pool
      // would sign in with the machine's Cloudflare login to reach it. Tests fake it.
      // remoteBindings: false is the second lock: even a config that had a remote binding
      // would get no remote session. test/ask.units.test.ts checks both lines.
      wrangler: { configPath: "./wrangler.test.toml" },
      remoteBindings: false,
      miniflare: {
        // TRACE_QUIET_MS is the quiet window between trace steps, read by
        // test/traces/replay.ts: TRACE_QUIET_MS=<ms> npx vitest run test/sangat.traces.test.ts
        bindings: { ADMIN_KEY: TEST_ADMIN_KEY, TRACE_QUIET_MS: process.env.TRACE_QUIET_MS ?? "" },
      },
    }),
  ],
  test: {
    setupFiles: ["./test/setup.ts"],
  },
});
