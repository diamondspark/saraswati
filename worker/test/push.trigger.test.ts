// When the hub pushes: a Sangat starting (create_room), a host's phone going into car mode,
// and a host ending the room on purpose. Always to the OTHER approved phones that registered a
// token, never on a dropped or restored connection, and only with FCM configured, Sangat on
// and the send limits not spent. FCM itself is a stand-in fetch.

import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeAccount, fakeGoogle, UNREGISTERED_BODY } from "./push.helpers";
import { call, setSwitch } from "./registry.helpers";
import {
  closeAllClients,
  createRoom,
  hubStub,
  joinAndApprove,
  resetHub,
  seedDevice,
  sleep,
  sql,
  SangatClient,
  type Device,
} from "./sangat.helpers";

const hub = () => hubStub();
let google: ReturnType<typeof fakeGoogle>;
let fetchSpy: ReturnType<typeof vi.spyOn>;
let secret: string;

const setSecret = (value: string | undefined) =>
  runInDurableObject(hub(), (instance) => {
    Object.assign((instance as unknown as { env: Record<string, unknown> }).env, {
      FCM_SERVICE_ACCOUNT: value,
    });
  });

function useGoogle(options?: Parameters<typeof fakeGoogle>[0]) {
  google = fakeGoogle(options);
  fetchSpy.mockImplementation(google.fn);
}

beforeEach(async () => {
  await resetHub();
  await sql(hub(), (db) => {
    db.exec("DELETE FROM push_tokens");
    db.exec("DELETE FROM rate_limits WHERE key LIKE 'push-send:%'");
  });
  secret = (await fakeAccount()).json;
  await setSecret(secret);
  fetchSpy = vi.spyOn(globalThis, "fetch");
  useGoogle();
});

afterEach(async () => {
  closeAllClients();
  fetchSpy.mockRestore();
  await setSecret(undefined);
});

/** Waits for the pushes the hub has started. */
const settle = () => runInDurableObject(hub(), (instance) => (instance as any).settlePushes() as Promise<void>);

/** Calls the hub's trigger directly, as a hook does. */
const trigger = (excludeDeviceId: string, reason: "started" | "ended" = "started") =>
  runInDurableObject(hub(), (instance) => {
    (instance as any).notifySangatChanged(excludeDeviceId, reason);
  });

const rateCount = (key: string) =>
  sql(hub(), (db) => db.exec<{ count: number }>("SELECT count FROM rate_limits WHERE key = ?", key).toArray()[0]?.count);

const registerToken = (device: Device, token = `fcm-${device.deviceId}`) =>
  sql(hub(), (db) => {
    db.exec("INSERT OR REPLACE INTO push_tokens (deviceId, token, updatedAt) VALUES (?, ?, 1)", device.deviceId, token);
    return token;
  });

const storedTokens = () =>
  sql(hub(), (db) =>
    db
      .exec<{ token: string }>("SELECT token FROM push_tokens ORDER BY token")
      .toArray()
      .map((row) => row.token),
  );

/** Starts with the request log empty and the send budgets full, as after a quiet spell. */
async function forgetPushes() {
  await settle();
  google.calls.length = 0;
  await sql(hub(), (db) => db.exec("DELETE FROM rate_limits WHERE key LIKE 'push-send:%'"));
}

/** host (token, creates rooms) + other phones in every state a roster can have. */
async function family() {
  const host = await seedDevice(hub(), "host");
  const b = await seedDevice(hub(), "b");
  const c = await seedDevice(hub(), "c");
  const pending = await seedDevice(hub(), "pending", "pending");
  const revoked = await seedDevice(hub(), "revoked", "revoked");
  const bare = await seedDevice(hub(), "bare");
  const tokens = {
    host: await registerToken(host),
    b: await registerToken(b),
    c: await registerToken(c),
    pending: await registerToken(pending),
    revoked: await registerToken(revoked),
  };
  return { host, b, c, pending, revoked, bare, tokens };
}

describe("a Sangat starting", () => {
  it("create_room pushes to the other approved devices that have a token, and only to those", async () => {
    const f = await family();

    const room = await createRoom(hub(), f.host);
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
    expect(google.tokenRequests()).toHaveLength(1);
    expect(room.code).toBeTruthy();
  });

  it("sends the same content-free message every time", async () => {
    const f = await family();

    await createRoom(hub(), f.host);
    await settle();

    for (const send of google.sends()) {
      const message = JSON.parse(String(send.init.body)).message;
      expect(Object.keys(message).sort()).toEqual(["android", "data", "token"]);
      expect(message.data).toEqual({ type: "sangat_changed" });
    }
  });

  it("a create_room that fails sends nothing", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    // Already in a room: refused, nothing committed.
    await room.client.send("create_room", { username: "Again" });
    await room.client.next("error");
    await settle();

    expect(google.sends()).toHaveLength(0);
  });

  it("does not wait for FCM before answering the host", async () => {
    const f = await family();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const inner = fakeGoogle();
    fetchSpy.mockImplementation((async (input: RequestInfo | URL, init?: RequestInit) => {
      await gate;
      return inner.fn(input, init);
    }) as typeof fetch);

    const room = await createRoom(hub(), f.host);

    expect(room.code).toBeTruthy();
    expect(inner.calls).toHaveLength(0);
    release();
    await settle();
    expect(inner.sentTo()).toHaveLength(2);
  });

  it("a thrown error in the send never reaches the room", async () => {
    const f = await family();
    fetchSpy.mockImplementation((async () => {
      throw new Error("network down");
    }) as typeof fetch);

    const room = await createRoom(hub(), f.host);
    await settle();

    expect(room.code).toBeTruthy();
    expect(await storedTokens()).toHaveLength(5);
  });
});

describe("a Sangat in the car", () => {
  async function roomInCar() {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();
    return { f, room };
  }
  const markCar = (device: Device, code: string, carMode: boolean) =>
    call(hub(), "PUT", "/sangat/car", { token: device.token, body: { roomCode: code, carMode } });

  it("marking the room as the car pushes to the others, not the car phone", async () => {
    const { f, room } = await roomInCar();

    const reply = await markCar(f.host, room.code, true);
    await settle();

    expect(reply.status).toBe(200);
    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("repeating carMode true on a room that is already a car room does not push again", async () => {
    const { f, room } = await roomInCar();
    await markCar(f.host, room.code, true);
    await forgetPushes();

    const again = await markCar(f.host, room.code, true);
    await settle();

    expect(again.status).toBe(200);
    expect(google.calls).toHaveLength(0);
    // Switching off and on again is a new change.
    await markCar(f.host, room.code, false);
    await markCar(f.host, room.code, true);
    await settle();
    expect(google.sends()).toHaveLength(2);
  });

  it("carMode false does not push", async () => {
    const { f, room } = await roomInCar();
    await markCar(f.host, room.code, true);
    await forgetPushes();

    const reply = await markCar(f.host, room.code, false);
    await settle();

    expect(reply.status).toBe(200);
    expect(google.calls).toHaveLength(0);
  });

  it("a refused request does not push: not the host, no room, sangat off", async () => {
    const { f, room } = await roomInCar();

    expect((await markCar(f.b, room.code, true)).status).toBe(403);
    expect((await markCar(f.host, "NOPE", true)).status).toBe(404);
    await setSwitch(hub(), "sangat", false);
    await forgetPushes(); // closing the room with the switch pushed "ended"; the refusal below must add nothing
    expect((await markCar(f.host, room.code, true)).status).toBe(503);
    await settle();

    expect(google.calls).toHaveLength(0);
  });
});

describe("a host ending the room", () => {
  it("leaving a room that is then empty pushes to the others, not the host", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    await room.client.send("leave_room");
    await sleep(100);
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("leaving a car room, which ends it for everyone, pushes", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await call(hub(), "PUT", "/sangat/car", { token: f.host.token, body: { roomCode: room.code, carMode: true } });
    await forgetPushes();

    await room.client.send("leave_room");
    expect((await guest.client.next("kicked")).payload.reason).toBe("Sangat in the car has ended");
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("a host leaving a room that carries on (another member takes over) does not push", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await forgetPushes();

    await room.client.send("leave_room");
    await guest.client.next("user_left");
    expect((await guest.client.next("host_changed")).payload.new_host_id).toBe(guest.userId);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a guest leaving does not push", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await forgetPushes();

    await guest.client.send("leave_room");
    await room.client.next("user_left");
    await settle();

    expect(google.calls).toHaveLength(0);
  });
});

describe("a connection dropping or coming back", () => {
  it("does not push: not when the host's socket drops, nor when the host reconnects", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    room.client.close();
    await sleep(150);
    const back = await SangatClient.connect(hub(), f.host.token);
    await back.send("reconnect", { session_token: room.token });
    await back.next("reconnected");
    await sleep(100);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a car room whose host loses signal is not pushed about either", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await call(hub(), "PUT", "/sangat/car", { token: f.host.token, body: { roomCode: room.code, carMode: true } });
    await forgetPushes();

    room.client.close();
    await guest.client.next("user_disconnected");
    await settle();

    expect(google.calls).toHaveLength(0);
  });
});

describe("revoking a phone", () => {
  const adminRevoke = (device: Device) =>
    call(hub(), "POST", `/roster/${device.deviceId}/revoke`, {
      token: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    });

  it("a revoked car host ends the room, so the others are pushed to (not the revoked phone)", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await call(hub(), "PUT", "/sangat/car", { token: f.host.token, body: { roomCode: room.code, carMode: true } });
    await forgetPushes();

    expect((await adminRevoke(f.host)).status).toBe(200);
    expect((await guest.client.next("kicked")).payload.reason).toBe("Sangat in the car has ended");
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("a revoked host whose room is then empty pushes too", async () => {
    const f = await family();
    await createRoom(hub(), f.host);
    await forgetPushes();

    await adminRevoke(f.host);
    await sleep(100);
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("a revoked host whose room carries on under a new host does not push", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await forgetPushes();

    await adminRevoke(f.host);
    await guest.client.next("user_left");
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a revoked guest, or a phone in no room, does not push", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await forgetPushes();

    await adminRevoke(f.b);
    await room.client.next("user_left");
    await adminRevoke(f.c);
    await sleep(100);
    await settle();

    expect(google.calls).toHaveLength(0);
    expect(guest.userId).toBeTruthy();
  });
});

describe("a room ending without the host leaving", () => {
  const GRACE_PLUS = 15 * 60_000 + 60_000;
  /** Makes the user's reconnect grace run out, as the lifecycle tests do. */
  const expireGrace = (userId: string) =>
    sql(hub(), (db) =>
      db.exec("UPDATE sangat_members SET disconnectedAt = ? WHERE userId = ?", Date.now() - GRACE_PLUS, userId),
    );

  it("a host who stays gone past the grace ends the room: one push to the others, none for the drop itself", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    room.client.close();
    await sleep(150);
    await runDurableObjectAlarm(hub());
    await settle();
    expect(google.calls).toHaveLength(0);

    await expireGrace(room.userId);
    await runDurableObjectAlarm(hub());
    await settle();
    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
    expect(google.sends()).toHaveLength(2);

    // The emptied room's later cleanup, and any further alarm, say nothing more.
    google.calls.length = 0;
    await sql(hub(), (db) => db.exec("UPDATE sangat_rooms SET emptySince = ? WHERE code = ?", 1, room.code));
    await runDurableObjectAlarm(hub());
    await settle();
    expect(google.calls).toHaveLength(0);
  });

  it("a host who reconnects within the grace pushes nothing, then or when the alarm next runs", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    room.client.close();
    await sleep(150);
    const back = await SangatClient.connect(hub(), f.host.token);
    await back.send("reconnect", { session_token: room.token });
    await back.next("reconnected");
    await runDurableObjectAlarm(hub());
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a host whose grace runs out while another member can take over ends nothing", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await forgetPushes();

    room.client.close();
    await sleep(150);
    await expireGrace(room.userId);
    await runDurableObjectAlarm(hub());
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a car room whose host stays gone ends with it", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    const guest = await joinAndApprove(hub(), room.client, room.code, f.b, "Bharat");
    await room.client.next("user_joined");
    await call(hub(), "PUT", "/sangat/car", { token: f.host.token, body: { roomCode: room.code, carMode: true } });
    await forgetPushes();

    room.client.close();
    await guest.client.next("user_disconnected");
    await expireGrace(room.userId);
    await runDurableObjectAlarm(hub());
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("a host who is back for good after a failed reconnect (grace over) is pushed about once", async () => {
    const f = await family();
    const room = await createRoom(hub(), f.host);
    await forgetPushes();

    room.client.close();
    await sleep(150);
    await expireGrace(room.userId);
    const back = await SangatClient.connect(hub(), f.host.token);
    await back.send("reconnect", { session_token: room.token });
    expect((await back.next("error")).payload.code).toBe("session_expired");
    await runDurableObjectAlarm(hub());
    await settle();

    expect(google.sends()).toHaveLength(2);
  });
});

describe("the Sangat kill switch", () => {
  it("switching Sangat off with a live room sends one ended push to every approved phone", async () => {
    const f = await family();
    await createRoom(hub(), f.host);
    await forgetPushes();

    expect((await setSwitch(hub(), "sangat", false)).status).toBe(200);
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.host, f.tokens.b, f.tokens.c].sort());
    expect(google.sends()).toHaveLength(3);
  });

  it("switching Sangat off with no room sends nothing", async () => {
    await family();
    await forgetPushes();

    expect((await setSwitch(hub(), "sangat", false)).status).toBe(200);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("switching another feature off sends nothing", async () => {
    const f = await family();
    await createRoom(hub(), f.host);
    await forgetPushes();

    expect((await setSwitch(hub(), "parivaar_mix", false)).status).toBe(200);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("a started push is still blocked while the switch is off, an ended one is not", async () => {
    const f = await family();
    await sql(hub(), (db) => db.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'"));

    await trigger(f.host.deviceId, "started");
    await settle();
    expect(google.calls).toHaveLength(0);

    await trigger(f.host.deviceId, "ended");
    await settle();
    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });
});

describe("when the hub stays quiet", () => {
  it("does nothing with FCM_SERVICE_ACCOUNT unset: no request at all", async () => {
    const f = await family();
    await setSecret(undefined);

    await createRoom(hub(), f.host);
    await settle();

    expect(google.calls).toHaveLength(0);
    expect(await sql(hub(), (db) => db.exec("SELECT COUNT(*) AS n FROM rate_limits WHERE key LIKE 'push-send:%'").one().n)).toBe(0);
  });

  it("does nothing with an unusable secret", async () => {
    const f = await family();
    await setSecret("{ not json");

    await createRoom(hub(), f.host);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("does nothing while the sangat feature is off", async () => {
    const f = await family();
    await sql(hub(), (db) => db.exec("UPDATE killswitches SET enabled = 0 WHERE key = 'sangat'"));

    await trigger(f.host.deviceId);
    await settle();

    expect(google.calls).toHaveLength(0);
  });

  it("does not count against the limits when nobody has a token to push to", async () => {
    const host = await seedDevice(hub(), "host");
    await registerToken(host);

    await trigger(host.deviceId);
    await settle();

    expect(google.calls).toHaveLength(0);
    expect(await sql(hub(), (db) => db.exec("SELECT COUNT(*) AS n FROM rate_limits WHERE key LIKE 'push-send:%'").one().n)).toBe(0);
  });
});

describe("send limits", () => {
  it("one phone may start 6 pushes per 10 minutes", async () => {
    const f = await family();

    for (let i = 0; i < 9; i++) await trigger(f.host.deviceId);
    await settle();

    // Two recipients each time.
    expect(google.sends()).toHaveLength(12);
    expect(await rateCount(`push-send:${f.host.deviceId}`)).toBe(6);
  });

  it("the family may have 30 per 10 minutes, whoever triggers them", async () => {
    await family();

    for (let i = 0; i < 40; i++) await trigger(`other-phone-${i}`);
    await settle();

    // Three approved phones have a token (pending and revoked ones are not pushed to), none of
    // them excluded: three recipients each time.
    expect(google.sends()).toHaveLength(30 * 3);
    expect(await rateCount("push-send:family")).toBe(30);
  });

  it("a phone that has spent its own limit does not use up the family's", async () => {
    const f = await family();

    for (let i = 0; i < 15; i++) await trigger(f.host.deviceId);
    await settle();

    expect(await rateCount("push-send:family")).toBe(6);
  });

  it("a refusal by the family limit does not cost the phone its own budget", async () => {
    const f = await family();
    for (let i = 0; i < 30; i++) await trigger(`other-phone-${i}`);
    await settle();
    google.calls.length = 0;

    await trigger(f.host.deviceId);
    await settle();

    expect(google.calls).toHaveLength(0);
    expect(await rateCount(`push-send:${f.host.deviceId}`)).toBeUndefined();
    expect(await rateCount("push-send:family")).toBe(30);
  });

  it("an ended push is never limited, and spends nothing", async () => {
    const f = await family();
    for (let i = 0; i < 6; i++) await trigger(f.host.deviceId);
    await settle();
    google.calls.length = 0;
    await trigger(f.host.deviceId);
    await settle();
    expect(google.calls).toHaveLength(0);

    await trigger(f.host.deviceId, "ended");
    await settle();

    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
    expect(await rateCount(`push-send:${f.host.deviceId}`)).toBe(6);

    // Not even with the family's budget gone.
    for (let i = 0; i < 30; i++) await trigger(`other-phone-${i}`);
    await settle();
    google.calls.length = 0;
    await trigger(f.host.deviceId, "ended");
    await settle();
    expect(google.sends()).toHaveLength(2);
  });

  it("a host leaving still pushes after the phone's start budget is spent", async () => {
    const f = await family();
    for (let i = 0; i < 6; i++) await trigger(f.host.deviceId);
    await settle();
    const room = await createRoom(hub(), f.host);
    await settle();
    // The start was refused by the limit...
    expect(google.sends()).toHaveLength(12);
    google.calls.length = 0;

    await room.client.send("leave_room");
    await sleep(100);
    await settle();

    // ...the end was not.
    expect(google.sentTo().sort()).toEqual([f.tokens.b, f.tokens.c].sort());
  });

  it("starts over once the window has passed", async () => {
    const f = await family();
    for (let i = 0; i < 6; i++) await trigger(f.host.deviceId);
    await trigger(f.host.deviceId);
    await settle();
    expect(google.sends()).toHaveLength(12);

    await sql(hub(), (db) => db.exec("UPDATE rate_limits SET windowStart = windowStart - 601000 WHERE key LIKE 'push-send:%'"));
    await trigger(f.host.deviceId);
    await settle();

    expect(google.sends()).toHaveLength(14);
  });
});

describe("dead tokens", () => {
  it("are deleted after the send; the live ones stay", async () => {
    const f = await family();
    useGoogle({ answer: (token) => (token === f.tokens.b ? { status: 404, body: UNREGISTERED_BODY } : undefined) });

    await createRoom(hub(), f.host);
    await settle();

    expect(await storedTokens()).toEqual(
      [f.tokens.host, f.tokens.c, f.tokens.pending, f.tokens.revoked].sort(),
    );
  });

  it("other failures keep the token", async () => {
    const f = await family();
    useGoogle({ answer: () => ({ status: 500, body: { error: { status: "INTERNAL" } } }) });

    await createRoom(hub(), f.host);
    await settle();

    expect(await storedTokens()).toHaveLength(5);
  });

  it("a phone that registered a fresh token while the send was out keeps it", async () => {
    const f = await family();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const inner = fakeGoogle({ answer: (token) => (token === f.tokens.b ? { status: 404, body: UNREGISTERED_BODY } : undefined) });
    fetchSpy.mockImplementation((async (input: RequestInfo | URL, init?: RequestInit) => {
      await gate;
      return inner.fn(input, init);
    }) as typeof fetch);

    await createRoom(hub(), f.host);
    await call(hub(), "PUT", "/push/token", { token: f.b.token, body: { token: "fresh-token-b" } });
    release();
    await settle();

    expect(await storedTokens()).toContain("fresh-token-b");
    expect(await storedTokens()).not.toContain(f.tokens.b);
  });
});
