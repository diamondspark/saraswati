// The worker project has no @types/node (Workers code must not reach for Node).
// record-traces.ts is the one Node-run file, and it needs only these few APIs.
declare module "node:fs/promises" {
  export function readFile(path: string, encoding: "utf8"): Promise<string>;
  export function writeFile(path: string, data: string): Promise<void>;
}
declare module "node:url" {
  export function fileURLToPath(url: string | URL): string;
}
declare module "node:path" {
  export function join(...parts: string[]): string;
}
interface ImportMeta {
  url: string;
}
