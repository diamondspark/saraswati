// Recorded by scripts/record-traces.ts from metroserver 9da6f9d. Regenerate the
// .golden.json files, never edit them by hand.
import type { ScenarioName } from "./scenarios.ts";
import create_room from "./create_room.golden.json";
import join_approve from "./join_approve.golden.json";
import join_reject from "./join_reject.golden.json";
import join_unknown_room from "./join_unknown_room.golden.json";
import guest_playback_not_host from "./guest_playback_not_host.golden.json";
import host_play_pause_seek from "./host_play_pause_seek.golden.json";
import change_track_and_queue_add from "./change_track_and_queue_add.golden.json";
import queue_add_duplicate from "./queue_add_duplicate.golden.json";
import queue_remove_and_clear from "./queue_remove_and_clear.golden.json";
import suggest_approve from "./suggest_approve.golden.json";
import suggest_reject from "./suggest_reject.golden.json";
import buffer_ready_complete from "./buffer_ready_complete.golden.json";
import kick_user from "./kick_user.golden.json";
import transfer_host from "./transfer_host.golden.json";
import leave_room from "./leave_room.golden.json";
import reconnect_after_close from "./reconnect_after_close.golden.json";
import reconnect_unknown_token from "./reconnect_unknown_token.golden.json";
import ping_pong_and_request_sync from "./ping_pong_and_request_sync.golden.json";
import invalid_and_unknown_messages from "./invalid_and_unknown_messages.golden.json";

export const GOLDENS: Record<ScenarioName, object[]> = {
  create_room,
  join_approve,
  join_reject,
  join_unknown_room,
  guest_playback_not_host,
  host_play_pause_seek,
  change_track_and_queue_add,
  queue_add_duplicate,
  queue_remove_and_clear,
  suggest_approve,
  suggest_reject,
  buffer_ready_complete,
  kick_user,
  transfer_host,
  leave_room,
  reconnect_after_close,
  reconnect_unknown_token,
  ping_pong_and_request_sync,
  invalid_and_unknown_messages,
};
