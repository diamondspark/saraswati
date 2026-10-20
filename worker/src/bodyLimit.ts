/** The biggest legitimate body is a six-station list: well under 2 KiB. */
export const MAX_BODY_BYTES = 8 * 1024;

function tooLarge(): Response {
  return new Response(JSON.stringify({ error: "request body too large" }), {
    status: 413,
    headers: { "content-type": "application/json" },
  });
}

/**
 * The 413 for a request whose body is not provably small, or null when the
 * request may go on. Checked in the front Worker, before the Durable Object is
 * woken or any byte of the body is read, so an oversized or endless upload
 * costs the hub nothing.
 *
 *  - A declared Content-Length must be plain digits and at most 8 KiB.
 *  - With no Content-Length, a request that has a body (a chunked upload) is
 *    refused, because its size cannot be known up front. A method that carries
 *    no body (GET, HEAD) or a request that really has none passes.
 */
export function bodyLimitResponse(request: Request): Response | null {
  const declared = request.headers.get("content-length");
  if (declared !== null) {
    return /^\d{1,9}$/.test(declared) && Number(declared) <= MAX_BODY_BYTES
      ? null
      : tooLarge();
  }
  if (
    request.method === "GET" ||
    request.method === "HEAD" ||
    request.body === null
  ) {
    return null;
  }
  return tooLarge();
}
