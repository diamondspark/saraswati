import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// The metroserver parity traces, run apart from the default suite while /ws is
// still being built: `npm run test:traces`. Same Worker pool as vitest.config.ts.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.toml" },
    }),
  ],
  test: {
    include: ["test/sangat.traces.test.ts"],
    setupFiles: ["./test/setup.ts"],
  },
});
