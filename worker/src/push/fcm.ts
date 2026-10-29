// Firebase Cloud Messaging, from the hub, for one purpose: waking a closed app with a data
// push that says only "something changed" (the app then asks GET /sangat/live itself).
//
// The service account is an optional Worker secret (FCM_SERVICE_ACCOUNT, the Google
// service-account JSON as one string). Without a usable one the client is disabled and makes
// no request at all. Like feedback/githubIssue.ts, nothing here throws, every request has a
// 5 s limit, redirects are never followed, and neither a token nor a key is ever logged.

export interface FcmEnv {
  FCM_SERVICE_ACCOUNT?: string;
}

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const REQUEST_TIMEOUT_MS = 5000;
const JWT_LIFETIME_SECONDS = 3600;
// An access token is reused until this long before Google says it expires.
const TOKEN_EARLY_EXPIRY_MS = 60_000;
// Only the start of an error body is read: enough to classify it.
const MAX_ERROR_BODY_CHARS = 4096;

interface ServiceAccount {
  projectId: string;
  clientEmail: string;
  privateKey: string;
}

/** The fields the client needs from the service-account JSON, or null when it is not usable. */
export function parseServiceAccount(raw: unknown): ServiceAccount | null {
  if (typeof raw !== "string" || raw.trim() === "") {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null) {
      return null;
    }
    const { project_id, client_email, private_key } = parsed as Record<string, unknown>;
    if (
      typeof project_id !== "string" ||
      project_id === "" ||
      typeof client_email !== "string" ||
      client_email === "" ||
      typeof private_key !== "string" ||
      !private_key.includes("PRIVATE KEY")
    ) {
      return null;
    }
    return { projectId: project_id, clientEmail: client_email, privateKey: private_key };
  } catch {
    return null;
  }
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const encodeJson = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));

function pemToDer(pem: string): Uint8Array {
  const body = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const binary = atob(body);
  const der = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    der[i] = binary.charCodeAt(i);
  }
  return der;
}

/** The signed RS256 assertion Google's token endpoint exchanges for an access token. */
async function signAssertion(account: ServiceAccount, nowMs: number): Promise<string> {
  const iat = Math.floor(nowMs / 1000);
  const signingInput =
    encodeJson({ alg: "RS256", typ: "JWT" }) +
    "." +
    encodeJson({
      iss: account.clientEmail,
      scope: SCOPE,
      aud: TOKEN_URL,
      iat,
      exp: iat + JWT_LIFETIME_SECONDS,
    });
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.privateKey),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64url(new Uint8Array(signature))}`;
}

function discard(response: Response): void {
  try {
    void response.body?.cancel().catch(() => undefined);
  } catch {
    // nothing to release
  }
}

/** What one send came to. "dead" means the registration token is gone for good. */
type Outcome = "ok" | "dead" | "auth" | "other";

async function classify(response: Response): Promise<Outcome> {
  if (response.ok) {
    discard(response);
    return "ok";
  }
  if (response.status === 401 || response.status === 403) {
    discard(response);
    return "auth";
  }
  // Not even a 404 is proof: a wrong project_id answers 404 NOT_FOUND too, and treating that as
  // a dead token would wipe every registration. Only FCM's own words count.
  let error: Record<string, unknown> | null = null;
  try {
    const text = (await response.text()).slice(0, MAX_ERROR_BODY_CHARS);
    const parsed: unknown = JSON.parse(text);
    const inner = (parsed as { error?: unknown } | null)?.error;
    error = typeof inner === "object" && inner !== null ? (inner as Record<string, unknown>) : null;
  } catch {
    return "other";
  }
  if (!error) {
    return "other";
  }
  const details = Array.isArray(error.details) ? (error.details as unknown[]) : [];
  if (
    details.some(
      (detail) => (detail as { errorCode?: unknown } | null)?.errorCode === "UNREGISTERED",
    )
  ) {
    return "dead";
  }
  if (
    error.status === "INVALID_ARGUMENT" &&
    (/registration token/i.test(typeof error.message === "string" ? error.message : "") ||
      /registration token/i.test(JSON.stringify(details)))
  ) {
    return "dead";
  }
  return "other";
}

export interface FcmOptions {
  fetchFn?: typeof fetch;
  /** Epoch milliseconds. */
  now?: () => number;
  timeoutMs?: number;
}

interface CachedToken {
  /** The secret the token was minted for, so a rotated secret never reuses it. */
  secret: string;
  value: string;
  expiresAt: number;
}

export class FcmClient {
  private readonly fetchFn: typeof fetch;
  private readonly now: () => number;
  private readonly timeoutMs: number;
  private cached: CachedToken | null = null;
  private minting: { secret: string; promise: Promise<string | null> } | null = null;

  constructor(options: FcmOptions = {}) {
    this.fetchFn = options.fetchFn ?? ((input, init) => fetch(input, init));
    this.now = options.now ?? (() => Date.now());
    this.timeoutMs = options.timeoutMs ?? REQUEST_TIMEOUT_MS;
  }

  /** True when the secret holds a service account this client can use. */
  isConfigured(env: FcmEnv): boolean {
    return parseServiceAccount(env.FCM_SERVICE_ACCOUNT) !== null;
  }

  /**
   * Sends the "something changed" push to each token, all at once, and returns the tokens FCM
   * says are dead so the caller can forget them. Never throws; with no usable secret it makes
   * no request and returns [].
   */
  async send(env: FcmEnv, tokens: readonly string[]): Promise<string[]> {
    try {
      const secret = env.FCM_SERVICE_ACCOUNT;
      const account = parseServiceAccount(secret);
      if (!account || typeof secret !== "string" || tokens.length === 0) {
        return [];
      }
      const accessToken = await this.accessToken(secret, account);
      if (accessToken === null) {
        return [];
      }
      const unique = [...new Set(tokens)];
      const outcomes = await Promise.all(
        unique.map((token) => this.sendOne(account, accessToken, token)),
      );
      if (outcomes.includes("auth")) {
        this.cached = null;
      }
      return unique.filter((_, index) => outcomes[index] === "dead");
    } catch {
      return [];
    }
  }

  /** Runs one request with the time limit; `fallback` is what a slow or failed attempt gives. */
  private async timed<T>(run: (signal: AbortSignal) => Promise<T>, fallback: T): Promise<T> {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    // The race bounds the whole attempt even if a fetch stand-in ignores its signal.
    const timedOut = new Promise<T>((resolve) => {
      timer = setTimeout(() => {
        controller.abort();
        resolve(fallback);
      }, this.timeoutMs);
    });
    const attempt = run(controller.signal).catch(() => fallback);
    try {
      return await Promise.race([attempt, timedOut]);
    } finally {
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    }
  }

  private async accessToken(secret: string, account: ServiceAccount): Promise<string | null> {
    if (this.cached && this.cached.secret === secret && this.cached.expiresAt > this.now()) {
      return this.cached.value;
    }
    // Parallel callers share one exchange.
    if (this.minting && this.minting.secret === secret) {
      return this.minting.promise;
    }
    const promise = this.mint(secret, account);
    const entry = { secret, promise };
    this.minting = entry;
    try {
      return await promise;
    } finally {
      if (this.minting === entry) {
        this.minting = null;
      }
    }
  }

  private mint(secret: string, account: ServiceAccount): Promise<string | null> {
    return this.timed(async (signal) => {
      const issuedAt = this.now();
      const assertion = await signAssertion(account, issuedAt);
      const response = await this.fetchFn(TOKEN_URL, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
          assertion,
        }).toString(),
        signal,
        redirect: "manual",
      });
      if (!response.ok) {
        discard(response);
        return null;
      }
      const granted = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
      if (typeof granted.access_token !== "string" || granted.access_token === "") {
        return null;
      }
      const lifetimeSeconds = typeof granted.expires_in === "number" ? granted.expires_in : 3600;
      this.cached = {
        secret,
        value: granted.access_token,
        expiresAt: issuedAt + Math.max(0, lifetimeSeconds * 1000 - TOKEN_EARLY_EXPIRY_MS),
      };
      return granted.access_token;
    }, null);
  }

  private sendOne(account: ServiceAccount, accessToken: string, token: string): Promise<Outcome> {
    return this.timed(async (signal) => {
      const response = await this.fetchFn(
        `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(account.projectId)}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": "application/json",
          },
          // Data only, and nothing in it but the kind of event: no names, rooms or songs.
          body: JSON.stringify({
            message: {
              token,
              data: { type: "sangat_changed" },
              android: { priority: "HIGH", ttl: "300s", collapse_key: "sangat" },
            },
          }),
          signal,
          redirect: "manual",
        },
      );
      return classify(response);
    }, "other" as Outcome);
  }
}
