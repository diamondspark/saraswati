// POST /ask: the phone sends a question and a locale, and gets back one intent to act on.
// Workers AI only routes the request (to a YouTube search, a station, Parivaar Mix or Sangat);
// it never picks music, and anything it says that is not a plain intent becomes a search for
// the person's own words. Checks run in this order: auth (401), kill switch (503), body (400),
// burst limit (429), daily limit (429). The question is never stored and never logged.

import type { AiRunner } from "../ask/ai";
import { ASK_BURST_MAX, ASK_BURST_WINDOW_SECONDS, chargeAsk } from "../ask/caps";
import { CONTROL, parseIntent } from "../ask/intent";
import { ASK_MODEL_FALLBACK, ASK_MODEL_PRIMARY, ASK_TIMEOUT_MS, modelInput } from "../ask/models";
import { buildMessages } from "../ask/prompt";
import { json, readStrictJsonObject } from "../http";
import type { AuthResult, HubContext } from "../hubContext";
import { cleanText, featureDisabled, tooManyRequests, unauthorized } from "./common";

const QUESTION_MAX = 200;
const LOCALE = /^[a-z]{2}(-[A-Z]{2})?$/;

interface StationRow extends Record<string, SqlStorageValue> {
  id: string;
  title: string;
}

function validQuestion(value: unknown): string | null {
  const question = cleanText(value, QUESTION_MAX);
  return question !== null && !CONTROL.test(question) ? question : null;
}

type Messages = Array<{ role: string; content: string }>;

/**
 * The primary model, then, only if it threw or timed out, the fallback model once. A reply
 * that came back goes to the validator whatever it says; it never earns a second call.
 */
async function askModels(
  ai: AiRunner,
  messages: Messages,
  question: string,
  stationIds: ReadonlySet<string>,
) {
  for (const model of [ASK_MODEL_PRIMARY, ASK_MODEL_FALLBACK]) {
    let reply: unknown;
    try {
      reply = await ai.run(model, modelInput(model, messages), ASK_TIMEOUT_MS);
    } catch {
      continue;
    }
    return parseIntent(reply, question, stationIds);
  }
  return parseIntent(undefined, question, stationIds);
}

export async function handleAsk(
  ctx: HubContext,
  request: Request,
  auth: AuthResult,
  ai: AiRunner,
): Promise<Response> {
  if (auth?.role !== "device") {
    return unauthorized();
  }
  if (!ctx.featureEnabled("ask_saraswati")) {
    return featureDisabled();
  }

  const body = await readStrictJsonObject(request);
  const question = validQuestion(body?.question);
  const locale = body?.locale;
  if (question === null) {
    return json(
      { error: `question must be 1-${QUESTION_MAX} characters with no control characters` },
      400,
    );
  }
  if (typeof locale !== "string" || !LOCALE.test(locale)) {
    return json({ error: "locale must look like hi or hi-IN" }, 400);
  }

  if (!ctx.rateLimit(`ask:${auth.deviceId}`, ASK_BURST_WINDOW_SECONDS, ASK_BURST_MAX)) {
    return tooManyRequests();
  }
  const charge = chargeAsk(ctx.sql, auth.deviceId, ctx.now());
  if (!charge.ok) {
    return json({ error: "daily limit", resetAt: charge.resetAt }, 429);
  }

  const stations = ctx.sql
    .exec<StationRow>("SELECT id, title FROM stations ORDER BY position")
    .toArray();
  const stationIds = new Set(stations.map((station) => station.id));
  return json(await askModels(ai, buildMessages(question, locale, stations), question, stationIds));
}
