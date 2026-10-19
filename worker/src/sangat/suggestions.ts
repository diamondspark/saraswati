// suggest_track: a port of handleSuggestTrack in metroserver's rooms.go. A guest
// proposes a track; it waits in storage until the host answers, even across the
// host's disconnects.

import { SuggestionReceivedPayloadSchema, SuggestTrackPayloadSchema } from "../gen/listentogether_pb";
import { newSuggestionId } from "./ids";
import { MAX_PENDING_SUGGESTIONS } from "./limits";
import { currentRoom, decode, fail, hostIsConnected, send } from "./roomOps";
import { sanitizeTrackInfo } from "./sanitize";
import type { Handler } from "./types";

export const handleSuggestTrack: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(SuggestTrackPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid suggest track payload")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  if (!p.trackInfo) return [fail(me, "missing_track_info", "Track info is required")];
  const track = sanitizeTrackInfo(p.trackInfo);
  if (!track) return [fail(me, "invalid_track_info", "Track must have ID and title")];

  const { room, member } = current;
  // The host picks their own tracks; a suggestion from them is ignored without an error.
  if (room.hostUserId === me) return [];
  if (store.suggestionCount(room.code) >= MAX_PENDING_SUGGESTIONS) {
    return [fail(me, "suggestions_full", "Too many pending suggestions")];
  }

  const now = rt.now();
  let id = newSuggestionId(now);
  while (store.suggestionExists(id)) id = newSuggestionId(now);
  store.insertSuggestion({
    id,
    roomCode: room.code,
    fromUserId: me,
    fromUsername: member.username,
    track,
    createdAt: now,
  });

  // With the host away it is replayed when they reconnect.
  if (!hostIsConnected(room)) return [];
  return [
    send(room.hostUserId, "suggestion_received", SuggestionReceivedPayloadSchema, {
      suggestionId: id,
      fromUserId: me,
      fromUsername: member.username,
      trackInfo: track,
    }),
  ];
};
