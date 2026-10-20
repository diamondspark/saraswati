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
      wrangler: { configPath: "./wrangler.toml" },
      miniflare: {
        bindings: { ADMIN_KEY: TEST_ADMIN_KEY },
      },
    }),
  ],
  test: {
    setupFiles: ["./test/setup.ts"],
  },
});
