// Parivaar Mix: each phone publishes the id of its own playlist and the name to show for
// it, and every phone reads the whole family's list. Checks run in this order: auth (401),
// kill switch (503), body size (413), body (400), write limit (429). A device revoked while
// its body was arriving is 401, as at the door.

import { json } from "../http";
import type { HubContext, RouteHandler } from "../hubContext";
import {
  cleanText,
  featureDisabled,
  isoTime,
  isPlaylistId,
  PLAYLIST_ID_ERROR,
  readWriteBody,
  tooManyRequests,
  unauthorized,
} from "./common";

const MAX_MEMBER_NAME_LENGTH = 50;
// Per device: 10 writes an hour.
const WRITE_WINDOW_SECONDS = 3600;
const WRITE_MAX = 10;

interface ParivaarRow extends Record<string, SqlStorageValue> {
  deviceId: string;
  memberName: string;
  playlistId: string;
  updatedAt: number;
}

const rowJson = (row: ParivaarRow) => ({
  deviceId: row.deviceId,
  memberName: row.memberName,
  playlistId: row.playlistId,
  updatedAt: isoTime(row.updatedAt),
});

/**
 * PUT /parivaar/playlist: sets the calling device's own row. Device only. A deviceId in
 * the body is never read: the row is the authenticated device's, so one phone cannot
 * write another's.
 */
export const putParivaarPlaylist: RouteHandler = async (ctx, request, auth) => {
  if (auth?.role !== "device") {
    return unauthorized();
  }
  if (!ctx.featureEnabled("parivaar_mix")) {
    return featureDisabled();
  }

  // Nothing is awaited after this, so the revoke check in it holds up to the write.
  const body = await readWriteBody(ctx, request, auth.deviceId);
  if (body instanceof Response) {
    return body;
  }
  const playlistId = body.playlistId;
  const memberName = cleanText(body.memberName, MAX_MEMBER_NAME_LENGTH);
  if (!isPlaylistId(playlistId)) {
    return json({ error: PLAYLIST_ID_ERROR }, 400);
  }
  if (memberName === null) {
    return json(
      { error: `memberName must be 1-${MAX_MEMBER_NAME_LENGTH} characters` },
      400,
    );
  }

  if (!ctx.rateLimit(`parivaar:${auth.deviceId}`, WRITE_WINDOW_SECONDS, WRITE_MAX)) {
    return tooManyRequests();
  }

  const row: ParivaarRow = {
    deviceId: auth.deviceId,
    memberName,
    playlistId,
    updatedAt: ctx.now(),
  };
  ctx.sql.exec(
    `INSERT INTO parivaar_playlists (deviceId, memberName, playlistId, updatedAt)
     VALUES (?, ?, ?, ?)
     ON CONFLICT(deviceId) DO UPDATE SET
       memberName = excluded.memberName,
       playlistId = excluded.playlistId,
       updatedAt = excluded.updatedAt`,
    row.deviceId,
    row.memberName,
    row.playlistId,
    row.updatedAt,
  );
  return json(rowJson(row));
};

/**
 * GET /parivaar/playlists: every approved device's row. A device or the admin may ask. A
 * revoked or pending device never shows, whatever its row says.
 */
export const listParivaarPlaylists: RouteHandler = async (ctx, _request, auth) => {
  if (!auth) {
    return unauthorized();
  }
  if (!ctx.featureEnabled("parivaar_mix")) {
    return featureDisabled();
  }

  return json({ playlists: readApprovedRows(ctx).map(rowJson) });
};

function readApprovedRows(ctx: HubContext): ParivaarRow[] {
  return ctx.sql
    .exec<ParivaarRow>(
      `SELECT p.deviceId, p.memberName, p.playlistId, p.updatedAt
       FROM parivaar_playlists p JOIN roster r ON r.deviceId = p.deviceId
       WHERE r.status = 'approved'
       ORDER BY p.memberName, p.deviceId`,
    )
    .toArray();
}
