/// <reference types="vite/client" />
import { env, runInDurableObject, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import worker, { type Env } from "../src/index";
// Vite inlines the file as a string at test time. This is the same file the
// release workflow and the updater's pinned-certificate check read.
import releaseCertFile from "../../security/release-cert.sha256?raw";

// The release signing certificate's SHA-256, pinned here on purpose: if the
// key ever rotates, this test fails until assetlinks.json is changed with it,
// instead of the phones silently losing "open the pairing link in the app".
const PINNED_FINGERPRINT =
  "BC:F9:B9:6C:06:91:1F:48:51:F6:99:83:58:DE:16:80:6A:A1:FD:33:FC:80:01:52:06:E2:76:C6:40:D7:5B:AC";

const ASSETLINKS_URL =
  "https://saraswati-sangat.pandey-mohitk.workers.dev/.well-known/assetlinks.json";
const PAIR_URL = "https://saraswati-sangat.pandey-mohitk.workers.dev/pair";
const SETUP_GUIDE_URL = "https://diamondspark.github.io/saraswati/setup-guide/";
const TOKEN = "ab".repeat(32);

/** An Env that fails the test the moment anything reaches for the hub. */
const TRIPWIRE_ENV = new Proxy(
  {},
  {
    get(_target, property) {
      throw new Error(`static route touched env.${String(property)}`);
    },
  },
) as unknown as Env;

/** The Worker entry point with no hub behind it. */
function fetchStatic(url: string, init?: RequestInit): Promise<Response> {
  return worker.fetch(new Request(url, init), TRIPWIRE_ENV);
}

function dbSnapshot() {
  const stub = env.FAMILY_HUB.get(env.FAMILY_HUB.idFromName("family"));
  return runInDurableObject(stub, (_instance, state) => {
    const tables = state.storage.sql
      .exec<{ name: string }>(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '_cf_%' ORDER BY name",
      )
      .toArray()
      .map((row) => row.name);
    return tables.map((name) => ({
      name,
      rows: state.storage.sql
        .exec(`SELECT * FROM "${name}" ORDER BY rowid`)
        .toArray(),
    }));
  });
}

describe("GET /.well-known/assetlinks.json", () => {
  it("serves the Digital Asset Links statement as application/json", async () => {
    const response = await fetchStatic(ASSETLINKS_URL);

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.json()).toEqual([
      {
        relation: ["delegate_permission/common.handle_all_urls"],
        target: {
          namespace: "android_app",
          package_name: "dev.diamondspark.saraswati",
          sha256_cert_fingerprints: [PINNED_FINGERPRINT],
        },
      },
    ]);
  });

  it("matches the committed release certificate, colon-separated and uppercase", () => {
    const hex = releaseCertFile.trim();

    expect(hex).toMatch(/^[0-9a-f]{64}$/);
    expect(hex.toUpperCase().match(/../g)!.join(":")).toBe(PINNED_FINGERPRINT);
  });

  it("needs no auth and no request header at all", async () => {
    const response = await fetchStatic(ASSETLINKS_URL, {
      headers: { Authorization: "Bearer not-a-real-token" },
    });

    expect(response.status).toBe(200);
  });

  it.each(["POST", "PUT", "PATCH", "DELETE", "HEAD"])(
    "answers %s with 405 and an Allow header",
    async (method) => {
      const response = await fetchStatic(ASSETLINKS_URL, { method });

      expect(response.status).toBe(405);
      expect(response.headers.get("allow")).toBe("GET");
    },
  );
});

describe("GET /pair", () => {
  it("serves a static HTML page with the locked-down headers", async () => {
    const response = await fetchStatic(PAIR_URL);

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toBe(
      "text/html; charset=utf-8",
    );
    expect(response.headers.get("content-security-policy")).toBe(
      "default-src 'none'; style-src 'unsafe-inline'",
    );
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
    expect(response.headers.get("x-content-type-options")).toBe("nosniff");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("tells the visitor to open the link on a phone with Saraswati and links the setup guide", async () => {
    const html = await (await fetchStatic(PAIR_URL)).text();

    expect(html).toContain("Open this link on a phone that has Saraswati installed");
    expect(html).toContain(`href="${SETUP_GUIDE_URL}"`);
    expect(html).toContain("सरस्वती");
    expect(html).toContain("Saraswati · Family pairing");
  });

  it("has no script, no external asset and only inline CSS", async () => {
    const html = (await (await fetchStatic(PAIR_URL)).text()).toLowerCase();

    expect(html).toContain("<style>");
    expect(html).not.toMatch(/<script/);
    expect(html).not.toMatch(/<(link|img|iframe|object|embed|video|audio|source|base|form)\b/);
    expect(html).not.toMatch(/\bon[a-z]+\s*=/);
    expect(html).not.toContain("javascript:");
    expect(html).not.toContain("@import");
    expect(html).not.toMatch(/url\(/);
    // The only thing the page points at is the setup guide.
    const urls = html.match(/https?:\/\/[^\s"'<>)]+/g) ?? [];
    expect(new Set(urls)).toEqual(new Set([SETUP_GUIDE_URL]));
  });

  it("never echoes anything from the request", async () => {
    const plain = await (await fetchStatic(PAIR_URL)).text();
    const marker = "ECHO-MARKER-7f3a";

    const probing = await fetchStatic(
      `${PAIR_URL}?next=${marker}&x=<script>alert(1)</script>#${TOKEN}`,
      {
        headers: {
          "user-agent": marker,
          referer: `https://${marker}.example.com/`,
          cookie: `session=${marker}`,
          authorization: `Bearer ${marker}`,
          "x-forwarded-host": `${marker}.example.com`,
          "accept-language": "hi-IN",
        },
      },
    );

    expect(probing.status).toBe(200);
    const body = await probing.text();
    expect(body).toBe(plain);
    expect(body).not.toContain(marker);
    expect(body).not.toContain(TOKEN);
    expect([...probing.headers.values()].join("\n")).not.toContain(marker);
  });

  it("needs no auth", async () => {
    const response = await fetchStatic(PAIR_URL);

    expect(response.status).toBe(200);
  });

  it.each(["POST", "PUT", "PATCH", "DELETE", "HEAD"])(
    "answers %s with 405 and an Allow header",
    async (method) => {
      const response = await fetchStatic(PAIR_URL, { method });

      expect(response.status).toBe(405);
      expect(response.headers.get("allow")).toBe("GET");
    },
  );
});

describe("both routes against the real hub", () => {
  it("touch no table and spend no rate-limit budget", async () => {
    const before = await dbSnapshot();

    for (let i = 0; i < 60; i += 1) {
      expect((await SELF.fetch(PAIR_URL)).status).toBe(200);
      expect((await SELF.fetch(ASSETLINKS_URL)).status).toBe(200);
    }
    await SELF.fetch(PAIR_URL, { method: "POST" });
    await SELF.fetch(ASSETLINKS_URL, { method: "DELETE" });

    expect(await dbSnapshot()).toEqual(before);
  });

  it("leave neighbouring paths to the hub's own not-found", async () => {
    for (const path of ["/pair/", "/pairing", "/.well-known/", "/.well-known/assetlinks.json/"]) {
      const response = await SELF.fetch(
        `https://saraswati-sangat.pandey-mohitk.workers.dev${path}`,
      );

      expect(response.status).toBe(404);
      expect(await response.text()).toBe("not found");
    }
  });

  it("do not disturb the hub's existing routes", async () => {
    const response = await SELF.fetch(
      "https://saraswati-sangat.pandey-mohitk.workers.dev/ping",
    );

    expect(response.status).toBe(200);
    expect(await response.text()).toBe("ok");
  });
});
