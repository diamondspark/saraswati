import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";

/**
 * Single global Durable Object backing the Saraswati family hub.
 *
 * Routing to the one instance (idFromName("family")) happens in
 * index.ts, not here. This class owns the instance's SQLite storage
 * and request handling.
 */
export class FamilyHub extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);

    // Table creation happens here. There are no tables yet — later tasks
    // add pairing_tokens, roster, stations, killswitches, health, and
    // rate_limits. This statement only proves the SQLite storage binding
    // is wired and functional.
    ctx.storage.sql.exec("SELECT 1");
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/ping") {
      return new Response("ok", { status: 200 });
    }

    return new Response("not found", { status: 404 });
  }
}
