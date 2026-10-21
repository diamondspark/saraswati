import type { AuthResult, FamilyFeatureKey } from "./FamilyHub";
import type { MirrorRow } from "./feedback/githubIssue";

export type { AuthResult };

/**
 * What a route module may use of the hub: its storage, the shared fixed-window
 * limiter, the kill switches, the roster check and the clock. Handlers get this instead of the
 * FamilyHub itself, so they can be run against a stand-in.
 */
export interface HubContext {
  sql: SqlStorage;
  /** True means the call is allowed (see FamilyHub.rateLimit). */
  rateLimit(key: string, windowSeconds: number, max: number): boolean;
  featureEnabled(key: FamilyFeatureKey): boolean;
  /**
   * True while the device is on the roster as approved. A route that awaits its body asks
   * again afterwards, because a revoke can land in between.
   */
  isApproved(deviceId: string): boolean;
  /** Epoch milliseconds. */
  now(): number;
  /** The new GitHub issue's URL for a stored feedback item, or null. Never throws. */
  mirrorFeedback(row: MirrorRow): Promise<string | null>;
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
