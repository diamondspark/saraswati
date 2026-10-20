import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";

describe("FamilyHub", () => {
  it("responds to GET /ping with 200 \"ok\" through the FAMILY_HUB binding", async () => {
    const id = env.FAMILY_HUB.idFromName("family");
    const stub = env.FAMILY_HUB.get(id);

    const response = await stub.fetch("https://example.com/ping");

    expect(response.status).toBe(200);
    expect(await response.text()).toBe("ok");
  });
});
