// `cloudflare:test`'s `env` is typed as the global `Cloudflare.Env`, which
// `wrangler types` would normally fill in. Mirror the Worker's own `Env`
// (src/index.ts) so tests see the real bindings.
declare namespace Cloudflare {
  interface Env {
    FAMILY_HUB: DurableObjectNamespace<import("./FamilyHub").FamilyHub>;
    ADMIN_KEY: string;
    GITHUB_FEEDBACK_TOKEN?: string;
    GITHUB_FEEDBACK_REPO?: string;
    // Set with `npx wrangler secret put FCM_SERVICE_ACCOUNT < key.json`; tests inject a fake one.
    FCM_SERVICE_ACCOUNT?: string;
    // Declared in wrangler.toml but not in wrangler.test.toml, so tests see it undefined.
    AI: Ai;
  }
}
