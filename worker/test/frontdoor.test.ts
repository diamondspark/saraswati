import { SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker, { type Env } from "../src/index";
import { TEST_ADMIN_KEY } from "./helpers";

const BASE = "https://saraswati-sangat.pandey-mohitk.workers.dev";

/** An Env that fails the test the moment anything at all reaches for it. */
const TRIPWIRE_ENV = new Proxy(
  {},
  {
    get(_target, property) {
      throw new Error(`front Worker touched env.${String(property)}`);
    },
  },
) as unknown as Env;

/** An Env whose hub only counts how often it is woken, and answers 299. */
function spyEnv() {
  const calls: Array<{ method: string; path: string }> = [];
  const env = {
    ADMIN_KEY: TEST_ADMIN_KEY,
    FAMILY_HUB: {
      idFromName: () => "family",
      get: () => ({
        fetch: async (request: Request) => {
          calls.push({
            method: request.method,
            path: new URL(request.url).pathname,
          });
          return new Response("from the hub", { status: 299 });
        },
      }),
    },
  } as unknown as Env;
  return { env, calls };
}

function send(env: Env, path: string, init?: RequestInit) {
  return worker.fetch(new Request(`${BASE}${path}`, init), env);
}

const bearer = { Authorization: "Bearer " + "ab".repeat(32) };

/** Every route that needs a bearer, as the hub serves it. */
const AUTHED_ROUTES: Array<[string, string]> = [
  ["POST", "/pairing/create"],
  ["GET", "/roster"],
  ["POST", "/roster/some-device/approve"],
  ["POST", "/roster/some-device/revoke"],
  ["PUT", "/roster/some-device/role"],
  ["GET", "/me"],
  ["GET", "/stations"],
  ["PUT", "/stations"],
  ["GET", "/health"],
  ["POST", "/health"],
  ["GET", "/killswitches"],
  ["PUT", "/killswitches"],
];

describe("a request that needs a bearer but has none", () => {
  it.each(AUTHED_ROUTES)(
    "%s %s answers 401 from the front Worker without waking the hub",
    async (method, path) => {
      const response = await send(TRIPWIRE_ENV, path, { method });

      expect(response.status).toBe(401);
      expect(response.headers.get("content-type")).toBe("application/json");
      expect(await response.json()).toEqual({ error: "unauthorized" });
    },
  );

  it.each([
    ["a Basic scheme", "Basic dXNlcjpwYXNz"],
    ["an empty Bearer", "Bearer "],
    ["a blank Bearer", "Bearer    "],
    ["a lowercase scheme", "bearer " + "ab".repeat(32)],
    ["no scheme at all", "ab".repeat(32)],
  ])("is also 401 with %s", async (_label, authorization) => {
    const response = await send(TRIPWIRE_ENV, "/roster", {
      headers: { Authorization: authorization },
    });

    expect(response.status).toBe(401);
  });

  it("is 401 before any body check, and does not read the body", async () => {
    const response = await send(TRIPWIRE_ENV, "/stations", {
      method: "PUT",
      headers: { "content-length": "999999" },
      body: "{}",
    });

    expect(response.status).toBe(401);
  });
});

describe("a request with a bearer", () => {
  it.each(AUTHED_ROUTES)(
    "%s %s reaches the hub, which does the real check",
    async (method, path) => {
      const { env, calls } = spyEnv();

      const response = await send(env, path, { method, headers: bearer });

      expect(response.status).toBe(299);
      expect(calls).toEqual([{ method, path }]);
    },
  );
});

describe("the routes that need no bearer", () => {
  it.each([
    ["GET", "/ping"],
    ["POST", "/pairing/claim"],
    ["POST", "/pairing/status"],
  ])("%s %s still reaches the hub with no Authorization header", async (method, path) => {
    const { env, calls } = spyEnv();

    const response = await send(env, path, { method });

    expect(response.status).toBe(299);
    expect(calls).toEqual([{ method, path }]);
  });

  it("serves /pair and assetlinks.json without the hub, as before", async () => {
    expect((await send(TRIPWIRE_ENV, "/pair")).status).toBe(200);
    expect(
      (await send(TRIPWIRE_ENV, "/.well-known/assetlinks.json")).status,
    ).toBe(200);
  });
});

describe("an unknown path or method", () => {
  it.each([
    ["GET", "/"],
    ["GET", "/nope"],
    ["GET", "/roster/"],
    ["GET", "/roster/some-device"],
    ["GET", "/roster/some-device/approve"],
    ["POST", "/roster//approve"],
    ["POST", "/roster/a/b/approve"],
    ["POST", "/roster/some-device/promote"],
    ["DELETE", "/stations"],
    ["POST", "/me"],
    ["GET", "/pairing/claim"],
    ["GET", "/pairing/create"],
    ["PATCH", "/killswitches"],
    ["GET", "/PING"],
  ])("%s %s answers 404 from the front Worker, with or without a bearer", async (method, path) => {
    for (const headers of [undefined, bearer]) {
      const response = await send(TRIPWIRE_ENV, path, { method, headers });

      expect(response.status, `${method} ${path}`).toBe(404);
      expect(await response.text()).toBe("not found");
    }
  });

  it.each(["approve", "revoke", "role"])(
    "a malformed percent-escape in the %s path is a 404, not a 500",
    async (action) => {
      const method = action === "role" ? "PUT" : "POST";
      const response = await send(
        TRIPWIRE_ENV,
        `/roster/%E0%A4%A/${action}`,
        { method, headers: bearer },
      );

      expect(response.status).toBe(404);
    },
  );
});

describe("request body limits", () => {
  const postClaim = (env: Env, init: RequestInit) =>
    send(env, "/pairing/claim", { method: "POST", ...init });

  it("lets a body of exactly 8 KiB through", async () => {
    const { env, calls } = spyEnv();

    const response = await postClaim(env, {
      headers: { "content-length": "8192" },
      body: "x".repeat(8192),
    });

    expect(response.status).toBe(299);
    expect(calls).toHaveLength(1);
  });

  it("answers 413 for a body of 8 KiB + 1, without waking the hub", async () => {
    const response = await postClaim(TRIPWIRE_ENV, {
      headers: { "content-length": "8193" },
      body: "x".repeat(8193),
    });

    expect(response.status).toBe(413);
  });

  it("answers 413 for a body that declares no Content-Length", async () => {
    const response = await postClaim(TRIPWIRE_ENV, { body: "{}" });

    expect(response.status).toBe(413);
  });

  it("answers 413 for a chunked body without Content-Length", async () => {
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("{}"));
        controller.close();
      },
    });

    const response = await postClaim(TRIPWIRE_ENV, {
      body,
      // @ts-expect-error `duplex` is required for a streaming body but is not in the DOM typings
      duplex: "half",
    });

    expect(response.status).toBe(413);
  });

  it.each(["-1", "abc", "1e3", "8192.5", " ", "99999999999999999999"])(
    "answers 413 for a Content-Length of %j",
    async (value) => {
      const response = await postClaim(TRIPWIRE_ENV, {
        headers: { "content-length": value },
        body: "{}",
      });

      expect(response.status).toBe(413);
    },
  );

  it("lets a method that has no body through without a Content-Length", async () => {
    const { env, calls } = spyEnv();

    const response = await send(env, "/ping");

    expect(response.status).toBe(299);
    expect(calls).toHaveLength(1);
  });

  it("lets a bodiless POST through, since there is nothing to bound", async () => {
    const { env, calls } = spyEnv();

    const response = await send(env, "/pairing/create", {
      method: "POST",
      headers: bearer,
    });

    expect(response.status).toBe(299);
    expect(calls).toHaveLength(1);
  });

  it("applies to a bearer-carrying request on an authed route too", async () => {
    const response = await send(TRIPWIRE_ENV, "/stations", {
      method: "PUT",
      headers: { ...bearer, "content-length": "9000" },
      body: "x".repeat(9000),
    });

    expect(response.status).toBe(413);
  });

  it("refuses an oversize declared length even on a GET", async () => {
    const response = await send(TRIPWIRE_ENV, "/ping", {
      headers: { "content-length": "9000" },
    });

    expect(response.status).toBe(413);
  });

  it("is enforced against the real hub as well", async () => {
    const response = await SELF.fetch(`${BASE}/pairing/claim`, {
      method: "POST",
      headers: { "content-type": "application/json", "content-length": "9000" },
      body: "x".repeat(9000),
    });

    expect(response.status).toBe(413);
  });
});
