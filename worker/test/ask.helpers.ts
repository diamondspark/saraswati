// Shared by the Ask Saraswati tests: a fake AiRunner (no test ever calls a real model), a way
// to run handleAsk inside the hub with its real storage, limiter and kill switches, and
// readers for the tables Ask touches.

import { runInDurableObject } from "cloudflare:test";
import { vi } from "vitest";
import type { AiRunner } from "../src/ask/ai";
import type { AuthResult, HubContext } from "../src/hubContext";
import { handleAsk } from "../src/routes/ask";
import { DEVICE_PREFIX, HUB_ORIGIN, sql, type Device, type Hub } from "./sangat.helpers";

export type ModelCall = { model: string; input: any; timeoutMs: number };

/**
 * One step per model call, in order. A function is called with the call; an Error is thrown
 * as a rejection; anything else is the model's answer. A call past the last step rejects,
 * so a test that did not expect it sees it in `calls`.
 */
export type Step = unknown | Error | ((call: ModelCall) => unknown);

export function fakeAi(...steps: Step[]): AiRunner & { calls: ModelCall[] } {
  const calls: ModelCall[] = [];
  return {
    calls,
    async run(model, input, timeoutMs) {
      const call = { model, input, timeoutMs };
      const step = steps[calls.length];
      const unscripted = calls.length >= steps.length;
      calls.push(call);
      if (unscripted) throw new Error("unscripted model call");
      if (step instanceof Error) throw step;
      return typeof step === "function" ? (step as (c: ModelCall) => unknown)(call) : step;
    },
  };
}

/** What the model returns in string mode. */
export const said = (intent: unknown) => ({ response: JSON.stringify(intent) });

export const MORNING = "subah ke bhajan sunao";
export const search = (query: string) => ({ kind: "search", query });

export type Answer = { status: number; body: any };

/** Runs handleAsk as `device` inside the hub, over the hub's own HubContext. */
export function askAs(
  hub: Hub,
  device: Device,
  body: unknown,
  ai: AiRunner | ((db: SqlStorage) => AiRunner),
  opts: { now?: number; auth?: AuthResult } = {},
): Promise<Answer> {
  return runInDurableObject(hub, async (instance, state) => {
    const base = (instance as unknown as { hubContext: HubContext }).hubContext;
    const now = opts.now;
    const ctx: HubContext = now === undefined ? base : { ...base, now: () => now };
    const request = new Request(`${HUB_ORIGIN}/ask`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: typeof body === "string" ? body : JSON.stringify(body),
    });
    const auth = opts.auth === undefined ? { role: "device" as const, deviceId: device.deviceId } : opts.auth;
    const runner = typeof ai === "function" ? ai(state.storage.sql) : ai;
    const response = await handleAsk(ctx, request, auth, runner);
    return { status: response.status, body: await response.json() };
  });
}

export const validBody = (question = MORNING, locale = "hi-IN") => ({ question, locale });

export const usage = (hub: Hub) =>
  sql(hub, (db) =>
    db
      .exec<{ day: string; key: string; count: number }>("SELECT day, key, count FROM ask_usage ORDER BY day, key")
      .toArray(),
  );

export const setStations = (hub: Hub, stations: Array<{ id: string; title: string }>) =>
  sql(hub, (db) => {
    db.exec("DELETE FROM stations");
    stations.forEach((s, position) => {
      db.exec(
        "INSERT INTO stations (id, title, seedVideoId, iconIndex, position) VALUES (?, ?, 'seed', 0, ?)",
        s.id,
        s.title,
        position,
      );
    });
  });

/** Removes a device's burst bucket, as if a minute had passed. */
export const clearBurst = (hub: Hub, device: Device) =>
  sql(hub, (db) => {
    db.exec("DELETE FROM rate_limits WHERE key = ?", `ask:${device.deviceId}`);
  });

/** Back to an untouched hub for the Ask tests. */
export async function resetAsk(hub: Hub): Promise<void> {
  await sql(hub, (db) => {
    db.exec("DELETE FROM ask_usage");
    db.exec("DELETE FROM stations");
    db.exec("DELETE FROM health");
    db.exec("DELETE FROM rate_limits WHERE key LIKE 'ask:%'");
    db.exec("DELETE FROM roster WHERE deviceId LIKE ?", `${DEVICE_PREFIX}%`);
    db.exec("UPDATE killswitches SET enabled = 1");
  });
}

/** Every row of every table, as one string, to look for text that must not be stored. */
export const everythingStored = (hub: Hub) =>
  sql(hub, (db) => {
    const tables = db
      .exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE '\\_cf\\_%' ESCAPE '\\'")
      .toArray();
    const dump: Record<string, unknown[]> = {};
    for (const { name } of tables) dump[name] = db.exec(`SELECT * FROM "${name}"`).toArray();
    return JSON.stringify(dump);
  });

/**
 * Runs `fn` with every console method recorded and silenced, and returns what was written as one
 * string. The hub runs in this isolate, so this sees console calls made inside it.
 */
export async function consoleOutput(fn: () => Promise<unknown>): Promise<string> {
  const spies = (["log", "info", "warn", "error", "debug"] as const).map((level) =>
    vi.spyOn(console, level).mockImplementation(() => {}),
  );
  try {
    await fn();
    return JSON.stringify(spies.flatMap((spy) => spy.mock.calls));
  } finally {
    for (const spy of spies) spy.mockRestore();
  }
}
