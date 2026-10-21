// Shared by the Parivaar and family-playlist tests: HTTP calls to the one FamilyHub with the
// body always read (an unread response keeps the Durable Object busy, and the next test's
// reset would wait on it), and a reset that needs no eviction because these routes open no sockets.

import { runInDurableObject } from "cloudflare:test";
import type { HubContext } from "../src/hubContext";
import { DEVICE_PREFIX, HUB_ORIGIN, sql, type Hub } from "./sangat.helpers";

// Must match the ADMIN_KEY binding injected in vitest.config.ts.
export const TEST_ADMIN_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
export const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export type Reply = { status: number; body: any; contentType: string | null };

async function toReply(response: Response): Promise<Reply> {
  const text = await response.text();
  let parsed: unknown = text;
  try {
    parsed = JSON.parse(text);
  } catch {
    // not JSON; the text stays
  }
  return { status: response.status, body: parsed, contentType: response.headers.get("content-type") };
}

export async function call(
  hub: Hub,
  method: string,
  path: string,
  opts: { token?: string | null; body?: unknown } = {},
): Promise<Reply> {
  const headers: Record<string, string> = {};
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  let body: string | undefined;
  if (opts.body !== undefined) {
    headers["content-type"] = "application/json";
    body = typeof opts.body === "string" ? opts.body : JSON.stringify(opts.body);
  }
  return toReply(await hub.fetch(`${HUB_ORIGIN}${path}`, { method, headers, body }));
}

/**
 * A request whose body is streamed: nothing is sent until `send` is called, which can be in
 * several chunks (so the request has no Content-Length), and `end` finishes it. The hub is left
 * waiting on the body in between, which is where a revoke can land.
 */
export function streamedCall(hub: Hub, method: string, path: string, token: string | null) {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const reply = hub
    .fetch(`${HUB_ORIGIN}${path}`, { method, headers, body: stream, duplex: "half" } as RequestInit)
    .then(toReply);
  const encoder = new TextEncoder();
  return {
    reply,
    send: (chunk: string) => controller.enqueue(encoder.encode(chunk)),
    end: () => controller.close(),
  };
}

export const setSwitch = (hub: Hub, key: string, enabled: boolean) =>
  call(hub, "PUT", "/killswitches", { token: TEST_ADMIN_KEY, body: { [key]: enabled } });

export const revoke = (hub: Hub, deviceId: string) =>
  call(hub, "POST", `/roster/${deviceId}/revoke`, { token: TEST_ADMIN_KEY });

/** Empties both registries and the device-write rate buckets, and turns every switch back on. */
export async function resetRegistries(hub: Hub): Promise<void> {
  await sql(hub, (db) => {
    db.exec("DELETE FROM parivaar_playlists");
    db.exec("DELETE FROM family_playlists");
    db.exec("DELETE FROM rate_limits WHERE key LIKE 'parivaar:%' OR key LIKE 'familyplaylists:%'");
    db.exec("DELETE FROM roster WHERE deviceId LIKE ?", `${DEVICE_PREFIX}%`);
    db.exec("UPDATE killswitches SET enabled = 1");
  });
}

export const rateKeys = (hub: Hub) =>
  sql(hub, (db) =>
    db
      .exec<{ key: string }>("SELECT key FROM rate_limits WHERE key != 'admin' ORDER BY key")
      .toArray()
      .map((row) => row.key),
  );

export const rateRow = (hub: Hub, key: string) =>
  sql(hub, (db) =>
    db
      .exec<{ windowStart: number; count: number }>(
        "SELECT windowStart, count FROM rate_limits WHERE key = ?",
        key,
      )
      .toArray()[0],
  );

/** Moves a rate window `seconds` into the past, as if that much time had gone by. */
export const ageRateWindow = (hub: Hub, key: string, seconds: number) =>
  sql(hub, (db) => {
    db.exec("UPDATE rate_limits SET windowStart = windowStart - ? WHERE key = ?", seconds * 1000, key);
  });

/** Runs `fn` inside the hub, so a handler can be called with a stand-in HubContext. */
export const inHub = <T>(hub: Hub, fn: (sqlStorage: SqlStorage) => Promise<T> | T): Promise<T> =>
  runInDurableObject(hub, (_instance, state) => fn(state.storage.sql));

/**
 * A HubContext over the hub's real storage whose clock, switches and limiter the test
 * controls. `rateCalls` records every rateLimit(key, windowSeconds, max) the handler made.
 * `isApproved` reads the roster, as the hub does, unless `approved` fixes the answer.
 */
export function stubContext(
  db: SqlStorage,
  opts: { now?: number; allowed?: boolean; enabled?: boolean; approved?: boolean } = {},
): HubContext & { rateCalls: [string, number, number][] } {
  const rateCalls: [string, number, number][] = [];
  return {
    sql: db,
    rateLimit(key, windowSeconds, max) {
      rateCalls.push([key, windowSeconds, max]);
      return opts.allowed ?? true;
    },
    featureEnabled: () => opts.enabled ?? true,
    isApproved: (deviceId) =>
      opts.approved ??
      db.exec("SELECT 1 FROM roster WHERE deviceId = ? AND status = 'approved'", deviceId).toArray().length > 0,
    now: () => opts.now ?? Date.now(),
    mirrorFeedback: async () => null,
    rateCalls,
  };
}
