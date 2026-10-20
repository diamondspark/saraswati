// Ask Saraswati's models, their settings and the Neuron arithmetic behind the daily caps.
//
// Checked against Cloudflare's own pages on 2026-10-19 (all under developers.cloudflare.com):
//   /workers-ai/platform/pricing/
//     Free: "10,000 Neurons per day at no charge", reset "daily at 00:00 UTC"; past it,
//     "further operations will fail with an error" (never billed). $0.011 per 1,000 Neurons.
//     @cf/meta/llama-3.1-8b-instruct-fp8   13,778 in / 26,128 out Neurons per million tokens
//     @cf/meta/llama-3.2-3b-instruct        4,625 in / 30,475 out
//   /workers-ai/models/llama-3.1-8b-instruct-fp8/ and /workers-ai/models/llama-3.2-3b-instruct/
//     both listed, no deprecation notice; input is `messages`, `max_tokens`, `temperature`
//     and `response_format`; the answer is `{response: string}`.
//   /changelog/post/2026-05-08-planned-model-deprecations/ (18 models, retired 2026-05-30):
//     neither model is on it. /changelog/post/2026-07-28-models-require-workers-paid/ (kimi-k2.6,
//     kimi-k2.7-code, glm-5.2 now need Workers Paid): neither model is on it.
//   /workers-ai/features/json-mode/ lists neither model, but the same page still lists
//     @cf/meta/llama-3.1-8b-instruct, retired on 2026-05-30, so it is out of date. Whether
//     these two honour `response_format` is therefore unknown until a real call (plan
//     Task 19). They start without it (ASK_JSON_MODE_MODELS is empty); the prompt asks for
//     JSON and the validator in intent.ts is what guarantees the output either way.
// Meta lists Hindi among the eight languages of both Llama 3.1 and 3.2; Cloudflare's pages
// do not name languages.
//
// Input size. Measured with the Llama 3 tokenizer: the fixed system text is 311 tokens; a
// request with six 40-character Devanagari titles and a 200-character Devanagari question
// is 684, and about 45 more go to the chat template. ASK_MAX_INPUT_TOKENS = 800 covers that.
// Emoji or random ids can reach about 1,200; the cap below then costs 68.8% of the allowance,
// still under 100%.
//
// Worst case per ask, both models used, 800 in and 96 out each:
//   primary   800 x 13,778 + 96 x 26,128 per million = 11.02 + 2.51 = 13.53 Neurons
//   fallback  800 x  4,625 + 96 x 30,475 per million =  3.70 + 2.93 =  6.63 Neurons
//   total 20.16. The plan's 300 a day would be 6,048 Neurons (60.5%), so the family cap is
//   250: 5,040 Neurons (50.4%) of the 10,000, under the 55% ceiling. Typical is about 13.

export const ASK_MODEL_PRIMARY = "@cf/meta/llama-3.1-8b-instruct-fp8";
export const ASK_MODEL_FALLBACK = "@cf/meta/llama-3.2-3b-instruct";

export const ASK_MAX_OUTPUT_TOKENS = 96;
export const ASK_TEMPERATURE = 0;
export const ASK_TIMEOUT_MS = 2500;

export const ASK_FREE_NEURONS_PER_DAY = 10_000;
export const ASK_MAX_INPUT_TOKENS = 800;

/** Neurons per million tokens, from the pricing page. */
const NEURONS_PER_MILLION_TOKENS = {
  [ASK_MODEL_PRIMARY]: { input: 13_778, output: 26_128 },
  [ASK_MODEL_FALLBACK]: { input: 4_625, output: 30_475 },
} as const;

/** One ask that uses both models, each with the largest input and output. */
export function askWorstCaseNeurons(): number {
  return Object.values(NEURONS_PER_MILLION_TOKENS).reduce(
    (total, rate) =>
      total +
      (ASK_MAX_INPUT_TOKENS * rate.input + ASK_MAX_OUTPUT_TOKENS * rate.output) / 1_000_000,
    0,
  );
}

export const ASK_SCHEMA = {
  type: "object",
  properties: {
    kind: {
      type: "string",
      enum: ["search", "play_station", "parivaar_mix", "sangat", "answer"],
    },
    query: { type: "string" },
    stationId: { type: "string" },
    text: { type: "string" },
  },
  required: ["kind"],
} as const;

/** Models known to take `response_format`; none yet (see the header). */
export const ASK_JSON_MODE_MODELS: ReadonlySet<string> = new Set();

export function modelInput(
  model: string,
  messages: Array<{ role: string; content: string }>,
  jsonModeModels: ReadonlySet<string> = ASK_JSON_MODE_MODELS,
): object {
  const input = {
    messages,
    max_tokens: ASK_MAX_OUTPUT_TOKENS,
    temperature: ASK_TEMPERATURE,
  };
  return jsonModeModels.has(model)
    ? { ...input, response_format: { type: "json_schema", json_schema: ASK_SCHEMA } }
    : input;
}
