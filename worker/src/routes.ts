// The hub's route table, shared by the front Worker (index.ts) and the Durable
// Object (FamilyHub.ts) so that what one calls unknown the other does too, and
// so a route's "needs a bearer" flag lives in exactly one place.

export type RouteName =
  | "ping"
  | "pairingCreate"
  | "pairingClaim"
  | "pairingStatus"
  | "rosterList"
  | "rosterApprove"
  | "rosterRevoke"
  | "rosterRole"
  | "me"
  | "stationsGet"
  | "stationsPut"
  | "healthPost"
  | "healthGet"
  | "killswitchesGet"
  | "killswitchesPut";

export interface Route {
  name: RouteName;
  /**
   * True when the route's handler answers 401 to a request with no usable
   * bearer. The front Worker uses it to refuse such a request without waking
   * the Durable Object. Routes that are false take no bearer at all.
   */
  needsBearer: boolean;
  /** The decoded `{id}` of a /roster/{id}/... route. */
  deviceId?: string;
}

/**
 * Decodes one path segment, or returns null when it holds a malformed
 * percent-escape such as `%E0%A4%A`. decodeURIComponent throws on those, and a
 * throw before authentication would be a 500 any anonymous caller could cause.
 */
export function decodeSegment(segment: string): string | null {
  try {
    return decodeURIComponent(segment);
  } catch {
    return null;
  }
}

const ROSTER_ACTION = /^\/roster\/([^/]+)\/(approve|revoke|role)$/;

/** The route a request is for, or null when the hub has no such route. */
export function matchRoute(method: string, pathname: string): Route | null {
  switch (pathname) {
    case "/ping":
      return method === "GET"
        ? { name: "ping", needsBearer: false }
        : null;
    case "/pairing/create":
      return method === "POST"
        ? { name: "pairingCreate", needsBearer: true }
        : null;
    case "/pairing/claim":
      return method === "POST"
        ? { name: "pairingClaim", needsBearer: false }
        : null;
    case "/pairing/status":
      return method === "POST"
        ? { name: "pairingStatus", needsBearer: false }
        : null;
    case "/roster":
      return method === "GET"
        ? { name: "rosterList", needsBearer: true }
        : null;
    case "/me":
      return method === "GET" ? { name: "me", needsBearer: true } : null;
    case "/stations":
      if (method === "GET") return { name: "stationsGet", needsBearer: true };
      if (method === "PUT") return { name: "stationsPut", needsBearer: true };
      return null;
    case "/health":
      if (method === "POST") return { name: "healthPost", needsBearer: true };
      if (method === "GET") return { name: "healthGet", needsBearer: true };
      return null;
    case "/killswitches":
      if (method === "GET") {
        return { name: "killswitchesGet", needsBearer: true };
      }
      if (method === "PUT") {
        return { name: "killswitchesPut", needsBearer: true };
      }
      return null;
  }

  const action = ROSTER_ACTION.exec(pathname);
  if (!action) {
    return null;
  }
  const [, rawId, verb] = action;
  const expectedMethod = verb === "role" ? "PUT" : "POST";
  if (method !== expectedMethod) {
    return null;
  }
  const deviceId = decodeSegment(rawId);
  if (deviceId === null) {
    return null;
  }
  const name: RouteName =
    verb === "approve"
      ? "rosterApprove"
      : verb === "revoke"
        ? "rosterRevoke"
        : "rosterRole";
  return { name, needsBearer: true, deviceId };
}

/**
 * The bearer token of a request, or null when it carries none. The Durable
 * Object's authenticate() and the front Worker both read it through here, so
 * "no usable bearer" means the same thing in both places.
 */
export function bearerToken(request: Request): string | null {
  const header = request.headers.get("Authorization");
  if (!header?.startsWith("Bearer ")) {
    return null;
  }
  const token = header.slice("Bearer ".length).trim();
  return token === "" ? null : token;
}
