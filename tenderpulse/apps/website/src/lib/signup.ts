// TP-034: signup logic, kept framework-free and independent of the DOM so it can be unit
// tested directly (see tests/signup.test.ts) without needing to render an Astro page or a real
// browser. The <script> tags in src/pages/signup.astro only wire these functions to form
// elements and DOM events; they contain no business logic themselves.

/** Base URL of the TenderPulse API, e.g. "http://localhost:8080" (no trailing slash expected). */
export function apiBaseUrl(): string {
  // import.meta.env is inlined at build time by Astro/Vite; guarded for the (non-Vite) vitest
  // environment where import.meta.env may not define this key at all.
  return (import.meta as any).env?.PUBLIC_API_BASE_URL || "http://localhost:8080";
}

/** Minimal client-side email shape check — good enough to block obviously-invalid input before
 *  it reaches the server; the server (`@Email` on RegisterRequest/ProSubscribeRequest) remains
 *  the source of truth. */
export function isValidEmail(email: string): boolean {
  const trimmed = email.trim();
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed);
}

export interface SignupResult {
  ok: boolean;
  status: number;
  body: unknown;
}

async function postJson(path: string, payload: Record<string, unknown>, fetchImpl: typeof fetch): Promise<SignupResult> {
  const response = await fetchImpl(`${apiBaseUrl()}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  let body: unknown = null;
  try {
    body = await response.json();
  } catch {
    // No/invalid JSON body — leave body as null, callers only branch on `ok`/`status`.
  }

  return { ok: response.ok, status: response.status, body };
}

/** Free tier: POST /api/v1/subscribers with tier: FREE (self-declared, unauthenticated — no
 *  payment). */
export function submitFreeSignup(email: string, fetchImpl: typeof fetch = fetch): Promise<SignupResult> {
  return postJson("/api/v1/subscribers", { email, tier: "FREE" }, fetchImpl);
}

/** Pro tier: POST /api/v1/subscribers/pro after a real PayPal subscription has been approved
 *  (see src/lib/paypal.ts). The tier is never set client-side — the backend independently
 *  verifies `paypalSubscriptionId` against PayPal's API before upgrading anyone to PAID. */
export function submitProSignup(
  email: string,
  paypalSubscriptionId: string,
  fetchImpl: typeof fetch = fetch
): Promise<SignupResult> {
  return postJson("/api/v1/subscribers/pro", { email, paypalSubscriptionId }, fetchImpl);
}
