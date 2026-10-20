// How much one phone and the whole family may ask in a UTC day. Every ask that reaches a
// model is charged once, before the model is called, however it ends. The caps are sized
// in models.ts so the worst case stays well under the free Neuron allowance.

export const ASK_DEVICE_DAILY_MAX = 25;
export const ASK_FAMILY_DAILY_MAX = 250;
/** Per device, through the hub's shared limiter under the key `ask:<deviceId>`. */
export const ASK_BURST_MAX = 5;
export const ASK_BURST_WINDOW_SECONDS = 60;

const DAY_MS = 24 * 60 * 60 * 1000;
const FAMILY_KEY = "family";

interface UsageRow extends Record<string, SqlStorageValue> {
  key: string;
  count: number;
}

/** The UTC date, as stored in ask_usage.day. */
export const utcDay = (epochMs: number): string => new Date(epochMs).toISOString().slice(0, 10);

/** The next 00:00 UTC, when Cloudflare's allowance and these counters start again. */
export function nextResetAt(epochMs: number): string {
  const now = new Date(epochMs);
  return new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1),
  ).toISOString();
}

export type Charge = { ok: true } | { ok: false; resetAt: string };

/**
 * Spends one ask from the device's day and the family's day, or says when they reset. All of
 * it runs without an await, so two asks cannot interleave. Rows older than yesterday go on a
 * successful charge.
 */
export function chargeAsk(sql: SqlStorage, deviceId: string, nowMs: number): Charge {
  const day = utcDay(nowMs);
  const deviceKey = `device:${deviceId}`;
  const used = new Map(
    sql
      .exec<UsageRow>(
        "SELECT key, count FROM ask_usage WHERE day = ? AND key IN (?, ?)",
        day,
        deviceKey,
        FAMILY_KEY,
      )
      .toArray()
      .map((row) => [row.key, row.count]),
  );
  if (
    (used.get(deviceKey) ?? 0) >= ASK_DEVICE_DAILY_MAX ||
    (used.get(FAMILY_KEY) ?? 0) >= ASK_FAMILY_DAILY_MAX
  ) {
    return { ok: false, resetAt: nextResetAt(nowMs) };
  }

  sql.exec("DELETE FROM ask_usage WHERE day < ?", utcDay(nowMs - DAY_MS));
  for (const key of [deviceKey, FAMILY_KEY]) {
    sql.exec(
      `INSERT INTO ask_usage (day, key, count) VALUES (?, ?, 1)
       ON CONFLICT(day, key) DO UPDATE SET count = count + 1`,
      day,
      key,
    );
  }
  return { ok: true };
}
