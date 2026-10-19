// Vite's `?raw` import, used by ask.units.test.ts to compare the two Wrangler files.
declare module "*?raw" {
  const content: string;
  export default content;
}
