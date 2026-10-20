import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Sangat } from "../src/sangat/Sangat";
import { closeAllClients, hubStub, openFamily, resetHub, sql, SangatClient } from "./sangat.helpers";

// A car room's guests are remote controllers, so none of them may become its host.

beforeEach(resetHub);
afterEach(closeAllClients);

type Hub = ReturnType<typeof hubStub>;

async function setCar(hub: Hub, code: string, deviceId: string, carMode: boolean) {
  const result = await runInDurableObject(hub, (_i, state) => new Sangat(state).setCarMode(code, deviceId, carMode));
  expect(result).toBe("ok");
}

const roomRow = (hub: Hub, code: string) =>
  sql(hub, (db) => db.exec("SELECT hostUserId, emptySince FROM sangat_rooms WHERE code = ?", code).toArray()[0] ?? null);
const memberCount = (hub: Hub) =>
  sql(hub, (db) => db.exec("SELECT COUNT(*) AS n FROM sangat_members").one().n as number);

async function carFamily(hub: Hub) {
  const family = await openFamily(hub, ["a", "b", "c"]);
  await setCar(hub, family.host.code, family.devices[0].deviceId, true);
  return family;
}

describe("a Sangat in the car", () => {
  it("transfer_host answers not_allowed and the host is unchanged", async () => {
    const hub = hubStub();
    const { host, guests } = await carFamily(hub);

    await host.client.send("transfer_host", { new_host_id: guests[0].userId });

    expect((await host.client.next("error")).payload.code).toBe("not_allowed");
    expect((await roomRow(hub, host.code))?.hostUserId).toBe(host.userId);
    expect(await guests[0].client.quiet()).toEqual([]);
  });

  it("transfer_host still works in a room that is not in car mode", async () => {
    const hub = hubStub();
    const { host, guests } = await openFamily(hub, ["a", "b"]);

    await host.client.send("transfer_host", { new_host_id: guests[0].userId });

    expect((await host.client.next("host_changed")).payload.new_host_id).toBe(guests[0].userId);
  });

  it("when the host leaves, every other member is kicked and the room is emptied", async () => {
    const hub = hubStub();
    const { host, guests, devices } = await carFamily(hub);

    await host.client.send("leave_room");

    for (const guest of guests) {
      expect((await guest.client.next("kicked")).payload.reason).toBe("Sangat in the car has ended");
    }
    expect(await memberCount(hub)).toBe(0);
    expect((await roomRow(hub, host.code))?.emptySince).not.toBeNull();
    const back = await SangatClient.connect(hub, devices[1].token);
    await back.send("reconnect", { session_token: guests[0].token });
    expect((await back.next("error")).payload.code).toBe("session_not_found");
  });

  it("a revoked car phone ends the room the same way", async () => {
    const hub = hubStub();
    const { host, guests, devices } = await carFamily(hub);

    await runInDurableObject(hub, (_i, state) => new Sangat(state).closeDevice(devices[0].deviceId, 4001, "revoked"));

    for (const guest of guests) expect((await guest.client.next("kicked")).payload.reason).toBe("Sangat in the car has ended");
    expect(await memberCount(hub)).toBe(0);
    expect(host.code).toBeTruthy();
  });

  it("the car phone's grace running out ends the room too", async () => {
    const hub = hubStub();
    const { host, guests } = await carFamily(hub);
    host.client.close();
    for (const guest of guests) await guest.client.next("user_disconnected");
    await sql(hub, (db) =>
      db.exec("UPDATE sangat_members SET disconnectedAt = ? WHERE userId = ?", Date.now() - 16 * 60_000, host.userId),
    );

    await runDurableObjectAlarm(hub);

    for (const guest of guests) expect((await guest.client.next("kicked")).payload.reason).toBe("Sangat in the car has ended");
    expect(await memberCount(hub)).toBe(0);
  });

  it("a guest leaving a car room changes nothing about the host", async () => {
    const hub = hubStub();
    const { host, guests } = await carFamily(hub);

    await guests[0].client.send("leave_room");

    expect((await host.client.next("user_left")).payload.user_id).toBe(guests[0].userId);
    expect((await roomRow(hub, host.code))?.hostUserId).toBe(host.userId);
    expect(await memberCount(hub)).toBe(2);
  });

  it("a room that left car mode hands over as before", async () => {
    const hub = hubStub();
    const { host, guests, devices } = await carFamily(hub);
    await setCar(hub, host.code, devices[0].deviceId, false);

    await host.client.send("leave_room");

    expect((await guests[0].client.next("user_left")).payload.user_id).toBe(host.userId);
    expect((await guests[0].client.next("host_changed")).payload.new_host_id).toBe(guests[0].userId);
  });
});
