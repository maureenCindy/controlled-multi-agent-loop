# TenderPulse marketing site (TP-034)

Public marketing site: Home, How it works, Testimonials (honest "early access" placeholder — no
fabricated quotes; real ones land via #36), and Signup (Free direct, Pro via a real PayPal
subscription). Built with [Astro](https://astro.build), static output, no framework/CMS.

## Local development

```bash
cd tenderpulse/apps/website
npm install
cp .env.example .env   # then edit .env — see "Environment variables" below
npm run dev             # http://localhost:4321
```

```bash
npm run build            # static build to dist/
npm run preview          # serve the built dist/ locally
npm run test              # vitest unit tests (see "What's tested" below)
```

The site expects the API (`tenderpulse/apps/api`) to be running and reachable at
`PUBLIC_API_BASE_URL` (defaults to `http://localhost:8080`), and that API's
`WEBSITE_ALLOWED_ORIGINS` env var must include this site's origin (`http://localhost:4321` in
dev) — see `tenderpulse/apps/api/.env.example`.

## Environment variables

See `.env.example` for the full list with comments. Summary:

| Variable | Required | Notes |
|---|---|---|
| `PUBLIC_API_BASE_URL` | No (defaults to `http://localhost:8080`) | Backend origin the signup form POSTs to. |
| `PUBLIC_PAYPAL_CLIENT_ID` | **Yes, for Pro signup to work** | PayPal Developer app Client ID. **Placeholder until real credentials are provided.** |
| `PUBLIC_PAYPAL_PLAN_ID` | **Yes, for Pro signup to work** | PayPal recurring Billing Plan ID. **Placeholder until a real Plan is created.** Must match the backend's `PAYPAL_PLAN_ID`. |

Astro only exposes env vars prefixed `PUBLIC_` to client-side/browser code — this is Astro's own
convention, not something added here. All three are build-time (baked into the static output at
`npm run build` time), not runtime-configurable after the fact.

### PayPal setup required before Pro signup works

**`PUBLIC_PAYPAL_CLIENT_ID` and `PUBLIC_PAYPAL_PLAN_ID` are placeholder values in
`.env.example`.** Until they're replaced with real PayPal Developer Dashboard credentials (and a
real recurring Billing Plan is created there), the Pro signup panel will render a
"Pro signup is not configured yet" message instead of a working PayPal button — Free signup is
unaffected. See `.env.example` for the exact setup steps.

## Signup flow

- **Free**: email only. Directly POSTs `{ email, tier: "FREE" }` to `POST /api/v1/subscribers`
  (unauthenticated, no payment). Success/error states are shown inline.
- **Pro**: embeds a real PayPal Subscription Button (PayPal JS SDK,
  `vault=true&intent=subscription`). On approval, PayPal's `onApprove` callback hands back a
  subscription ID; the page POSTs `{ email, paypalSubscriptionId }` to
  `POST /api/v1/subscribers/pro`. **The tier is never set client-side** — that endpoint
  independently verifies the subscription against PayPal's own API before upgrading anyone to
  PAID (see `tenderpulse/apps/api`'s `SubscriberService.registerPro`, TP-042).

  **Known limitation (deliberate MVP trade-off, tracked — not silently accepted as solved):**
  there is no webhook listener yet, so if a PayPal subscription is later cancelled or a renewal
  fails on PayPal's side, the subscriber is **not** automatically downgraded from PAID. See the
  comment near the signup code in `src/pages/signup.astro` and `src/lib/paypal.ts`.

Logic lives in `src/lib/signup.ts` (email validation, POST calls) and `src/lib/paypal.ts` (PayPal
SDK loading, button rendering, `onApprove` wiring) — both framework-free so they're unit
testable without a browser. `src/pages/signup.astro`'s inline `<script>` only wires these to DOM
elements/events.

## What's tested

- `npm run build` — the site builds successfully (CI-enforced).
- `npm run test` (vitest) — unit tests for `src/lib/signup.ts` and `src/lib/paypal.ts`:
  - Email validation (valid/invalid/empty/whitespace).
  - `submitFreeSignup`/`submitProSignup` POST the right URL, method, and body, and surface both
    success and non-ok responses without throwing.
  - `renderPayPalSubscribeButton` against a **stubbed PayPal SDK**: the button is rendered into
    the right container, `createSubscription` requests the configured plan ID, and `onApprove`
    calls the backend with the approved subscription ID + current email and routes
    success/failure to the right UI callback.

**Not tested (documented limitation, not an oversight):** a real end-to-end PayPal approval —
that requires real PayPal sandbox credentials, which have not been provided (see "PayPal setup
required" above). The stubbed-SDK tests above are the closest feasible substitute per the issue's
own test case 5.

## Deliberately out of scope (see issue #40)

Subscriber portal/login, payment processing beyond what's described above, a pricing page beyond
the Free/Pro choice on signup, blog/CMS, and actual hosting/deployment configuration.
