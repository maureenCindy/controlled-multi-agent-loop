/// <reference path="../.astro/types.d.ts" />
/// <reference types="astro/client" />

interface ImportMetaEnv {
  /** Base URL of the TenderPulse API (no trailing slash), e.g. http://localhost:8080 in dev. */
  readonly PUBLIC_API_BASE_URL: string;
  /** PayPal REST app Client ID (sandbox or live) used to load the PayPal JS SDK. Placeholder until real credentials are provided — see README. */
  readonly PUBLIC_PAYPAL_CLIENT_ID: string;
  /** PayPal Billing Plan ID the Pro subscription button subscribes to. Placeholder until a real Plan is created in the PayPal dashboard — see README. */
  readonly PUBLIC_PAYPAL_PLAN_ID: string;
  /** Feature flag: show marketing copy about the WhatsApp notification channel for Paid
   *  subscribers. Must be the exact string "true" to enable; defaults off. See
   *  src/lib/featureFlags.ts and .env.example. */
  readonly PUBLIC_WHATSAPP_ENABLED: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
