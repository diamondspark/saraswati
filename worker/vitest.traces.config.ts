import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// The metroserver parity traces, run apart from the default suite while /ws is
// still being built: `npm run test:traces`. Same Worker pool as vitest.config.ts.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
      // Quiet window between trace steps, read by test/traces/replay.ts.
      miniflare: { bindings: { TRACE_QUIET_MS: process.env.TRACE_QUIET_MS ?? "" } },
    }),
  ],
  test: {
    include: ["test/sangat.traces.test.ts"],
    setupFiles: ["./test/setup.ts"],
  },
});
