import { appLinkResponse } from "./appLinks";
import { bodyLimitResponse } from "./bodyLimit";
import { FamilyHub } from "./FamilyHub";
import { bearerToken, matchRoute } from "./routes";

export { FamilyHub };

export interface Env {
  FAMILY_HUB: DurableObjectNamespace;
  // Workers AI, for POST /ask. A binding holds no key; wrangler.toml declares it with [ai].
  AI: Ai;
  // Worker secret, set with `wrangler secret put ADMIN_KEY`; the owner's own
  // 64-hex-character value, which no agent ever sees. `authenticate()` in
  // FamilyHub.ts checks bearer tokens against it first (trimmed, like the
  // token), and treats a value shorter than 32 characters as not set at all.
  ADMIN_KEY: string;
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    // The app-link pages are public and static: answered here, before the hub
    // is even looked up, so they cannot touch its storage or its rate limits.
    const appLink = appLinkResponse(request);
    if (appLink) return appLink;

    // Anything the hub would refuse without ever reading its storage is refused
    // here instead. A Durable Object request costs duration and a possible
    // cold start, and it is the one thing anyone on the internet can otherwise
    // run up for free by guessing paths. Worker requests are still counted
    // against the free quota; only the Durable Object time is saved.
    const route = matchRoute(request.method, new URL(request.url).pathname);
    if (!route) {
      return new Response("not found", { status: 404 });
    }
    if (route.needsBearer && bearerToken(request) === null) {
      return json({ error: "unauthorized" }, 401);
    }
    const tooLarge = bodyLimitResponse(request);
    if (tooLarge) return tooLarge;

    const id = env.FAMILY_HUB.idFromName("family");
    const stub = env.FAMILY_HUB.get(id);
    return stub.fetch(request);
  },
} satisfies ExportedHandler<Env>;
