import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { TrackInfoSchema } from "../src/gen/listentogether_pb";
import { sanitizeQueue, sanitizeString, sanitizeTrackInfo, sanitizeUpcomingQueue } from "../src/sangat/sanitize";

const track = (init: Partial<{ id: string; title: string; duration: bigint }> = {}) =>
  create(TrackInfoSchema, { id: "t1", title: "Raag Yaman", duration: 1000n, ...init });

describe("sanitizeString (metroserver validation.go)", () => {
  it("drops control characters except tab, newline and carriage return", () => {
    expect(sanitizeString("a\u0000b\u0001c\u000bd\u000ce\u001ff", 50)).toBe("abcdef");
    expect(sanitizeString("a\tb\nc\rd", 50)).toBe("a\tb\nc\rd");
  });

  it("trims what Go's TrimSpace trims, not what JavaScript's trim() does", () => {
    expect(sanitizeString(" 　 Asha  \u0085", 50)).toBe("Asha");
    expect(sanitizeString("﻿Asha", 50)).toBe("﻿Asha");
    expect(sanitizeString("   ", 50)).toBe("");
  });

  it("limits to a byte count without splitting a multi-byte character", () => {
    expect(sanitizeString("a".repeat(60), 50)).toBe("a".repeat(50));
    // Each Devanagari letter is 3 bytes: 17 of them are 51 bytes, so 16 fit in 50.
    expect(sanitizeString("स".repeat(17), 50)).toBe("स".repeat(16));
    // Emoji are 4 bytes.
    expect(sanitizeString("\u{1f3b5}".repeat(13), 50)).toBe("\u{1f3b5}".repeat(12));
  });
});

describe("sanitizeTrackInfo", () => {
  it("needs an id and a title", () => {
    expect(sanitizeTrackInfo(track({ id: " " }))).toBeNull();
    expect(sanitizeTrackInfo(track({ title: "" }))).toBeNull();
  });

  it("gives a zero or negative duration 180000 ms and caps a huge one at 24 hours", () => {
    expect(sanitizeTrackInfo(track({ duration: 0n }))?.duration).toBe(180000n);
    expect(sanitizeTrackInfo(track({ duration: -5n }))?.duration).toBe(180000n);
    expect(sanitizeTrackInfo(track({ duration: 99999999999n }))?.duration).toBe(86400000n);
    expect(sanitizeTrackInfo(track({ duration: 240001n }))?.duration).toBe(240001n);
  });

  it("trims every text field to its limit and returns a copy", () => {
    const original = track({ id: "x".repeat(300), title: `  ${"y".repeat(300)}  ` });
    original.thumbnail = "t".repeat(3000);
    original.suggestedBy = "s".repeat(80);

    const clean = sanitizeTrackInfo(original);

    expect(clean?.id).toHaveLength(200);
    expect(clean?.title).toHaveLength(200);
    expect(clean?.thumbnail).toHaveLength(2048);
    expect(clean?.suggestedBy).toHaveLength(50);
    expect(original.id).toHaveLength(300);
  });
});

describe("queue sanitizing", () => {
  it("keeps the first of each id, drops invalid tracks and stops at 1000", () => {
    const queue = [track({ id: "a" }), track({ id: "a", title: "dup" }), track({ id: "" }), track({ id: "b" })];
    expect(sanitizeQueue(queue).map((t) => t.id)).toEqual(["a", "b"]);

    const many = Array.from({ length: 1500 }, (_, i) => track({ id: `t${i}` }));
    expect(sanitizeQueue(many)).toHaveLength(1000);
  });

  it("leaves out the track that is playing", () => {
    const queue = [track({ id: "a" }), track({ id: "now" }), track({ id: "b" })];
    expect(sanitizeUpcomingQueue(queue, "now").map((t) => t.id)).toEqual(["a", "b"]);
    expect(sanitizeUpcomingQueue(queue, "").map((t) => t.id)).toEqual(["a", "now", "b"]);
  });
});
