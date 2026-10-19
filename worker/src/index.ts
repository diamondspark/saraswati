import { FamilyHub } from "./FamilyHub";

export { FamilyHub };

export interface Env {
  FAMILY_HUB: DurableObjectNamespace;
  // Workers AI, for POST /ask. A binding holds no key; wrangler.toml declares it with [ai].
  AI: Ai;
  // Worker secret, set via `wrangler secret put ADMIN_KEY` (Task 2 Step 8,
  // a human checkpoint not yet done at the time of writing). `authenticate()`
  // in FamilyHub.ts checks bearer tokens against this value first.
  ADMIN_KEY: string;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const id = env.FAMILY_HUB.idFromName("family");
    const stub = env.FAMILY_HUB.get(id);
    return stub.fetch(request);
  },
} satisfies ExportedHandler<Env>;
