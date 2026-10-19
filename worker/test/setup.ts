import { env, runInDurableObject } from "cloudflare:test";
import { beforeEach } from "vitest";

// Every test in a file shares one FamilyHub, and the hub caps admin requests
// at 20 per minute. Each test starts with a fresh admin budget so that a
// file's worth of admin-authed setup calls does not trip a cap that
// adminlimit.test.ts exercises for real. Wired in via vitest.config.ts.
beforeEach(async () => {
  const stub = env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName("family"));
  await runInDurableObject(stub, (_instance, state) => {
    state.storage.sql.exec("DELETE FROM rate_limits WHERE key = 'admin'");
  });
});
