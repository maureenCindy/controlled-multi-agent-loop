// Build-time feature flags for the marketing site. This is intentionally a single boolean
// constant, not a feature-flag service — the site is static Astro with no existing flagging
// infrastructure, and a platform like LaunchDarkly/GrowthBook would be disproportionate for one
// on/off switch. See tenderpulse/apps/website/.env.example for the backing env var.

/** Pure resolution logic, split out so it can be unit tested directly against arbitrary raw
 *  values (see tests/featureFlags.test.ts) without depending on how/whether a given test runner
 *  populates `import.meta.env`. Returns `true` only for the exact literal string "true"; any
 *  other value (undefined/unset, "false", "1", "TRUE", typos, etc.) resolves to `false` so a
 *  malformed or misconfigured env var can never accidentally enable a promise the product can't
 *  yet keep. */
export function resolveWhatsAppFlag(rawValue: string | undefined): boolean {
  return rawValue === "true";
}

/** Whether marketing copy about the (in-progress) WhatsApp notification channel for Paid
 *  subscribers should be shown. Backed by PUBLIC_WHATSAPP_ENABLED, a build-time env var — Astro
 *  only exposes PUBLIC_-prefixed vars to the browser/build. See resolveWhatsAppFlag() above for
 *  the exact matching rule.
 *
 *  import.meta.env is inlined at build time by Astro/Vite; guarded (as any) for the (non-Vite)
 *  vitest environment where import.meta.env may not define this key at all — same pattern as
 *  apiBaseUrl() in ./signup.ts. */
export const WHATSAPP_NOTIFICATIONS_ENABLED: boolean = resolveWhatsAppFlag(
  (import.meta as any).env?.PUBLIC_WHATSAPP_ENABLED
);
