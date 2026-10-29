// FCM push registration: each phone tells the hub the token Firebase gave its app install, so
// the hub can wake the app when a Sangat starts. Device only, and not tied to the sangat
// kill switch (a token is harmless to keep while Sangat is off). Checks run in this order:
// auth (401), body size (413), body (400), write limit (429), as for the Parivaar routes.

import { json } from "../http";
import type { RouteHandler } from "../hubContext";
import { readWriteBody, tooManyRequests, unauthorized } from "./common";

// Real FCM tokens are around 160 characters; this is generous, and only the characters FCM
// itself uses.
const MAX_TOKEN_LENGTH = 4096;
const TOKEN_PATTERN = /^[A-Za-z0-9_:.\-]+$/;
const TOKEN_ERROR = `token must be 1-${MAX_TOKEN_LENGTH} characters: letters, digits, _ : . or -`;
// Per device: 20 registrations an hour.
const WRITE_WINDOW_SECONDS = 3600;
const WRITE_MAX = 20;

const noContent = () => new Response(null, { status: 204 });

/**
 * PUT /push/token: sets the calling device's token. A deviceId in the body is never read. A
 * token is one app install's, so if another device's row holds it, that row is dropped
 * (the app was moved or the roster changed) rather than left to be pushed twice.
 */
export const putPushToken: RouteHandler = async (ctx, request, auth) => {
  if (auth?.role !== "device") {
    return unauthorized();
  }

  // Nothing is awaited after this, so the revoke check in it holds up to the write.
  const body = await readWriteBody(ctx, request, auth.deviceId);
  if (body instanceof Response) {
    return body;
  }
  const token = body.token;
  if (typeof token !== "string" || token.length > MAX_TOKEN_LENGTH || !TOKEN_PATTERN.test(token)) {
    return json({ error: TOKEN_ERROR }, 400);
  }

  if (!ctx.rateLimit(`push-token:${auth.deviceId}`, WRITE_WINDOW_SECONDS, WRITE_MAX)) {
    return tooManyRequests();
  }

  // Both statements run in the same turn, with nothing awaited between them.
  ctx.sql.exec("DELETE FROM push_tokens WHERE token = ? AND deviceId != ?", token, auth.deviceId);
  ctx.sql.exec(
    `INSERT INTO push_tokens (deviceId, token, updatedAt)
     VALUES (?, ?, ?)
     ON CONFLICT(deviceId) DO UPDATE SET
       token = excluded.token,
       updatedAt = excluded.updatedAt`,
    auth.deviceId,
    token,
    ctx.now(),
  );
  return noContent();
};

/** DELETE /push/token: forgets the calling device's token. 204 whether or not it had one. */
export const deletePushToken: RouteHandler = async (ctx, _request, auth) => {
  if (auth?.role !== "device") {
    return unauthorized();
  }
  ctx.sql.exec("DELETE FROM push_tokens WHERE deviceId = ?", auth.deviceId);
  return noContent();
};
