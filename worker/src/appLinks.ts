// Two static, public pages that let a family phone open a pairing link in the
// app instead of the browser. The Worker entry point answers them before the
// Durable Object is ever touched: no table is read, no request is counted
// against any rate limit, and nothing here looks at the request beyond its
// path and method.
//
//   GET /.well-known/assetlinks.json   Digital Asset Links, which is how
//                                      Android verifies the app's autoVerify
//                                      intent filter for this host and /pair.
//   GET /pair                          What a browser (or a phone without the
//                                      app) sees. The token is in the URL
//                                      fragment, which never reaches us, and
//                                      this page has no script to read it.

const APP_PACKAGE = "dev.diamondspark.saraswati";

// The release certificate's SHA-256 as Digital Asset Links wants it: uppercase
// hex pairs, colon-separated. Derived from security/release-cert.sha256; the
// test in test/applinks.test.ts pins the exact string and fails if the two
// ever drift (a rotated key must change both together).
const RELEASE_CERT_SHA256 =
  "BC:F9:B9:6C:06:91:1F:48:51:F6:99:83:58:DE:16:80:6A:A1:FD:33:FC:80:01:52:06:E2:76:C6:40:D7:5B:AC";

const SETUP_GUIDE_URL = "https://diamondspark.github.io/saraswati/setup-guide/";

const ASSET_LINKS_BODY = JSON.stringify([
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: APP_PACKAGE,
      sha256_cert_fingerprints: [RELEASE_CERT_SHA256],
    },
  },
]);

// Static on purpose: nothing from the request is ever interpolated into this.
// The CSP below forbids scripts, images, fonts and everything else except
// this inline stylesheet.
const PAIR_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Saraswati · Family pairing</title>
<style>
:root { color-scheme: light dark; }
body {
  margin: 0;
  padding: 24px 16px;
  font: 18px/1.5 system-ui, sans-serif;
  background: #fffaf2;
  color: #2b2118;
}
main { max-width: 32rem; margin: 0 auto; }
h1 { margin: 0; font-size: 2.25rem; line-height: 1.2; }
.sub { margin: 4px 0 32px; color: #6b5a48; }
.ask { font-size: 1.25rem; font-weight: 600; }
a { color: #9a3b00; }
@media (prefers-color-scheme: dark) {
  body { background: #1d1711; color: #f3e8d8; }
  .sub { color: #c4b19a; }
  a { color: #ffb27a; }
}
</style>
</head>
<body>
<main>
<h1 lang="hi">सरस्वती</h1>
<p class="sub">Saraswati · Family pairing</p>
<p class="ask">Open this link on a phone that has Saraswati installed.</p>
<p>Not installed yet? Follow the <a href="${SETUP_GUIDE_URL}" rel="noopener noreferrer">setup guide</a>, then open this link again.</p>
</main>
</body>
</html>
`;

function notAllowed(): Response {
  return new Response("method not allowed", {
    status: 405,
    headers: { allow: "GET", "content-type": "text/plain; charset=utf-8" },
  });
}

/**
 * The response for an app-link path, or null when the request is for anything
 * else and belongs to the hub. Exact path match only.
 */
export function appLinkResponse(request: Request): Response | null {
  const { pathname } = new URL(request.url);

  if (pathname === "/.well-known/assetlinks.json") {
    if (request.method !== "GET") return notAllowed();
    return new Response(ASSET_LINKS_BODY, {
      status: 200,
      headers: { "content-type": "application/json" },
    });
  }

  if (pathname === "/pair") {
    if (request.method !== "GET") return notAllowed();
    return new Response(PAIR_PAGE, {
      status: 200,
      headers: {
        "content-type": "text/html; charset=utf-8",
        "content-security-policy":
          "default-src 'none'; style-src 'unsafe-inline'",
        "referrer-policy": "no-referrer",
        "x-content-type-options": "nosniff",
        "cache-control": "no-store",
      },
    });
  }

  return null;
}
