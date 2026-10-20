import { env, evictAllDurableObjects, runInDurableObject } from "cloudflare:test";
import { expect, it } from "vitest";
import { sha256Hex } from "./helpers";

// Its own file because evicting waits on every Durable Object of the file's
// isolate, and migration.test.ts leaves some with responses nobody read.

const DEVICE_TOKEN = "99".repeat(32);

it("a deployed v2 hub migrates itself on restart, before it answers anything", async () => {
  const stub = env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName("deployed-v2"));
  const tokenHash = await sha256Hex(DEVICE_TOKEN);

  // Make the stored database what the hub is live with today: Phase 3's
  // tables and data, version 2, none of the family features' tables.
  await runInDurableObject(stub, (_instance, state) => {
    const sql = state.storage.sql;
    for (const table of [
      "parivaar_playlists",
      "family_playlists",
      "sangat_rooms",
      "sangat_members",
      "sangat_pending_joins",
      "sangat_suggestions",
    ]) {
      sql.exec(`DROP TABLE ${table}`);
    }
    sql.exec(
      `INSERT INTO roster (deviceId, deviceName, status, sessionTokenHash, pendingSessionToken, approvedAt, claimSecretHash, role, claimedAt)
       VALUES ('dev-live', 'Live phone', 'approved', ?, NULL, 1700000000000, NULL, 'adult', 1700000000000)`,
      tokenHash,
    );
    sql.exec("UPDATE schema_version SET version = 2");
  });
  await evictAllDurableObjects();

  // The first request after the restart builds a new instance, whose
  // constructor holds every event back until the schema is current.
  const added = await stub.fetch("https://example.com/family-playlists", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${DEVICE_TOKEN}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ playlistId: "PLabcdefghijk", title: "Bhajans" }),
  });
  expect(added.status).toBe(201);
  await added.text();

  await runInDurableObject(stub, (_instance, state) => {
    const sql = state.storage.sql;
    expect(
      sql.exec<{ version: number }>("SELECT version FROM schema_version").one()
        .version,
    ).toBe(5);
    expect(
      sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM family_playlists").one()
        .n,
    ).toBe(1);
    expect(
      sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM sangat_rooms").one().n,
    ).toBe(0);
  });
});
