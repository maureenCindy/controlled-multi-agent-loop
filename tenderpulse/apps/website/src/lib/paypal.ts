// TP-034: PayPal Subscription Button wiring, split out from src/pages/signup.astro so the
// createSubscription/onApprove logic can be unit tested against a stubbed PayPal SDK (see
// tests/paypal.test.ts) — a real end-to-end PayPal approval cannot be exercised here without
// real PayPal sandbox credentials (see README "PayPal setup required").
import { submitProSignup, type SignupResult } from "./signup";

/** Builds the PayPal JS SDK <script> src for a given Client ID. `vault=true&intent=subscription`
 *  are required for the Subscriptions Buttons flow used here. */
export function payPalSdkUrl(clientId: string): string {
  const params = new URLSearchParams({
    "client-id": clientId,
    vault: "true",
    intent: "subscription",
  });
  return `https://www.paypal.com/sdk/js?${params.toString()}`;
}

/** Loads the PayPal JS SDK by injecting a <script> tag, resolving with the resulting global
 *  `paypal` object once it's ready. No-ops (resolves immediately) if it's already loaded. */
export function loadPayPalSdk(clientId: string, doc: Document = document): Promise<any> {
  return new Promise((resolve, reject) => {
    const existing = (globalThis as any).paypal;
    if (existing) {
      resolve(existing);
      return;
    }

    const script = doc.createElement("script");
    script.src = payPalSdkUrl(clientId);
    script.async = true;
    script.onload = () => resolve((globalThis as any).paypal);
    script.onerror = () => reject(new Error("Failed to load the PayPal SDK."));
    doc.head.appendChild(script);
  });
}

export interface PayPalButtonHandlers {
  onSuccess: (result: SignupResult) => void;
  onError: (message: string) => void;
}

/**
 * Renders a PayPal Subscription button into `containerSelector`.
 *
 * - `createSubscription` calls PayPal's `actions.subscription.create({ plan_id: planId })` —
 *   the plan itself must already exist in the PayPal dashboard (PUBLIC_PAYPAL_PLAN_ID).
 * - `onApprove` receives the resulting subscription ID and POSTs `{ email, paypalSubscriptionId }`
 *   to `POST /api/v1/subscribers/pro`. The tier is set to PAID only after the backend
 *   independently verifies that subscription with PayPal's own API — this function never claims
 *   success/PAID status on the frontend's say-so.
 *
 * Known limitation (see README and the issue): there is no webhook listener yet, so a
 * cancellation or failed renewal on PayPal's side later will not automatically downgrade the
 * subscriber. That's a deliberate MVP trade-off, not handled here.
 */
export function renderPayPalSubscribeButton(
  paypalSdk: any,
  containerSelector: string,
  planId: string,
  getEmail: () => string,
  handlers: PayPalButtonHandlers,
  submitPro: typeof submitProSignup = submitProSignup
) {
  return paypalSdk
    .Buttons({
      createSubscription: (_data: unknown, actions: any) =>
        actions.subscription.create({ plan_id: planId }),
      onApprove: async (data: { subscriptionID: string }) => {
        try {
          const result = await submitPro(getEmail(), data.subscriptionID);
          if (result.ok) {
            handlers.onSuccess(result);
          } else {
            handlers.onError(extractErrorMessage(result));
          }
        } catch (err) {
          handlers.onError(
            err instanceof Error
              ? err.message
              : "Something went wrong verifying your PayPal subscription."
          );
        }
      },
      onError: () => {
        handlers.onError("PayPal was unable to complete the subscription. Please try again.");
      },
    })
    .render(containerSelector);
}

function extractErrorMessage(result: SignupResult): string {
  const body = result.body as { message?: string } | null;
  return body?.message ?? "We could not verify your PayPal subscription. Please contact support.";
}
