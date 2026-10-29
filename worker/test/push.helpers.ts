// Shared by the FCM push tests: a fake Google service account built from a key generated in
// the test, and a fetch stand-in that plays both Google's token endpoint and FCM.

import { vi } from "vitest";

export const TOKEN_URL = "https://oauth2.googleapis.com/token";
export const PROJECT_ID = "saraswati-test";
export const CLIENT_EMAIL = "hub@saraswati-test.iam.gserviceaccount.com";
export const SEND_URL = `https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send`;

function pem(der: ArrayBuffer): string {
  let binary = "";
  for (const byte of new Uint8Array(der)) binary += String.fromCharCode(byte);
  const lines = btoa(binary).match(/.{1,64}/g) ?? [];
  return `-----BEGIN PRIVATE KEY-----\n${lines.join("\n")}\n-----END PRIVATE KEY-----\n`;
}

export interface FakeAccount {
  /** The service-account JSON as the FCM_SERVICE_ACCOUNT secret holds it. */
  json: string;
  publicKey: CryptoKey;
}

let cached: Promise<FakeAccount> | undefined;

/** One RSA key per test file; generating it is the slow part. */
export function fakeAccount(): Promise<FakeAccount> {
  cached ??= (async () => {
    const pair = (await crypto.subtle.generateKey(
      {
        name: "RSASSA-PKCS1-v1_5",
        modulusLength: 2048,
        publicExponent: new Uint8Array([1, 0, 1]),
        hash: "SHA-256",
      },
      true,
      ["sign", "verify"],
    )) as CryptoKeyPair;
    const der = (await crypto.subtle.exportKey("pkcs8", pair.privateKey)) as ArrayBuffer;
    return {
      json: JSON.stringify({
        type: "service_account",
        project_id: PROJECT_ID,
        private_key_id: "abc123",
        private_key: pem(der),
        client_email: CLIENT_EMAIL,
        client_id: "1234567890",
      }),
      publicKey: pair.publicKey,
    };
  })();
  return cached;
}

export type SendAnswer = { status: number; body?: unknown };

export interface RecordedCall {
  url: string;
  init: RequestInit;
}

/**
 * A fetch stand-in. Token requests are answered with `accessToken` (valid for `expiresIn`
 * seconds); each send is answered by `answer(deviceToken)`, 200 unless it says otherwise.
 */
export function fakeGoogle(
  opts: {
    answer?: (token: string) => SendAnswer | undefined;
    expiresIn?: number;
    tokenStatus?: number;
  } = {},
) {
  const calls: RecordedCall[] = [];
  let minted = 0;
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init: init ?? {} });
    if (url === TOKEN_URL) {
      if (opts.tokenStatus && opts.tokenStatus !== 200) {
        return new Response("{}", { status: opts.tokenStatus });
      }
      minted += 1;
      return new Response(
        JSON.stringify({ access_token: `at-${minted}`, expires_in: opts.expiresIn ?? 3600, token_type: "Bearer" }),
        { status: 200 },
      );
    }
    const token = JSON.parse(String(init?.body)).message.token as string;
    const answer = opts.answer?.(token);
    if (!answer) return new Response(JSON.stringify({ name: "projects/x/messages/1" }), { status: 200 });
    return new Response(answer.body === undefined ? "" : JSON.stringify(answer.body), {
      status: answer.status,
    });
  }) as unknown as typeof fetch & ReturnType<typeof vi.fn>;
  return {
    fn,
    calls,
    tokenRequests: () => calls.filter((c) => c.url === TOKEN_URL),
    sends: () => calls.filter((c) => c.url === SEND_URL),
    /** The device tokens pushed to, in call order. */
    sentTo: () =>
      calls
        .filter((c) => c.url === SEND_URL)
        .map((c) => JSON.parse(String(c.init.body)).message.token as string),
  };
}

export const UNREGISTERED_BODY = {
  error: {
    code: 404,
    message: "Requested entity was not found.",
    status: "NOT_FOUND",
    details: [
      { "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" },
    ],
  },
};
