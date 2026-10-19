import { describe, expect, it } from "vitest";
import { normalize, type TraceMessage } from "./traces/normalize.ts";

const msg = (client: string, type: string, payload: object): TraceMessage => ({ client, type, payload });

const payloadOf = (messages: object[], index: number) =>
  (messages[index] as { payload: Record<string, unknown> }).payload;

describe("trace normalization", () => {
  it("replaces a non-zero time field with $TIME but leaves a zero one as \"0\"", () => {
    const out = normalize([
      msg("A", "pong", {
        client_time: "1700000000000",
        server_receive_time: "1792412300123",
        server_send_time: "0",
        sequence: "7",
      }),
      msg("A", "sync_playback", { action: "seek", server_time: 0, captured_at_server_time: "5" }),
      msg("A", "sync_state", { last_update: "1792412300999" }),
    ]);

    expect(payloadOf(out, 0)).toEqual({
      client_time: "1700000000000", // an echo of what the client sent, not a server clock
      server_receive_time: "$TIME",
      server_send_time: "0",
      sequence: "7",
    });
    expect(payloadOf(out, 1)).toEqual({ action: "seek", server_time: 0, captured_at_server_time: "$TIME" });
    expect(payloadOf(out, 2)).toEqual({ last_update: "$TIME" });
  });

  it("numbers ids per kind by first appearance, across messages and clients", () => {
    const out = normalize([
      msg("A", "room_created", { room_code: "K3J9QW2D", user_id: "user_1_11", session_token: "aa" }),
      msg("A", "join_request", { user_id: "user_2_22", username: "Bharat" }),
      msg("B", "join_approved", {
        room_code: "K3J9QW2D",
        user_id: "user_2_22",
        session_token: "bb",
        state: { room_code: "K3J9QW2D", host_id: "user_1_11", users: [{ user_id: "user_1_11" }, { user_id: "user_2_22" }] },
      }),
      msg("A", "suggestion_received", { suggestion_id: "sug_9", from_user_id: "user_2_22" }),
      msg("B", "buffer_wait", { track_id: "t", waiting_for: ["user_2_22", "user_3_33"] }),
      msg("C", "room_created", { room_code: "ZZ", user_id: "user_3_33", session_token: "aa" }),
    ]);

    expect(payloadOf(out, 0)).toEqual({ room_code: "$ROOM1", user_id: "$USER1", session_token: "$TOKEN1" });
    expect(payloadOf(out, 1)).toEqual({ user_id: "$USER2", username: "Bharat" });
    expect(payloadOf(out, 2)).toEqual({
      room_code: "$ROOM1",
      user_id: "$USER2",
      session_token: "$TOKEN2",
      state: { room_code: "$ROOM1", host_id: "$USER1", users: [{ user_id: "$USER1" }, { user_id: "$USER2" }] },
    });
    expect(payloadOf(out, 3)).toEqual({ suggestion_id: "$SUGG1", from_user_id: "$USER2" });
    expect(payloadOf(out, 4)).toEqual({ track_id: "t", waiting_for: ["$USER2", "$USER3"] });
    // A value seen before keeps its number (token "aa"), a new one gets the next (room "ZZ").
    expect(payloadOf(out, 5)).toEqual({ room_code: "$ROOM2", user_id: "$USER3", session_token: "$TOKEN1" });
  });

  it("leaves an empty id alone", () => {
    const out = normalize([msg("A", "join_rejected", { user_id: "", reason: "no" })]);
    expect(payloadOf(out, 0)).toEqual({ user_id: "", reason: "no" });
  });

  it("masks position only in an object whose is_playing is true", () => {
    const out = normalize([
      msg("A", "sync_state", { is_playing: true, position: "61234", queue: [] }),
      msg("A", "sync_state", { is_playing: false, position: "61234", queue: [] }),
      msg("A", "sync_playback", { action: "seek", position: "60000" }),
      msg("B", "join_approved", {
        state: { is_playing: true, position: "1", current_track: { id: "t", duration: "5", position: "9" } },
      }),
    ]);

    expect(payloadOf(out, 0)).toEqual({ is_playing: true, position: "$POS", queue: [] });
    expect(payloadOf(out, 1)).toEqual({ is_playing: false, position: "61234", queue: [] });
    expect(payloadOf(out, 2)).toEqual({ action: "seek", position: "60000" });
    expect(payloadOf(out, 3)).toEqual({
      state: { is_playing: true, position: "$POS", current_track: { id: "t", duration: "5", position: "9" } },
    });
  });

  it("does not touch error codes or messages, even when they look like ids or times", () => {
    const error = { code: "room_not_found", message: "Room not found: K3J9QW2D at 1792412300123 for user_1_11" };
    const out = normalize([msg("B", "error", error)]);
    expect(out).toEqual([{ client: "B", type: "error", payload: error }]);
  });

  it("keeps client and type and the message order", () => {
    const out = normalize([msg("B", "pong", {}), msg("A", "error", { code: "x", message: "y" })]);
    expect(out.map((m) => (m as TraceMessage).client + ":" + (m as TraceMessage).type)).toEqual(["B:pong", "A:error"]);
  });
});
