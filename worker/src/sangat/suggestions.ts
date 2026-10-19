// suggest_track, approve_suggestion and reject_suggestion: a port of the matching
// handlers in metroserver's rooms.go. A guest proposes a track; it waits in
// storage until the host answers, even across the host's disconnects.

import { clone } from "@bufbuild/protobuf";
import {
  ApproveSuggestionPayloadSchema,
  PlaybackActionPayloadSchema,
  RejectSuggestionPayloadSchema,
  SuggestionApprovedPayloadSchema,
  SuggestionReceivedPayloadSchema,
  SuggestionRejectedPayloadSchema,
  SuggestTrackPayloadSchema,
  TrackInfoSchema,
} from "../gen/listentogether_pb";
import { newSuggestionId } from "./ids";
import { MAX_PENDING_SUGGESTIONS, MAX_QUEUE } from "./limits";
import {
  connectedIds,
  connectedMemberOf,
  currentRoom,
  decode,
  fail,
  hostIsConnected,
  isActiveHost,
  message,
  send,
  toAll,
} from "./roomOps";
import { sanitizeString, sanitizeTrackInfo } from "./sanitize";
import type { Handler } from "./types";

const REJECT_REASON_MAX = 200;

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

export const handleApproveSuggestion: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(ApproveSuggestionPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid approve suggestion payload")];
  if (p.suggestionId === "") return [fail(me, "missing_suggestion_id", "Suggestion ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) return [fail(me, "not_host", "Only the host can approve suggestions")];
  const suggestion = store.suggestion(room.code, p.suggestionId);
  if (!suggestion) return [fail(me, "suggestion_not_found", "Suggestion not found")];

  // The suggestion stays pending when the track cannot be queued.
  const { state } = room;
  if (state.queue.length >= MAX_QUEUE) return [fail(me, "queue_full", "Queue is full")];
  if (state.currentTrack?.id === suggestion.track.id || state.queue.some((t) => t.id === suggestion.track.id)) {
    return [fail(me, "duplicate_track", "Track is already playing or queued")];
  }

  const track = clone(TrackInfoSchema, suggestion.track);
  track.suggestedBy = suggestion.fromUsername;
  state.queue.unshift(track);
  state.revision += 1n;
  if (!store.trySaveRoom(room)) return [fail(me, "queue_full", "Queue is full")];
  store.deleteSuggestion(suggestion.id);

  const queueAdd = message(PlaybackActionPayloadSchema, {
    action: "queue_add",
    trackInfo: track,
    insertNext: true,
    queue: state.queue,
    serverTime: BigInt(rt.now()),
    revision: state.revision,
  });
  const outs = toAll(connectedIds(store, room.code), "sync_playback", queueAdd);
  // Only the suggester, if still in the room, is told.
  if (connectedMemberOf(store, room.code, suggestion.fromUserId)) {
    outs.push(
      send(suggestion.fromUserId, "suggestion_approved", SuggestionApprovedPayloadSchema, {
        suggestionId: suggestion.id,
        trackInfo: track,
      }),
    );
  }
  return outs;
};

export const handleRejectSuggestion: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(RejectSuggestionPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid reject suggestion payload")];
  if (p.suggestionId === "") return [fail(me, "missing_suggestion_id", "Suggestion ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) return [fail(me, "not_host", "Only the host can reject suggestions")];
  const suggestion = store.suggestion(room.code, p.suggestionId);
  if (!suggestion) return [fail(me, "suggestion_not_found", "Suggestion not found")];

  store.deleteSuggestion(suggestion.id);
  if (!connectedMemberOf(store, room.code, suggestion.fromUserId)) return [];
  return [
    send(suggestion.fromUserId, "suggestion_rejected", SuggestionRejectedPayloadSchema, {
      suggestionId: suggestion.id,
      reason: sanitizeString(p.reason, REJECT_REASON_MAX),
    }),
  ];
};
