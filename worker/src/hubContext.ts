import type { AuthResult, FamilyFeatureKey } from "./FamilyHub";

export type { AuthResult };

/**
 * What a route module may use of the hub: its storage, the shared fixed-window
 * limiter, the kill switches and the clock. Handlers get this instead of the
 * FamilyHub itself, so they can be run against a stand-in.
 */
export interface HubContext {
  sql: SqlStorage;
  /** True means the call is allowed (see FamilyHub.rateLimit). */
  rateLimit(key: string, windowSeconds: number, max: number): boolean;
  featureEnabled(key: FamilyFeatureKey): boolean;
  /** Epoch milliseconds. */
  now(): number;
}

/**
 * `auth` is what FamilyHub.authenticate() found for the request. A route that
 * is open to a device or the admin is called with either role; a device-only
 * route is called with a device.
 */
export type RouteHandler = (
  ctx: HubContext,
  request: Request,
  auth: AuthResult,
) => Promise<Response>;
