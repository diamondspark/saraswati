// playback_action and request_sync: a port of the matching handlers in
// metroserver's playback.go. Positions and times are milliseconds, kept as
// bigint because the wire fields are int64.

import { clone, toBinary } from "@bufbuild/protobuf";
import {
  BufferCompletePayloadSchema,
  BufferReadyPayloadSchema,
  PlaybackActionPayloadSchema,
  SyncStatePayloadSchema,
  TrackInfoSchema,
  type PlaybackActionPayload,
  type RoomState,
  type TrackInfo,
} from "../gen/listentogether_pb";
import { MAX_QUEUE, MAX_QUEUE_INPUT, TRACK_TITLE_MAX } from "./limits";
import {
  connectedIds,
  currentRoom,
  decode,
  fail,
  isActiveHost,
  livePosition,
  send,
  toAll,
} from "./roomOps";
import { sanitizeString, sanitizeTrackInfo, sanitizeUpcomingQueue } from "./sanitize";
import type { Handler, Out } from "./types";

const MAX_ACTION_TRANSIT_COMPENSATION_MS = 5000n;

const clampPosition = (position: bigint, track: TrackInfo | undefined): bigint => {
  if (position < 0n) return 0n;
  if (track && track.duration > 0n && position > track.duration) return track.duration;
  return position;
};

// The host's clock reading was taken `elapsed` ago; move the position on to now.
const compensateTransit = (position: bigint, capturedAt: bigint, nowMs: bigint): bigint => {
  if (capturedAt <= 0n || capturedAt >= nowMs) return position;
  const elapsed = nowMs - capturedAt;
  return position + (elapsed > MAX_ACTION_TRANSIT_COMPENSATION_MS ? MAX_ACTION_TRANSIT_COMPENSATION_MS : elapsed);
};

// An action that names a track must name the one playing; naming none means it.
function staleTrack(me: string, state: RoomState, p: PlaybackActionPayload): Out | null {
  if (!state.currentTrack) return null;
  if (p.trackId === "") {
    p.trackId = state.currentTrack.id;
    return null;
  }
  if (p.trackId !== state.currentTrack.id) {
    return fail(me, "stale_track", "Playback action targets a stale track");
  }
  return null;
}

export const handlePlaybackAction: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const { room } = current;
  if (!isActiveHost(room, me)) {
    return [fail(me, "not_host", "Only the host can control playback")];
  }

  const p = decode(PlaybackActionPayloadSchema, payload);
  if (!p || p.queue.length > MAX_QUEUE_INPUT) {
    return [fail(me, "invalid_payload", "Invalid playback action payload")];
  }
  if (p.action === "") return [fail(me, "missing_action", "Action is required")];
  p.queueTitle = sanitizeString(p.queueTitle, TRACK_TITLE_MAX);

  const state = room.state;
  const nowMs = BigInt(rt.now());
  let problem: Out | null;

  switch (p.action) {
    case "play":
      if (!state.currentTrack) return [fail(me, "no_track", "Cannot play without a track")];
      if (p.position < 0n) return [fail(me, "invalid_position", "Position cannot be negative")];
      if ((problem = staleTrack(me, state, p))) return [problem];
      p.position = clampPosition(compensateTransit(p.position, p.capturedAtServerTime, nowMs), state.currentTrack);
      state.isPlaying = true;
      state.position = p.position;
      state.lastUpdate = nowMs;
      break;

    case "pause":
      if (p.position < 0n) return [fail(me, "invalid_position", "Position cannot be negative")];
      if ((problem = staleTrack(me, state, p))) return [problem];
      p.position = clampPosition(p.position, state.currentTrack);
      state.isPlaying = false;
      state.position = p.position;
      state.lastUpdate = nowMs;
      break;

    case "seek":
      if (p.position < 0n) return [fail(me, "invalid_position", "Position cannot be negative")];
      if ((problem = staleTrack(me, state, p))) return [problem];
      if (state.isPlaying) p.position = compensateTransit(p.position, p.capturedAtServerTime, nowMs);
      p.position = clampPosition(p.position, state.currentTrack);
      state.position = p.position;
      state.lastUpdate = nowMs;
      break;

    case "change_track": {
      if (!p.trackInfo) return [fail(me, "missing_track_info", "Track info is required for track change")];
      const track = sanitizeTrackInfo(p.trackInfo);
      if (!track) return [fail(me, "invalid_track_info", "Track must have ID and title")];
      p.trackInfo = track;
      // An empty queue in the message means "keep the queue" (an empty repeated
      // field is absent on the wire).
      if (p.queue.length > 0) {
        p.queue = sanitizeUpcomingQueue(p.queue, track.id);
        state.queue = [...p.queue];
      }
      state.currentTrack = clone(TrackInfoSchema, track);
      state.queue = sanitizeUpcomingQueue(state.queue, track.id);
      state.position = 0n;
      state.isPlaying = false;
      state.lastUpdate = nowMs;
      p.trackId = track.id;
      p.position = 0n;
      break;
    }

    case "skip_next":
    case "skip_prev":
      // The host's player produces the change_track that follows; nothing to relay.
      return [];

    case "queue_add": {
      if (!p.trackInfo) return [fail(me, "missing_track_info", "Track info is required for queue add")];
      const track = sanitizeTrackInfo(p.trackInfo);
      if (!track) return [fail(me, "invalid_track_info", "Track must have ID and title")];
      p.trackInfo = track;
      if (state.queue.length >= MAX_QUEUE) return [fail(me, "queue_full", "Queue is full")];
      if (state.currentTrack?.id === track.id || state.queue.some((t) => t.id === track.id)) {
        return [fail(me, "duplicate_track", "Track is already playing or queued")];
      }
      if (p.insertNext) state.queue.unshift(track);
      else state.queue.push(track);
      break;
    }

    case "queue_remove":
      if (p.trackId === "") return [fail(me, "missing_track_id", "Track ID is required for queue remove")];
      state.queue = state.queue.filter((t) => t.id !== p.trackId);
      break;

    case "queue_clear":
      state.queue = [];
      break;

    case "sync_queue":
      p.queue = sanitizeUpcomingQueue(p.queue, state.currentTrack?.id ?? "");
      state.queue = [...p.queue];
      break;

    case "set_volume":
      if (!Number.isFinite(p.volume) || p.volume < 0 || p.volume > 1) {
        return [fail(me, "invalid_volume", "Volume must be between 0 and 1")];
      }
      state.volume = p.volume;
      break;

    default:
      return [fail(me, "unknown_action", `Unknown action: ${p.action}`)];
  }

  if (
    p.action === "change_track" ||
    p.action === "queue_add" ||
    p.action === "queue_remove" ||
    p.action === "queue_clear" ||
    p.action === "sync_queue"
  ) {
    p.queue = state.queue.map((t) => clone(TrackInfoSchema, t));
  }

  state.revision += 1n;
  p.revision = state.revision;
  p.serverTime = nowMs;
  // Nothing is stored for a queue that would not fit in the room's row. Actions that can
  // add to the queue stop short of the cap; the others (pause, volume) may use all of it.
  const grows = p.action === "change_track" || p.action === "queue_add" || p.action === "sync_queue";
  if (!(grows ? store.trySaveRoomGrowth(room) : store.trySaveRoom(room))) {
    return [fail(me, "queue_full", "Queue is full")];
  }
  return toAll(connectedIds(store, room.code), "sync_playback", toBinary(PlaybackActionPayloadSchema, p));
};

// buffer_ready: the sender has buffered the current track and is told where to
// start: a seek to the live position, the room's play or pause, then
// buffer_complete, to the sender alone. metroserver has a path that holds
// everyone until all members are ready (buffer_wait), but it never fills the set
// of members to wait for, so it is never taken and is not ported.
export const handleBufferReady: Handler = (rt, conn, payload) => {
  const { store } = rt;
  const me = conn.att.userId;
  const p = decode(BufferReadyPayloadSchema, payload);
  if (!p) return [fail(me, "invalid_payload", "Invalid buffer ready payload")];
  if (p.trackId === "") return [fail(me, "missing_track_id", "Track ID is required")];
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];

  const { state } = current.room;
  if (!state.currentTrack || p.trackId !== state.currentTrack.id) {
    return [fail(me, "stale_track", "Buffer readiness targets a stale track")];
  }

  const nowMs = BigInt(rt.now());
  const at = {
    trackId: state.currentTrack.id,
    position: livePosition(state, nowMs),
    serverTime: nowMs,
    revision: state.revision,
  };
  return [
    send(me, "sync_playback", PlaybackActionPayloadSchema, { action: "seek", ...at }),
    send(me, "sync_playback", PlaybackActionPayloadSchema, { action: state.isPlaying ? "play" : "pause", ...at }),
    send(me, "buffer_complete", BufferCompletePayloadSchema, { trackId: at.trackId }),
  ];
};

export const handleRequestSync: Handler = (rt, conn) => {
  const { store } = rt;
  const me = conn.att.userId;
  const current = currentRoom(store, me);
  if (!current) return [fail(me, "not_in_room", "You are not in a room")];
  const now = rt.now();
  // Asking again within a second is ignored, without an answer.
  if (!rt.allowSyncResponse(me, now)) return [];

  const { state } = current.room;
  return [
    send(me, "sync_state", SyncStatePayloadSchema, {
      currentTrack: state.currentTrack,
      isPlaying: state.isPlaying,
      position: livePosition(state, BigInt(now)),
      lastUpdate: BigInt(now),
      queue: state.queue,
      volume: state.volume,
      revision: state.revision,
    }),
  ];
};
