// The 19 trace scenarios. Each is an ordered list of steps over clients A, B and
// C. The same list drives the Node recorder (against metroserver, to write the
// goldens) and the Worker replay (against FamilyHub), so it is data only.
//
// Payloads are proto3 JSON with snake_case field names. A string that is
// exactly one of these placeholders is replaced when the step is sent, from
// ids the clients have already seen on the wire:
//   "<room>"          room code of the first room created        "<room|lower>"  the same, lower-cased
//   "<user:B>"        user id of client B                         "<token:A>"     session token of client A
//   "<suggestion>"    id of the latest suggestion the host saw
// A step with only `waitMs` pauses for that long (the rate-limit scenarios need
// real time to pass).

export type TraceStep = {
  client: string;
  send?: { type: string; payload: object };
  close?: true;
  reconnect?: true;
  waitMs?: number;
};

export const SCENARIO_NAMES = [
  "create_room",
  "join_approve",
  "join_reject",
  "join_unknown_room",
  "guest_playback_not_host",
  "host_play_pause_seek",
  "change_track_and_queue_add",
  "queue_add_duplicate",
  "queue_remove_and_clear",
  "suggest_approve",
  "suggest_reject",
  "buffer_ready_complete",
  "kick_user",
  "transfer_host",
  "leave_room",
  "reconnect_after_close",
  "reconnect_unknown_token",
  "ping_pong_and_request_sync",
  "invalid_and_unknown_messages",
] as const;

export type ScenarioName = (typeof SCENARIO_NAMES)[number];

const NAME = { A: "Asha", B: "Bharat", C: "Charu" } as const;

const send = (client: string, type: string, payload: object = {}): TraceStep => ({
  client,
  send: { type, payload },
});
const close = (client: string): TraceStep => ({ client, close: true });
const reopen = (client: string): TraceStep => ({ client, reconnect: true });
const wait = (client: string, waitMs: number): TraceStep => ({ client, waitMs });
const rawPayload = (client: string, type: string, hex: string): TraceStep =>
  send(client, type, { $rawHex: hex });
const rawFrame = (client: string, hex: string): TraceStep =>
  send(client, "$frame", { $rawHex: hex });

const track = (n: number, extra: object = {}) => ({
  id: `trk-${String(n).padStart(3, "0")}`,
  title: `Raag Number ${n}`,
  artist: "Pt. Ravi Shankar",
  album: "Raga Collection",
  duration: 240000 + n,
  thumbnail: `https://i.ytimg.com/vi/trk-${n}/hqdefault.jpg`,
  ...extra,
});

const create = (client: "A" | "B" | "C" = "A"): TraceStep =>
  send(client, "create_room", { username: NAME[client] });
const join = (client: "A" | "B" | "C"): TraceStep =>
  send(client, "join_room", { room_code: "<room>", username: NAME[client] });
const approve = (guest: "A" | "B" | "C", host = "A"): TraceStep =>
  send(host, "approve_join", { user_id: `<user:${guest}>` });
// Join request, then the host's approval.
const enter = (guest: "B" | "C", host = "A"): TraceStep[] => [join(guest), approve(guest, host)];
const play = (client: string, position = 0, extra: object = {}): TraceStep =>
  send(client, "playback_action", { action: "play", position, ...extra });
const change = (client: string, trackInfo: object, extra: object = {}): TraceStep =>
  send(client, "playback_action", { action: "change_track", track_info: trackInfo, ...extra });
const queueAdd = (client: string, trackInfo: object, extra: object = {}): TraceStep =>
  send(client, "playback_action", { action: "queue_add", track_info: trackInfo, ...extra });

const ROOM_ABSENT = "OOOOOOOO"; // metroserver's alphabet has no letter O, so this never exists

const longText = (n: number) => "L".repeat(n);

export const SCENARIOS: Record<ScenarioName, TraceStep[]> = {
  create_room: [
    send("A", "client_capabilities", {
      supports_protobuf: true,
      supports_compression: true,
      client_version: "saraswati-trace/1",
    }),
    create("A"),
    create("A"),
    send("B", "create_room", { username: "" }),
    send("B", "create_room", { username: "   " }),
    create("B"),
  ],

  join_approve: [
    create("A"),
    send("B", "join_room", { room_code: "<room|lower>", username: NAME.B }),
    join("B"),
    approve("B"),
    join("B"),
    join("C"),
    approve("C"),
    approve("C"),
    send("A", "approve_join", { user_id: "" }),
  ],

  join_reject: [
    create("A"),
    join("B"),
    send("A", "reject_join", { user_id: "<user:B>", reason: "Not tonight" }),
    join("C"),
    send("A", "reject_join", { user_id: "<user:C>" }),
    send("A", "reject_join", { user_id: "<user:C>" }),
    send("A", "reject_join", { user_id: "" }),
    join("B"),
    approve("B"),
  ],

  join_unknown_room: [
    send("B", "join_room", { room_code: ROOM_ABSENT, username: NAME.B }),
    send("B", "join_room", { room_code: "", username: NAME.B }),
    send("B", "join_room", { room_code: ROOM_ABSENT, username: "" }),
    send("B", "join_room", { room_code: ROOM_ABSENT, username: "\u0001" }),
    send("B", "join_room", { room_code: "   ", username: NAME.B }),
    send("B", "join_room", { room_code: ROOM_ABSENT.repeat(2), username: NAME.B }),
  ],

  guest_playback_not_host: [
    create("A"),
    ...enter("B"),
    play("B"),
    send("B", "playback_action", { action: "change_track", track_info: track(1) }),
    send("B", "kick_user", { user_id: "<user:A>" }),
    send("B", "transfer_host", { new_host_id: "<user:B>" }),
    send("B", "approve_join", { user_id: "user_unknown" }),
    send("B", "reject_join", { user_id: "user_unknown" }),
    send("B", "approve_suggestion", { suggestion_id: "sug_unknown" }),
    send("B", "reject_suggestion", { suggestion_id: "sug_unknown" }),
    play("C"),
    send("C", "kick_user", { user_id: "user_unknown" }),
    send("C", "transfer_host", { new_host_id: "user_unknown" }),
    send("C", "suggest_track", { track_info: track(2) }),
    send("C", "buffer_ready", { track_id: "trk-001" }),
    send("C", "request_sync"),
  ],

  host_play_pause_seek: [
    create("A"),
    play("A"),
    change("A", track(1)),
    play("A", 5000, { track_id: "trk-001" }),
    send("A", "playback_action", { action: "seek", position: 60000 }),
    send("A", "playback_action", { action: "pause", position: 61000 }),
    send("A", "playback_action", { action: "seek", position: 999999999 }),
    send("A", "playback_action", { action: "seek", position: -5 }),
    play("A", 0, { track_id: "trk-unknown" }),
    send("A", "playback_action", { action: "set_volume", volume: 0.5 }),
    send("A", "playback_action", { action: "set_volume", volume: 1.5 }),
    send("A", "playback_action", { action: "dance" }),
    send("A", "playback_action", { action: "" }),
    send("A", "playback_action", { action: "skip_next" }),
    play("A", 1000),
    ...enter("B"),
    send("B", "request_sync"),
    send("A", "playback_action", { action: "pause", position: 2000 }),
  ],

  change_track_and_queue_add: [
    create("A"),
    ...enter("B"),
    change(
      "A",
      track(1, { title: "  Raag Number 1  ", duration: 0 }),
      {
        queue: [
          track(2, { duration: 99999999999 }),
          track(3, { title: "सरस्वती वंदना", artist: "लता मंगेशकर" }),
          track(1),
          track(2),
        ],
      },
    ),
    queueAdd("A", track(4)),
    queueAdd("A", track(5), { insert_next: true }),
    send("A", "playback_action", { action: "change_track" }),
    change("A", { id: "", title: "no id" }),
    send("B", "request_sync"),
    change("A", track(2)),
    change("A", {
      id: "trk-long",
      title: longText(250),
      artist: longText(250),
      album: longText(250),
      thumbnail: `https://example.test/${longText(2100)}`,
      duration: 5000,
    }),
  ],

  queue_add_duplicate: [
    create("A"),
    queueAdd("A", track(2)),
    change("A", track(1)),
    queueAdd("A", track(3)),
    queueAdd("A", track(3)),
    queueAdd("A", track(1)),
    send("A", "playback_action", { action: "queue_add" }),
    queueAdd("A", { id: "trk-blank", title: "   " }),
    queueAdd("A", track(4), { insert_next: true }),
  ],

  queue_remove_and_clear: [
    create("A"),
    ...enter("B"),
    change("A", track(1), { queue: [track(2), track(3), track(4)] }),
    send("A", "playback_action", { action: "queue_remove", track_id: "trk-003" }),
    send("A", "playback_action", { action: "queue_remove" }),
    send("A", "playback_action", { action: "queue_remove", track_id: "trk-nothing" }),
    send("A", "playback_action", { action: "sync_queue", queue: [track(5), track(1), track(6)] }),
    send("A", "playback_action", { action: "queue_clear" }),
    send("B", "request_sync"),
  ],

  suggest_approve: [
    create("A"),
    ...enter("B"),
    send("C", "suggest_track", { track_info: track(9) }),
    send("A", "suggest_track", { track_info: track(9) }),
    send("B", "suggest_track"),
    send("B", "suggest_track", { track_info: { id: "", title: "x" } }),
    send("B", "suggest_track", { track_info: track(9, { duration: 0, title: " Pyaar Hua " }) }),
    send("A", "approve_suggestion", { suggestion_id: "<suggestion>" }),
    send("A", "approve_suggestion", { suggestion_id: "<suggestion>" }),
    send("A", "approve_suggestion", { suggestion_id: "" }),
    send("B", "suggest_track", { track_info: track(9) }),
    send("A", "approve_suggestion", { suggestion_id: "<suggestion>" }),
  ],

  suggest_reject: [
    create("A"),
    ...enter("B"),
    send("B", "suggest_track", { track_info: track(8) }),
    send("A", "reject_suggestion", { suggestion_id: "<suggestion>", reason: "Maybe later" }),
    send("A", "reject_suggestion", { suggestion_id: "<suggestion>" }),
    send("B", "suggest_track", { track_info: track(7) }),
    send("A", "reject_suggestion", { suggestion_id: "<suggestion>" }),
    send("A", "reject_suggestion", { suggestion_id: "" }),
  ],

  buffer_ready_complete: [
    create("A"),
    ...enter("B"),
    send("B", "buffer_ready", { track_id: "trk-001" }),
    send("B", "buffer_ready", {}),
    change("A", track(1)),
    send("B", "buffer_ready", { track_id: "trk-001" }),
    send("B", "buffer_ready", { track_id: "trk-other" }),
    send("C", "buffer_ready", { track_id: "trk-001" }),
    send("A", "buffer_ready", { track_id: "trk-001" }),
  ],

  kick_user: [
    create("A"),
    ...enter("B"),
    ...enter("C"),
    send("A", "kick_user", { user_id: "<user:B>", reason: "Too loud" }),
    send("A", "kick_user", { user_id: "<user:A>" }),
    send("A", "kick_user", { user_id: "<user:B>" }),
    send("A", "kick_user", { user_id: "" }),
    send("A", "kick_user", { user_id: "<user:C>" }),
    play("B"),
    join("B"),
    approve("B"),
  ],

  transfer_host: [
    create("A"),
    ...enter("B"),
    ...enter("C"),
    send("A", "transfer_host", { new_host_id: "<user:B>" }),
    play("A"),
    change("B", track(1)),
    send("B", "transfer_host", { new_host_id: "<user:B>" }),
    send("B", "transfer_host", { new_host_id: "user_unknown" }),
    send("B", "transfer_host", { new_host_id: "" }),
    send("A", "leave_room"),
    join("A"),
    send("B", "approve_join", { user_id: "<user:A>" }),
  ],

  leave_room: [
    create("A"),
    ...enter("B"),
    join("C"),
    send("C", "leave_room"),
    approve("C"),
    ...enter("C"),
    send("B", "leave_room"),
    send("B", "leave_room"),
    play("B"),
    send("A", "leave_room"),
    change("C", track(1)),
    send("C", "leave_room"),
    send("B", "join_room", { room_code: "<room>", username: NAME.B }),
  ],

  reconnect_after_close: [
    create("A"),
    ...enter("B"),
    change("A", track(1), { queue: [track(2)] }),
    send("B", "suggest_track", { track_info: track(3) }),
    close("A"),
    join("C"),
    send("B", "request_sync"),
    reopen("A"),
    send("A", "reconnect", { session_token: "<token:A>" }),
    approve("C"),
    close("B"),
    play("A", 500),
    reopen("B"),
    send("B", "reconnect", { session_token: "<token:B>" }),
    close("C"),
    reopen("C"),
    send("C", "reconnect", { session_token: "<token:C>" }),
  ],

  reconnect_unknown_token: [
    send("A", "reconnect", { session_token: "0123456789abcdef0123456789abcdef" }),
    send("A", "reconnect", { session_token: "" }),
    create("A"),
    send("A", "reconnect", { session_token: "<token:A>" }),
    join("B"),
    send("B", "reconnect", { session_token: "abc" }),
    send("A", "leave_room"),
    send("A", "reconnect", { session_token: "<token:A>" }),
  ],

  ping_pong_and_request_sync: [
    send("A", "ping", { client_time: 1700000000000, sequence: 1 }),
    send("A", "ping", {}),
    send("A", "ping", { client_time: 42, sequence: 99 }),
    send("A", "request_sync"),
    create("A"),
    send("A", "request_sync"),
    // Deliberately back to back: request_sync is answered at most once per second
    // per connection, so these two steps must stay under 1 s apart (the recorder
    // settles 500 ms and the replay 250 ms between steps) for the second to be
    // dropped. Any later repeat by the same client needs an explicit `wait`.
    send("A", "request_sync"),
    ...enter("B"),
    send("B", "request_sync"),
    wait("B", 1100),
    change("A", track(1), { queue: [track(2)] }),
    send("B", "request_sync"),
    wait("B", 1100),
    send("B", "request_sync"),
  ],

  invalid_and_unknown_messages: [
    send("A", "frobnicate"),
    send("A", "room_created", { room_code: "ABCD1234" }),
    rawFrame("A", "ffffff"),
    rawFrame("A", ""),
    rawFrame("A", "120100"),
    rawFrame("A", `0a41${"61".repeat(65)}`),
    rawFrame("A", "0a0470696e6712020102" + "1801"),
    rawPayload("A", "create_room", "ff"),
    rawPayload("A", "join_room", "ff"),
    rawPayload("A", "ping", "ff"),
    send("B", "client_capabilities", { supports_protobuf: false }),
    send("B", "client_capabilities", {
      supports_protobuf: true,
      supports_compression: false,
      client_version: "saraswati-trace/1",
    }),
    send("B", "client_capabilities", { supports_protobuf: true }),
    create("C"),
    send("C", "client_capabilities", { supports_protobuf: true }),
    rawPayload("C", "playback_action", "ff"),
    rawPayload("C", "approve_suggestion", "ff"),
    send("C", "leave_room", {}),
  ],
};
