// Family playlists: up to 20 YouTube playlists the whole family shares. Any device may add
// one; the device that added it, or the owner, may remove it. Checks run in this order:
// auth (401), kill switch (503), body (400), write limit (429), then the rules that read
// the stored rows (409, 404, 403).

import { json, readStrictJsonObject } from "../http";
import type { AuthResult, HubContext, RouteHandler } from "../hubContext";
import {
  cleanText,
  featureDisabled,
  isoTime,
  isPlaylistId,
  PLAYLIST_ID_ERROR,
  tooManyRequests,
  unauthorized,
} from "./common";

const MAX_PLAYLISTS = 20;
const MAX_TITLE_LENGTH = 150;
// Per device: 20 additions an hour. Removing one does not give the budget back.
const WRITE_WINDOW_SECONDS = 3600;
const WRITE_MAX = 20;

interface FamilyPlaylistRow extends Record<string, SqlStorageValue> {
  id: string;
  playlistId: string;
  title: string;
  addedByDeviceId: string;
  addedAt: number;
}

const rowJson = (row: FamilyPlaylistRow) => ({
  id: row.id,
  playlistId: row.playlistId,
  title: row.title,
  addedByDeviceId: row.addedByDeviceId,
  addedAt: isoTime(row.addedAt),
});

/** GET /family-playlists: oldest first. A device or the admin may ask. */
export const listFamilyPlaylists: RouteHandler = async (ctx, _request, auth) => {
  if (!auth) {
    return unauthorized();
  }
  if (!ctx.featureEnabled("family_playlists")) {
    return featureDisabled();
  }

  const playlists = ctx.sql
    .exec<FamilyPlaylistRow>(
      "SELECT id, playlistId, title, addedByDeviceId, addedAt FROM family_playlists ORDER BY addedAt, rowid",
    )
    .toArray();
  return json({ playlists: playlists.map(rowJson) });
};

/** POST /family-playlists: device only. 201 with the new row. */
export const addFamilyPlaylist: RouteHandler = async (ctx, request, auth) => {
  if (auth?.role !== "device") {
    return unauthorized();
  }
  if (!ctx.featureEnabled("family_playlists")) {
    return featureDisabled();
  }

  const body = await readStrictJsonObject(request);
  const playlistId = body?.playlistId;
  const title = cleanText(body?.title, MAX_TITLE_LENGTH);
  if (!isPlaylistId(playlistId)) {
    return json({ error: PLAYLIST_ID_ERROR }, 400);
  }
  if (title === null) {
    return json({ error: `title must be 1-${MAX_TITLE_LENGTH} characters` }, 400);
  }

  if (!ctx.rateLimit(`familyplaylists:${auth.deviceId}`, WRITE_WINDOW_SECONDS, WRITE_MAX)) {
    return tooManyRequests();
  }

  // Nothing is awaited from here to the insert, so the checks and the write cannot interleave
  // with another request.
  const duplicate = ctx.sql
    .exec("SELECT 1 FROM family_playlists WHERE playlistId = ?", playlistId)
    .toArray();
  if (duplicate.length > 0) {
    return json({ error: "playlist already added" }, 409);
  }
  if (countPlaylists(ctx) >= MAX_PLAYLISTS) {
    return json({ error: "family playlist limit reached" }, 409);
  }

  const row: FamilyPlaylistRow = {
    id: crypto.randomUUID(),
    playlistId,
    title,
    addedByDeviceId: auth.deviceId,
    addedAt: ctx.now(),
  };
  ctx.sql.exec(
    "INSERT INTO family_playlists (id, playlistId, title, addedByDeviceId, addedAt) VALUES (?, ?, ?, ?, ?)",
    row.id,
    row.playlistId,
    row.title,
    row.addedByDeviceId,
    row.addedAt,
  );
  return json(rowJson(row), 201);
};

/**
 * DELETE /family-playlists/{id}: the owner's admin key, or the device that added it. A
 * device that was revoked can no longer authenticate, so only the admin can remove what it
 * added. Not rate limited: each playlist a device can remove cost it a limited POST.
 */
export async function deleteFamilyPlaylist(
  ctx: HubContext,
  _request: Request,
  auth: AuthResult,
  id: string,
): Promise<Response> {
  if (!auth) {
    return unauthorized();
  }
  if (!ctx.featureEnabled("family_playlists")) {
    return featureDisabled();
  }

  const row = ctx.sql
    .exec<{ addedByDeviceId: string } & Record<string, SqlStorageValue>>(
      "SELECT addedByDeviceId FROM family_playlists WHERE id = ?",
      id,
    )
    .toArray()[0];
  if (!row) {
    return json({ error: "not found" }, 404);
  }
  if (auth.role === "device" && row.addedByDeviceId !== auth.deviceId) {
    return json({ error: "only the device that added it can remove it" }, 403);
  }

  ctx.sql.exec("DELETE FROM family_playlists WHERE id = ?", id);
  return new Response(null, { status: 204 });
}

function countPlaylists(ctx: HubContext): number {
  return ctx.sql
    .exec<{ n: number }>("SELECT COUNT(*) AS n FROM family_playlists")
    .one().n;
}
