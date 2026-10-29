// src/push/fcm.ts on its own: configuration, the signed OAuth assertion, the access-token
// cache, what is sent, how answers are classified, and that nothing here ever throws or hangs.

import { afterEach, describe, expect, it, vi } from "vitest";
import { FcmClient, parseServiceAccount } from "../src/push/fcm";
import {
  CLIENT_EMAIL,
  fakeAccount,
  fakeGoogle,
  PROJECT_ID,
  SEND_URL,
  TOKEN_URL,
  UNREGISTERED_BODY,
} from "./push.helpers";

afterEach(() => vi.restoreAllMocks());

const b64urlToBytes = (text: string): Uint8Array => {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4);
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
};
const b64urlJson = (text: string) => JSON.parse(new TextDecoder().decode(b64urlToBytes(text)));

describe("configuration", () => {
  it("is disabled without a usable secret, and then makes no request at all", async () => {
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn });
    const account = JSON.parse((await fakeAccount()).json);
    const bad: (string | undefined)[] = [
      undefined,
      "",
      "   ",
      "not json",
      "[]",
      "null",
      JSON.stringify({ ...account, project_id: undefined }),
      JSON.stringify({ ...account, client_email: "" }),
      JSON.stringify({ ...account, private_key: undefined }),
      JSON.stringify({ ...account, private_key: "plain text" }),
    ];
    for (const secret of bad) {
      expect(client.isConfigured({ FCM_SERVICE_ACCOUNT: secret })).toBe(false);
      expect(await client.send({ FCM_SERVICE_ACCOUNT: secret }, ["tok-1", "tok-2"])).toEqual([]);
    }
    expect(google.fn).not.toHaveBeenCalled();
  });

  it("reads project_id, client_email and private_key", async () => {
    const { json } = await fakeAccount();
    expect(parseServiceAccount(json)).toMatchObject({ projectId: PROJECT_ID, clientEmail: CLIENT_EMAIL });
    expect(new FcmClient().isConfigured({ FCM_SERVICE_ACCOUNT: json })).toBe(true);
  });

  it("an unusable private key sends nothing and does not throw", async () => {
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn });
    const secret = JSON.stringify({
      project_id: PROJECT_ID,
      client_email: CLIENT_EMAIL,
      private_key: "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n",
    });
    expect(await client.send({ FCM_SERVICE_ACCOUNT: secret }, ["tok-1"])).toEqual([]);
    expect(google.fn).not.toHaveBeenCalled();
  });
});

describe("the OAuth exchange", () => {
  it("posts a form with an RS256 JWT whose header, claims and signature are right", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn, now: () => 1_700_000_000_500 });

    await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["tok-1"]);

    const [request] = google.tokenRequests();
    expect(request.init.method).toBe("POST");
    expect((request.init.headers as Record<string, string>)["Content-Type"]).toBe(
      "application/x-www-form-urlencoded",
    );
    expect(request.init.redirect).toBe("manual");
    const form = new URLSearchParams(String(request.init.body));
    expect(form.get("grant_type")).toBe("urn:ietf:params:oauth:grant-type:jwt-bearer");
    const [header, claims, signature] = form.get("assertion")!.split(".");
    expect(b64urlJson(header)).toEqual({ alg: "RS256", typ: "JWT" });
    expect(b64urlJson(claims)).toEqual({
      iss: CLIENT_EMAIL,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: TOKEN_URL,
      iat: 1_700_000_000,
      exp: 1_700_003_600,
    });
    const valid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      account.publicKey,
      b64urlToBytes(signature),
      new TextEncoder().encode(`${header}.${claims}`),
    );
    expect(valid).toBe(true);
  });

  it("a refused exchange sends nothing", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle({ tokenStatus: 400 });
    const client = new FcmClient({ fetchFn: google.fn });

    expect(await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["tok-1"])).toEqual([]);
    expect(google.sends()).toHaveLength(0);
  });

  it("shares one exchange between parallel sends and between later calls", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn });
    const env = { FCM_SERVICE_ACCOUNT: account.json };

    await client.send(env, ["t1", "t2", "t3", "t4", "t5"]);
    await client.send(env, ["t6"]);

    expect(google.tokenRequests()).toHaveLength(1);
    expect(google.sends()).toHaveLength(6);
  });

  it("reuses the access token until 60 s before it expires, then gets a new one", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle({ expiresIn: 3600 });
    let now = 1_700_000_000_000;
    const client = new FcmClient({ fetchFn: google.fn, now: () => now });
    const env = { FCM_SERVICE_ACCOUNT: account.json };

    await client.send(env, ["t1"]);
    now += (3600 - 61) * 1000;
    await client.send(env, ["t2"]);
    expect(google.tokenRequests()).toHaveLength(1);

    now += 2000;
    await client.send(env, ["t3"]);
    expect(google.tokenRequests()).toHaveLength(2);
    expect((google.sends()[2].init.headers as Record<string, string>).Authorization).toBe("Bearer at-2");
  });

  it("a rotated secret does not reuse the old secret's access token", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn });

    await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["t1"]);
    await client.send({ FCM_SERVICE_ACCOUNT: account.json + " " }, ["t2"]);

    expect(google.tokenRequests()).toHaveLength(2);
  });

  it.each([
    ["401", 401, { error: { code: 401, status: "UNAUTHENTICATED" } }],
    ["401 with no body", 401, undefined],
    ["403 PERMISSION_DENIED", 403, { error: { code: 403, status: "PERMISSION_DENIED", message: "denied" } }],
    ["403 UNAUTHENTICATED", 403, { error: { code: 403, status: "UNAUTHENTICATED" } }],
  ])("drops the cached access token when FCM answers %s", async (_label, status, body) => {
    const account = await fakeAccount();
    let reject = true;
    const google = fakeGoogle({ answer: () => (reject ? { status, body } : undefined) });
    const client = new FcmClient({ fetchFn: google.fn });
    const env = { FCM_SERVICE_ACCOUNT: account.json };

    expect(await client.send(env, ["t1"])).toEqual([]);
    reject = false;
    await client.send(env, ["t2"]);

    expect(google.tokenRequests()).toHaveLength(2);
    expect((google.sends()[1].init.headers as Record<string, string>).Authorization).toBe("Bearer at-2");
  });

  it("403 SENDER_ID_MISMATCH prunes the token and keeps the access token", async () => {
    const account = await fakeAccount();
    const mismatch = {
      error: {
        code: 403,
        message: "SenderId mismatch",
        status: "PERMISSION_DENIED",
        details: [
          { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "SENDER_ID_MISMATCH" },
        ],
      },
    };
    const google = fakeGoogle({ answer: (t) => (t === "foreign" ? { status: 403, body: mismatch } : undefined) });
    const client = new FcmClient({ fetchFn: google.fn });
    const env = { FCM_SERVICE_ACCOUNT: account.json };

    expect(await client.send(env, ["foreign", "mine"])).toEqual(["foreign"]);
    await client.send(env, ["mine"]);

    expect(google.tokenRequests()).toHaveLength(1);
    // The same code in error.status alone counts too.
    const bare = fakeGoogle({ answer: () => ({ status: 403, body: { error: { status: "SENDER_ID_MISMATCH" } } }) });
    expect(await new FcmClient({ fetchFn: bare.fn }).send(env, ["foreign"])).toEqual(["foreign"]);
  });

  it("a 403 that is not an auth failure leaves the access token and the token alone", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle({
      answer: () => ({ status: 403, body: { error: { code: 403, status: "SOMETHING_ELSE" } } }),
    });
    const client = new FcmClient({ fetchFn: google.fn });
    const env = { FCM_SERVICE_ACCOUNT: account.json };

    expect(await client.send(env, ["t1"])).toEqual([]);
    expect(await client.send(env, ["t2"])).toEqual([]);
    const unreadable = fakeGoogle({ answer: () => ({ status: 403 }) });
    const other = new FcmClient({ fetchFn: unreadable.fn });
    await other.send(env, ["t1"]);
    await other.send(env, ["t2"]);

    expect(google.tokenRequests()).toHaveLength(1);
    expect(unreadable.tokenRequests()).toHaveLength(1);
  });
});

describe("sending", () => {
  it("posts exactly the data-only message, with the bearer, to the project's endpoint", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle();
    const client = new FcmClient({ fetchFn: google.fn });

    await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["tok:abc-1.2_3"]);

    const [send] = google.sends();
    expect(send.url).toBe(SEND_URL);
    expect(send.init.method).toBe("POST");
    expect(send.init.redirect).toBe("manual");
    expect(send.init.headers).toMatchObject({
      Authorization: "Bearer at-1",
      "Content-Type": "application/json",
    });
    expect(send.init.body).toBe(
      '{"message":{"token":"tok:abc-1.2_3","data":{"type":"sangat_changed"},"android":{"priority":"HIGH","ttl":"300s","collapse_key":"sangat"}}}',
    );
  });

  it("sends each distinct token once, all of them in parallel", async () => {
    const account = await fakeAccount();
    let inFlight = 0;
    let peak = 0;
    const base = fakeGoogle();
    const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === SEND_URL) {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 20));
        inFlight -= 1;
      }
      return base.fn(input, init);
    }) as unknown as typeof fetch;
    const client = new FcmClient({ fetchFn: fn });

    await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["a", "b", "c", "a"]);

    expect(base.sentTo().sort()).toEqual(["a", "b", "c"]);
    expect(peak).toBe(3);
  });
});

describe("classifying the answers", () => {
  const dead = async (answer: { status: number; body?: unknown } | undefined, rawBody?: string) => {
    const account = await fakeAccount();
    const fn = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === TOKEN_URL) {
        return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }));
      }
      if (!answer) return new Response("{}", { status: 200 });
      return new Response(rawBody ?? JSON.stringify(answer.body), { status: answer.status });
    }) as unknown as typeof fetch;
    return new FcmClient({ fetchFn: fn }).send({ FCM_SERVICE_ACCOUNT: account.json }, ["the-token"]);
  };

  it("200 is delivered, not dead", async () => {
    expect(await dead(undefined)).toEqual([]);
  });

  it("a 404 with the UNREGISTERED detail is dead", async () => {
    expect(await dead({ status: 404, body: UNREGISTERED_BODY })).toEqual(["the-token"]);
  });

  it("404 NOT_FOUND without UNREGISTERED keeps the token (a wrong project_id looks like this)", async () => {
    const notFound = { error: { code: 404, message: "Requested entity was not found.", status: "NOT_FOUND" } };
    expect(await dead({ status: 404, body: notFound })).toEqual([]);
    expect(await dead({ status: 404, body: { error: { status: "NOT_FOUND", details: [{ errorCode: "SOMETHING_ELSE" }] } } })).toEqual([]);
    expect(await dead({ status: 404 }, "")).toEqual([]);
    expect(await dead({ status: 404 }, "<html>not found</html>")).toEqual([]);
  });

  it("UNREGISTERED counts only as an errorCode in details, whatever the HTTP status", async () => {
    expect(await dead({ status: 410, body: UNREGISTERED_BODY })).toEqual(["the-token"]);
    expect(await dead({ status: 400, body: { error: { status: "UNREGISTERED" } } })).toEqual([]);
  });

  it("INVALID_ARGUMENT about the registration token is dead; about anything else it is not", async () => {
    const inDetails = {
      error: {
        status: "INVALID_ARGUMENT",
        message: "Request contains an invalid argument.",
        details: [{ fieldViolations: [{ field: "message.token", description: "Invalid registration token" }] }],
      },
    };
    expect(await dead({ status: 400, body: inDetails })).toEqual(["the-token"]);
    const invalidToken = {
      error: {
        code: 400,
        status: "INVALID_ARGUMENT",
        message: "The registration token is not a valid FCM registration token",
      },
    };
    expect(await dead({ status: 400, body: invalidToken })).toEqual(["the-token"]);
    expect(
      await dead({
        status: 400,
        body: { error: { status: "INVALID_ARGUMENT", message: "Invalid JSON payload received." } },
      }),
    ).toEqual([]);
  });

  it("other failures are ignored, and the token is kept", async () => {
    expect(await dead({ status: 500, body: { error: { status: "INTERNAL" } } })).toEqual([]);
    expect(await dead({ status: 429, body: { error: { status: "QUOTA_EXCEEDED" } } })).toEqual([]);
    expect(await dead({ status: 503 }, "<html>busy</html>")).toEqual([]);
    expect(await dead({ status: 400 }, "not json")).toEqual([]);
  });

  it("returns only the dead tokens of a mixed batch", async () => {
    const account = await fakeAccount();
    const google = fakeGoogle({
      answer: (token) =>
        token === "gone" ? { status: 404, body: UNREGISTERED_BODY } : token === "sick" ? { status: 500 } : undefined,
    });
    const client = new FcmClient({ fetchFn: google.fn });

    expect(await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["ok", "gone", "sick"])).toEqual(["gone"]);
  });
});

describe("failure handling", () => {
  it("a token exchange that never answers times out and returns without throwing", async () => {
    const account = await fakeAccount();
    const fn = vi.fn(() => new Promise<Response>(() => undefined)) as unknown as typeof fetch;
    const client = new FcmClient({ fetchFn: fn, timeoutMs: 30 });

    await expect(client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["t1"])).resolves.toEqual([]);
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("a send that never answers times out on its own, the others still count", async () => {
    const account = await fakeAccount();
    const base = fakeGoogle({ answer: (t) => (t === "gone" ? { status: 404, body: UNREGISTERED_BODY } : undefined) });
    const fn = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === SEND_URL && JSON.parse(String(init?.body)).message.token === "stuck") {
        return new Promise<Response>(() => undefined);
      }
      return base.fn(input, init);
    }) as unknown as typeof fetch;
    const client = new FcmClient({ fetchFn: fn, timeoutMs: 100 });

    const started = Date.now();
    const result = await client.send({ FCM_SERVICE_ACCOUNT: account.json }, ["stuck", "gone", "fine"]);

    expect(result).toEqual(["gone"]);
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("aborts the request's signal when it times out", async () => {
    const account = await fakeAccount();
    let signal: AbortSignal | undefined;
    const fn = vi.fn((_input: RequestInfo | URL, init?: RequestInit) => {
      signal = init?.signal ?? undefined;
      return new Promise<Response>(() => undefined);
    }) as unknown as typeof fetch;

    await new FcmClient({ fetchFn: fn, timeoutMs: 20 }).send({ FCM_SERVICE_ACCOUNT: account.json }, ["t1"]);

    expect(signal?.aborted).toBe(true);
  });

  it("a fetch that throws is swallowed, and neither the key nor a token is logged", async () => {
    const account = await fakeAccount();
    const logs = (["log", "error", "warn", "info"] as const).map((k) => vi.spyOn(console, k));
    const fn = vi.fn(async () => {
      throw new Error("boom with secret-device-token");
    }) as unknown as typeof fetch;

    await expect(
      new FcmClient({ fetchFn: fn }).send({ FCM_SERVICE_ACCOUNT: account.json }, ["secret-device-token"]),
    ).resolves.toEqual([]);

    for (const log of logs) expect(log).not.toHaveBeenCalled();
  });

  it("a token endpoint answering garbage is swallowed", async () => {
    const account = await fakeAccount();
    const fn = vi.fn(async () => new Response("not json", { status: 200 })) as unknown as typeof fetch;

    await expect(
      new FcmClient({ fetchFn: fn }).send({ FCM_SERVICE_ACCOUNT: account.json }, ["t1"]),
    ).resolves.toEqual([]);
  });
});
