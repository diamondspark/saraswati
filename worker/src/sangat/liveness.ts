// What the hub knows about a socket from the messages it sends: how often (the
// 60 messages per second limit) and when it was last heard from (the idle
// timeout). Both live in the socket's attachment, so they survive hibernation.

import {
  IDLE_TIMEOUT_MS,
  RATE_LIMIT_MESSAGES_PER_SECOND,
  RATE_LIMIT_STRIKES_TO_CLOSE,
  RATE_LIMIT_WINDOW_MS,
} from "./limits";
import type { Attachment } from "./types";

/**
 * Counts one message that arrived at `now` and notes that the socket is alive.
 * False when it is over the limit: a fixed window of RATE_LIMIT_WINDOW_MS that
 * starts at the first message after the previous window ended, as in
 * metroserver's allowMessage. The message that is refused still proves the
 * socket is alive.
 *
 * Each window in which a message was refused is also counted as a strike, and a
 * run of strikes in windows that follow one another is what floodedTooLong looks at.
 */
export function recordMessage(att: Attachment, now: number): boolean {
  att.lastSeen = now;
  if (att.rateStart === undefined || now < att.rateStart || now - att.rateStart >= RATE_LIMIT_WINDOW_MS) {
    // The run goes on only if the window that just ended had a refusal and this one
    // starts within a window's length of it, with no quiet window in between.
    const follows =
      att.rateStart !== undefined && now >= att.rateStart && now - att.rateStart < 2 * RATE_LIMIT_WINDOW_MS;
    if (!(follows && att.rateStruck)) att.rateStrikes = 0;
    att.rateStruck = false;
    att.rateStart = now;
    att.rateCount = 0;
  }
  const count = att.rateCount ?? 0;
  if (count >= RATE_LIMIT_MESSAGES_PER_SECOND) {
    if (!att.rateStruck) {
      att.rateStruck = true;
      att.rateStrikes = (att.rateStrikes ?? 0) + 1;
    }
    return false;
  }
  att.rateCount = count + 1;
  return true;
}

/** True once messages have been refused in RATE_LIMIT_STRIKES_TO_CLOSE windows in a row: the socket is flooding, not bursting. */
export function floodedTooLong(att: Attachment): boolean {
  return (att.rateStrikes ?? 0) >= RATE_LIMIT_STRIKES_TO_CLOSE;
}

/**
 * When this socket counts as dead if it stays silent, or null for one that is
 * already closed or has never been heard from.
 */
export function idleDeadline(att: Attachment): number | null {
  if (att.closed || att.lastSeen === undefined) return null;
  return att.lastSeen + IDLE_TIMEOUT_MS;
}
