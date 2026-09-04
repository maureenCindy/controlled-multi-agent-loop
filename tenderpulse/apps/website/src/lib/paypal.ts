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
  /**
   * TP-071: called once `onClick`'s email validation has passed and PayPal's checkout popup is
   * about to open (i.e. right before `actions.resolve()`). Callers use this to lock the email
   * input for the duration of the popup, closing the residual window where a user could edit or
   * clear the email *after* the `onClick` gate but *before* `onApprove`/`onError` settles — which
   * could otherwise produce a real, ACTIVE PayPal subscription with a stale/blank email attached.
   * Optional so existing callers/tests that don't care about the lock/unlock UX keep working.
   */
  onCheckoutStart?: () => void;
  onSuccess: (result: SignupResult) => void;
  onError: (message: string) => void;
}

/**
 * Renders a PayPal Subscription button into `containerSelector`.
 *
 * - `onClick` calls `getEmail()` *before* PayPal's checkout popup is allowed to open. If it
 *   throws (invalid/empty email — see `getEmail`'s contract below), `actions.reject()` stops the
 *   click dead: no popup, no subscription is ever created on PayPal's side. This is what
 *   actually blocks Pro checkout on a bad email — relying on `createSubscription` or `onApprove`
 *   alone is too late, since by the time either of those fire the user has already completed
 *   real PayPal checkout and PayPal has already created an ACTIVE subscription. Once that's
 *   happened there is no way to "undo" it from here, and — since there's no webhook listener
 *   yet (see below) — no way to reconcile it later either, so gating at `onClick` is load-bearing,
 *   not a nicety.
 * - `createSubscription` calls PayPal's `actions.subscription.create({ plan_id: planId })` —
 *   the plan itself must already exist in the PayPal dashboard (PUBLIC_PAYPAL_PLAN_ID).
 * - `onApprove` receives the resulting subscription ID and POSTs `{ email, paypalSubscriptionId }`
 *   to `POST /api/v1/subscribers/pro`. The tier is set to PAID only after the backend
 *   independently verifies that subscription with PayPal's own API — this function never claims
 *   success/PAID status on the frontend's say-so. `getEmail()` is called again here (defense in
 *   depth, not the primary guard) in case the email field is somehow cleared between `onClick`
 *   and approval.
 *
 * `getEmail` is expected to both validate and return the current email: return the email string
 * when valid, or throw when it isn't (see src/pages/signup.astro's usage) — `onClick` and
 * `onApprove` both treat a throw as "invalid, do not proceed."
 *
 * TP-071: `handlers.onCheckoutStart` (if provided) fires right after `onClick` resolves, i.e.
 * once the popup is actually opening — callers use it to lock the email input for the duration
 * of the popup. `handlers.onSuccess`/`handlers.onError` (already called on every settle path —
 * `onApprove` success, `onApprove` failure, and the SDK's own `onError`/cancel) are where callers
 * re-enable it, so no separate "checkout ended" hook is needed here.
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
      onClick: (_data: unknown, actions: any) => {
        try {
          getEmail();
        } catch {
          return actions.reject();
        }
        // TP-071: lock the email input now that the popup is actually about to open, so it
        // can't be edited/cleared out from under `onApprove` while checkout is in flight.
        handlers.onCheckoutStart?.();
        return actions.resolve();
      },
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
