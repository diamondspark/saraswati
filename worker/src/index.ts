import { FamilyHub } from "./FamilyHub";

export { FamilyHub };

export interface Env {
  FAMILY_HUB: DurableObjectNamespace;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const id = env.FAMILY_HUB.idFromName("family");
    const stub = env.FAMILY_HUB.get(id);
    return stub.fetch(request);
  },
} satisfies ExportedHandler<Env>;
