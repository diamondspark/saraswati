// `cloudflare:test`'s `env` is typed as the global `Cloudflare.Env`, which
// `wrangler types` would normally fill in. Mirror the Worker's own `Env`
// (src/index.ts) so tests see the real bindings.
declare namespace Cloudflare {
  interface Env {
    FAMILY_HUB: DurableObjectNamespace<import("./FamilyHub").FamilyHub>;
    ADMIN_KEY: string;
  }
}
