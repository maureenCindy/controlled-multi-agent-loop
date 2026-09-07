# TenderBell Subscription, Alert Preferences, and PayPal Billing Specification

**Status:** Proposed for implementation<br />
**Date:** 2026-09-08<br />
**Product:** TenderBell<br />
**Backend:** Spring Boot, Kotlin, PostgreSQL, Flyway<br />
**Frontend target:** React + TypeScript<br />
**Payment provider:** PayPal Subscriptions

---

## 1. Purpose

This specification defines how TenderBell manages the complete subscriber lifecycle for the
Free, Pro, and Max plans, including:

- initial subscription;
- email and WhatsApp preferences;
- passwordless self-service management;
- payment approval and recurring billing through PayPal;
- payment failures and recovery;
- pausing alerts;
- cancelling a paid subscription;
- downgrading to Free;
- unsubscribing from individual channels or all alerts;
- re-subscribing and reactivating paid service;
- PayPal webhook verification and reconciliation;
- plan entitlements and access enforcement.

The central design rule is that **billing state and alert-delivery state are separate**. A user
must be able to pause email or disable WhatsApp without accidentally cancelling billing. Likewise,
cancelling a paid plan must not be presented as equivalent to unsubscribing from email.

This is an implementation specification, not a final legal or privacy policy. The consent,
cancellation, refund, retention, and data-protection language must receive appropriate legal
review before broad commercial launch.

---

## 2. Architecture and Scope Assumptions

This specification assumes a greenfield implementation with the following components:

- a React and TypeScript web application;
- a Spring Boot and Kotlin REST API;
- PostgreSQL for application, subscription, preference, and webhook state;
- Flyway for versioned database schema management;
- PayPal Subscriptions for Pro and Max recurring payments;
- an email provider that supports custom unsubscribe headers;
- a WhatsApp Business messaging provider for optional paid alerts;
- scheduled tender ingestion, matching, reminders, and weekly digest jobs;
- passwordless magic-link authentication or another secure authentication mechanism.

TenderBell owns customer accounts, entitlements, alert preferences, notification delivery, and
the Market Insights Portal. PayPal owns payment approval and recurring payment collection. The
backend reconciles the two systems through verified webhooks and scheduled provider lookups.

---

## 3. Product Plans and Entitlements

| Capability | Free | Pro | Max |
|---|---:|---:|---:|
| Monthly price | USD 0 | USD 5 | USD 30 |
| Tender category matches | 1 | 1 | Unlimited |
| Newly published tender alerts | Weekly Monday email | After each successful monitoring run | After each successful monitoring run |
| Closing-soon reminders | No | Yes | Yes, inherited from Pro |
| Tender-award notice alerts | No | Yes | Yes, inherited from Pro |
| Email delivery | Yes | Yes | Yes |
| Optional WhatsApp delivery | No | Yes | Yes |
| TenderBell Market Insights Portal | No | No | Yes |
| Market Opportunity Insights | No | No | Yes |

### 3.1 Monitoring and product-copy accuracy

Tender ingestion frequency must be configurable. If production monitoring runs three times per
day, paid alerts are delivered after a monitoring run finds a match; they are not technically
instantaneous. Product copy should say either:

- “Alerts after each TenderBell monitoring run”; or
- “Timely matched alerts, up to three times per day.”

Do not promise literal real-time delivery until the underlying source-monitoring architecture
supports it.

### 3.2 Required delivery schedule

- Free: one digest every Monday at 08:00 Africa/Johannesburg, excluding empty digests.
- Pro and Max: alerts after each successful monitoring and matching cycle.
- Reminder and award notifications: paid plans only.
- All schedules and time zones must be configuration-driven and covered by tests.

---

## 4. User-Facing Management Model

Every subscriber receives access to a secure **Manage TenderBell** experience.

This lightweight management experience is not the Max portal. It exists so all users can exercise
basic service, privacy, and billing controls.

### 4.1 Free management page

The Free page displays:

- current plan: Free;
- current tender category;
- weekly delivery schedule;
- email status;
- last alert or digest sent;
- change category action;
- pause alerts action;
- unsubscribe action;
- upgrade to Pro or Max actions.

### 4.2 Pro management page

The Pro page displays:

- current plan and monthly price;
- PayPal billing status;
- next expected billing date, when supplied by PayPal;
- one tender category;
- email and WhatsApp settings;
- pause alert delivery;
- resume alert delivery;
- resolve payment problem;
- cancel Pro;
- upgrade to Max;
- downgrade-to-Free outcome when cancelling.

### 4.3 Max management

Max users manage the same controls inside the TenderBell Market Insights Portal. Email links may
deep-link directly to the portal's subscription or alert-preferences screen.

Portal access does not replace unsubscribe controls in the email footer. Every subscribed email
must retain a clear preference-management and unsubscribe path.

---

## 5. Terminology and User-Action Semantics

| Action | Billing effect | Alert effect | Authentication |
|---|---|---|---|
| Manage alerts | None | User edits category/channels | Magic link or authenticated session |
| Pause alerts | None | Stops selected alert delivery temporarily | Authenticated confirmation |
| Resume alerts | None | Restarts delivery | Authenticated confirmation |
| Disable WhatsApp | None | WhatsApp stops; email remains | Authenticated confirmation or verified WhatsApp opt-out |
| Unsubscribe from email | None by itself | Email stops | One-click token; no login |
| Stop all alerts | None by itself | Email and WhatsApp stop | Confirmed preference action |
| Cancel paid plan | Stops future PayPal billing | Paid access ends at paid-through time | Authenticated confirmation |
| Downgrade to Free | Stops PayPal billing | Weekly email continues only if explicitly selected | Authenticated confirmation |
| Close account | Stops billing and delivery | Account enters deletion workflow | Strong confirmation |

The UI must never label “unsubscribe” as “cancel subscription,” or imply that disabling email
stops PayPal billing.

---

## 6. Subscription and Alert State Models

### 6.1 Plan

Define the plan enum as:

```kotlin
enum class SubscriptionPlan {
    FREE,
    PRO,
    MAX
}
```

Do not use a generic `PAID` value because it cannot distinguish Pro entitlements from Max
entitlements.

### 6.2 Billing status

```kotlin
enum class BillingStatus {
    NONE,                 // Free or never entered PayPal checkout
    APPROVAL_PENDING,     // PayPal subscription created but not active
    ACTIVE,
    PAYMENT_FAILED,
    SUSPENDED,
    CANCELLATION_PENDING, // TenderBell is resolving cancellation state
    CANCELLED,
    EXPIRED
}
```

### 6.3 Alert status

```kotlin
enum class AlertStatus {
    ACTIVE,
    PAUSED,
    UNSUBSCRIBED
}
```

Channel consent remains independent:

```text
emailEnabled
whatsappEnabled
whatsappNumber
whatsappConsentAt
emailConsentAt
```

### 6.4 Effective entitlements

Entitlements must be calculated by a single backend service, for example
`SubscriptionEntitlementService`. Controllers, React components, scheduled jobs, and notification
senders must not each invent their own plan rules.

Paid entitlements are granted only when:

```text
billingStatus == ACTIVE
OR
(billingStatus in [PAYMENT_FAILED, CANCELLED] AND paidAccessUntil > now)
```

Alert sending additionally requires:

```text
subscriber.active
AND alertStatus == ACTIVE
AND the selected channel is enabled and consented
```

---

## 7. Proposed Data Model

### 7.1 Subscriber model

The `subscribers` table should contain:

| Column | Type | Notes |
|---|---|---|
| `plan` | varchar | `FREE`, `PRO`, or `MAX` |
| `alert_status` | varchar | `ACTIVE`, `PAUSED`, or `UNSUBSCRIBED` |
| `email_enabled` | boolean | Whether alert emails may be delivered |
| `email_consent_at` | timestamptz | Evidence of opt-in or resubscription |
| `alerts_paused_until` | timestamptz nullable | Optional automatic resumption time |
| `updated_at` | timestamptz | Audit support |

### 7.2 Billing subscriptions

Create `billing_subscriptions`:

| Column | Type | Constraints / purpose |
|---|---|---|
| `id` | uuid | Primary key |
| `subscriber_id` | uuid | Foreign key to subscriber |
| `provider` | varchar | `PAYPAL` for MVP |
| `provider_subscription_id` | varchar | Unique PayPal subscription ID |
| `provider_plan_id` | varchar | PayPal Pro or Max plan ID |
| `plan` | varchar | `PRO` or `MAX` |
| `status` | varchar | TenderBell billing status |
| `provider_status` | varchar | Last raw PayPal status |
| `payer_id` | varchar nullable | PayPal payer identifier |
| `payer_email` | varchar nullable | For reconciliation; treat as personal data |
| `next_billing_time` | timestamptz nullable | From PayPal billing details |
| `paid_access_until` | timestamptz nullable | TenderBell entitlement cutoff |
| `last_payment_at` | timestamptz nullable | Last successful recurring payment |
| `last_payment_amount` | numeric nullable | Reconciliation and support |
| `currency_code` | varchar(3) | `USD` |
| `cancel_requested_at` | timestamptz nullable | Audit trail |
| `cancelled_at` | timestamptz nullable | Confirmed cancellation |
| `created_at` | timestamptz | Creation time |
| `updated_at` | timestamptz | Last local state change |
| `version` | bigint | Optimistic-lock version |

Retain billing history. Do not overwrite an old cancelled PayPal subscription ID when a user
subscribes again; create a new billing-subscription row and link it to the same subscriber.

### 7.3 Webhook inbox

Create `paypal_webhook_events`:

| Column | Type | Constraints / purpose |
|---|---|---|
| `id` | uuid | Internal primary key |
| `paypal_event_id` | varchar | Unique idempotency key |
| `event_type` | varchar | PayPal event name |
| `resource_id` | varchar nullable | Subscription or sale identifier |
| `payload` | jsonb | Original verified payload |
| `verification_status` | varchar | `SUCCESS` or `FAILURE` |
| `processing_status` | varchar | `RECEIVED`, `PROCESSED`, `IGNORED`, `FAILED` |
| `received_at` | timestamptz | Receipt timestamp |
| `processed_at` | timestamptz nullable | Completion timestamp |
| `processing_error` | text nullable | Sanitized failure detail |

The unique `paypal_event_id` constraint is the final protection against duplicate webhook
delivery.

### 7.4 Preference audit

Create `subscriber_preference_events` or an equivalent append-only audit table containing:

- subscriber ID;
- action (`EMAIL_OPT_IN`, `EMAIL_OPT_OUT`, `WHATSAPP_OPT_OUT`, `PAUSE`, `RESUME`);
- source (`SIGNUP`, `EMAIL_LINK`, `MANAGE_PAGE`, `WHATSAPP`, `ADMIN`);
- timestamp;
- IP address and user agent only if justified by the privacy policy;
- related token ID where applicable.

### 7.5 Flyway schema plan

Use focused, versioned Flyway migrations to:

1. create subscribers and preference fields;
2. create interest profiles and category limits;
3. create billing subscriptions;
4. create the PayPal webhook inbox;
5. create the preference audit log;
6. add foreign keys, uniqueness constraints, and operational indexes.

Production databases must use `ddl-auto: validate`; Flyway, not Hibernate automatic updates,
owns schema changes.

---

## 8. PayPal Environment Setup

### 8.1 Create accounts and REST application

1. Create or verify the TenderBell PayPal Business account.
2. Sign in to the PayPal Developer Dashboard.
3. Under **Apps & Credentials**, use Sandbox first.
4. Create a TenderBell REST application.
5. Record the sandbox Client ID and Secret in the deployment secret manager.
6. Use a sandbox Business account as the merchant and a sandbox Personal account as the buyer.
7. Never commit the Client Secret, webhook ID, access token, payer credentials, or live plan IDs.

The browser may receive the PayPal Client ID and plan IDs. It must never receive the Client
Secret.

### 8.2 Obtain an OAuth access token

PayPal REST calls use the OAuth 2.0 client-credentials flow:

```bash
curl -X POST "https://api-m.sandbox.paypal.com/v1/oauth2/token" \
  -u "PAYPAL_CLIENT_ID:PAYPAL_CLIENT_SECRET" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials"
```

The Kotlin PayPal client must cache and reuse the access token until shortly before `expires_in`,
then refresh it. Access tokens must never be logged.

### 8.3 Create one PayPal product

Create one service product for TenderBell:

```bash
curl -X POST "https://api-m.sandbox.paypal.com/v1/catalogs/products" \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "PayPal-Request-Id: UNIQUE_IDEMPOTENCY_VALUE" \
  -d '{
    "name": "TenderBell Subscriptions",
    "description": "Tender alerts and government procurement market intelligence",
    "type": "SERVICE",
    "category": "SOFTWARE",
    "home_url": "https://YOUR_PUBLIC_DOMAIN/"
  }'
```

Store the returned `PROD-...` identifier as an operational deployment value.

### 8.4 Create the Pro plan

```bash
curl -X POST "https://api-m.sandbox.paypal.com/v1/billing/plans" \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "PayPal-Request-Id: UNIQUE_PRO_PLAN_REQUEST_ID" \
  -d '{
    "product_id": "PAYPAL_PRODUCT_ID",
    "name": "TenderBell Pro Monthly",
    "description": "One tender category with timely tender, reminder, and award alerts",
    "status": "ACTIVE",
    "billing_cycles": [{
      "frequency": {"interval_unit": "MONTH", "interval_count": 1},
      "tenure_type": "REGULAR",
      "sequence": 1,
      "total_cycles": 0,
      "pricing_scheme": {"fixed_price": {"value": "5.00", "currency_code": "USD"}}
    }],
    "payment_preferences": {
      "auto_bill_outstanding": true,
      "setup_fee": {"value": "0", "currency_code": "USD"},
      "setup_fee_failure_action": "CONTINUE",
      "payment_failure_threshold": 3
    }
  }'
```

Store the returned `P-...` ID as `PAYPAL_PRO_PLAN_ID`.

### 8.5 Create the Max plan

Create a second plan using the same product and configuration, changing:

```json
{
  "name": "TenderBell Max Monthly",
  "description": "Unlimited category matching and TenderBell Market Insights Portal access",
  "pricing_scheme": {
    "fixed_price": {"value": "30.00", "currency_code": "USD"}
  }
}
```

The `pricing_scheme` belongs inside the regular billing cycle as shown in the Pro request. Store
the returned plan ID as `PAYPAL_MAX_PLAN_ID`.

### 8.6 Register the webhook

Register a public HTTPS endpoint:

```text
POST https://YOUR_API_DOMAIN/api/v1/billing/paypal/webhooks
```

Subscribe at minimum to:

- `BILLING.SUBSCRIPTION.CREATED`
- `BILLING.SUBSCRIPTION.ACTIVATED`
- `BILLING.SUBSCRIPTION.UPDATED`
- `BILLING.SUBSCRIPTION.PAYMENT.FAILED`
- `BILLING.SUBSCRIPTION.SUSPENDED`
- `BILLING.SUBSCRIPTION.CANCELLED`
- `BILLING.SUBSCRIPTION.EXPIRED`
- `PAYMENT.SALE.COMPLETED`
- `PAYMENT.SALE.REFUNDED`
- `PAYMENT.SALE.REVERSED`

Store the PayPal webhook ID as `PAYPAL_WEBHOOK_ID`. It is required for signature verification.

### 8.7 Sandbox-to-live checklist

Sandbox and live resources are separate. For production:

1. switch the Developer Dashboard to Live;
2. create or select the live REST application;
3. create the live product;
4. create separate live Pro and Max plans;
5. register the live webhook URL;
6. store live credentials and identifiers in the production secret manager;
7. set `PAYPAL_BASE_URL=https://api-m.paypal.com`;
8. perform a low-value controlled live subscription test;
9. verify the activation, recurring-payment, cancellation, and reconciliation paths;
10. never reuse sandbox IDs in live configuration.

---

## 9. Spring Boot and Kotlin Design

### 9.1 Configuration

Replace the single plan setting with explicit configuration:

```yaml
paypal:
  base-url: ${PAYPAL_BASE_URL:https://api-m.sandbox.paypal.com}
  client-id: ${PAYPAL_CLIENT_ID:}
  client-secret: ${PAYPAL_CLIENT_SECRET:}
  webhook-id: ${PAYPAL_WEBHOOK_ID:}
  plans:
    pro: ${PAYPAL_PRO_PLAN_ID:}
    max: ${PAYPAL_MAX_PLAN_ID:}
```

In non-development profiles, startup should fail closed if paid plans are enabled but any required
PayPal secret or plan ID is blank. Do not expose `client-secret` or `webhook-id` through a public
configuration endpoint.

### 9.2 Proposed package structure

```text
com.tenderpulse.billing
├── BillingController.kt
├── BillingDtos.kt
├── BillingService.kt
├── BillingSubscription.kt
├── BillingSubscriptionRepository.kt
├── SubscriptionEntitlementService.kt
└── SubscriptionReconciliationJob.kt

com.tenderpulse.paypal
├── PayPalClient.kt
├── PayPalDtos.kt
├── PayPalWebhookController.kt
├── PayPalWebhookService.kt
├── PayPalWebhookVerifier.kt
├── PayPalWebhookEvent.kt
└── PayPalWebhookEventRepository.kt

com.tenderpulse.preferences
├── PreferenceController.kt
├── PreferenceService.kt
├── PreferenceDtos.kt
└── PreferenceAuditEvent.kt
```

### 9.3 PayPal client operations

Implement a PayPal client with:

```kotlin
fun fetchSubscription(subscriptionId: String): PayPalSubscriptionResponse?
fun cancelSubscription(subscriptionId: String, reason: String)
fun suspendSubscription(subscriptionId: String, reason: String)
fun activateSubscription(subscriptionId: String, reason: String)
fun reviseSubscription(subscriptionId: String, targetPlanId: String): RevisionResponse
fun verifyWebhook(headers: PayPalWebhookHeaders, rawBody: String): Boolean
```

Use `UriComponentsBuilder.pathSegment` for every provider ID placed in a URL as a path-injection
defence. Apply connection and read timeouts. Log PayPal debug IDs and
internal correlation IDs, but never tokens, secrets, full webhook payloads at INFO level, or payer
details.

### 9.4 Webhook endpoint

```text
POST /api/v1/billing/paypal/webhooks
Content-Type: application/json
```

Required headers include:

- `PAYPAL-AUTH-ALGO`
- `PAYPAL-CERT-URL`
- `PAYPAL-TRANSMISSION-ID`
- `PAYPAL-TRANSMISSION-SIG`
- `PAYPAL-TRANSMISSION-TIME`

Processing sequence:

1. Read and retain the original raw request body.
2. Validate required headers and body-size limits.
3. Verify the signature before trusting the event.
4. Reject failed verification with a non-2xx response and security log.
5. Insert the verified event into the webhook inbox using PayPal event ID as a unique key.
6. If already stored, return `200 OK` without processing it twice.
7. Commit receipt promptly.
8. Process the event idempotently, preferably outside the HTTP transaction.
9. Fetch current subscription details from PayPal for state-changing subscription events.
10. Update billing state and entitlements transactionally.
11. Mark the inbox event processed.

For MVP, server-side postback to PayPal's
`POST /v1/notifications/verify-webhook-signature` endpoint is acceptable. Local cryptographic
verification can be introduced later, but must validate the certificate URL safely and use the
unmodified raw payload.

### 9.5 Event mapping

| PayPal event | TenderBell action |
|---|---|
| `BILLING.SUBSCRIPTION.CREATED` | Store pending subscription if known; do not grant paid access |
| `BILLING.SUBSCRIPTION.ACTIVATED` | Fetch details, verify plan and subscriber binding, set active plan |
| `PAYMENT.SALE.COMPLETED` | Record payment and extend `paidAccessUntil` |
| `BILLING.SUBSCRIPTION.PAYMENT.FAILED` | Set `PAYMENT_FAILED`, start/continue grace policy, notify user once per attempt policy |
| `BILLING.SUBSCRIPTION.SUSPENDED` | Set `SUSPENDED`; remove paid entitlements when grace expires |
| `BILLING.SUBSCRIPTION.CANCELLED` | Set `CANCELLED`; retain access only through `paidAccessUntil` |
| `BILLING.SUBSCRIPTION.EXPIRED` | Set `EXPIRED`; remove paid entitlements |
| `PAYMENT.SALE.REFUNDED` | Record refund; apply reviewed refund/access policy |
| `PAYMENT.SALE.REVERSED` | Flag for support/risk review and recalculate access |

Webhook ordering is not guaranteed. A stale event must not overwrite a newer provider state.
Fetching the current subscription after material state events is safer than trusting event order.

### 9.6 Reconciliation job

Run a scheduled reconciliation at least daily:

1. select locally active, failed, suspended, or cancellation-pending PayPal subscriptions;
2. fetch each current subscription from PayPal with bounded concurrency and rate limiting;
3. compare provider and local state;
4. repair safe discrepancies;
5. emit an operator alert for ambiguous cases;
6. record reconciliation time and outcome.

Webhooks provide timely changes; reconciliation repairs missed or delayed delivery.

---

## 10. Backend API Contract

All subscriber-owned endpoints require an authenticated session or bearer token and must enforce
that the authenticated subject owns the requested subscriber resource, unless explicitly marked
public.

### 10.1 Public billing configuration

```http
GET /api/v1/billing/public-config
```

```json
{
  "provider": "PAYPAL",
  "clientId": "PUBLIC_PAYPAL_CLIENT_ID",
  "currency": "USD",
  "plans": {
    "PRO": {"paypalPlanId": "P-...", "amount": "5.00"},
    "MAX": {"paypalPlanId": "P-...", "amount": "30.00"}
  }
}
```

This response is display/bootstrap configuration only. The backend must still map the returned
PayPal subscription to its configured plan ID during confirmation.

### 10.2 Confirm approved subscription

```http
POST /api/v1/billing/paypal/subscriptions/confirm
Authorization: Bearer SUBSCRIBER_TOKEN
```

```json
{
  "paypalSubscriptionId": "I-...",
  "requestedPlan": "PRO"
}
```

The backend must:

- fetch the subscription directly from PayPal;
- require `ACTIVE` status before final activation;
- require the expected configured plan ID;
- prevent one PayPal subscription from linking to multiple subscribers;
- verify subscriber ownership without relying solely on matching email addresses;
- persist the billing record idempotently;
- return effective entitlements.

An approval callback improves responsiveness, but webhook/reconciliation remains authoritative.

### 10.3 Subscription summary

```http
GET /api/v1/subscribers/{subscriberId}/subscription
```

Return plan, status, next billing time, paid-access cutoff, alert status, enabled channels, and
available actions. Do not return PayPal payer data unless the UI truly needs it.

### 10.4 Alert preferences

```http
GET   /api/v1/subscribers/{subscriberId}/preferences
PATCH /api/v1/subscribers/{subscriberId}/preferences
POST  /api/v1/subscribers/{subscriberId}/alerts/pause
POST  /api/v1/subscribers/{subscriberId}/alerts/resume
```

Example update:

```json
{
  "emailEnabled": true,
  "whatsappEnabled": false,
  "pausedUntil": null
}
```

Plan rules are enforced on the server. Free users cannot enable WhatsApp or multiple categories
by manually changing a request.

### 10.5 Cancellation

```http
POST /api/v1/subscribers/{subscriberId}/subscription/cancel
```

```json
{
  "afterCancellation": "DOWNGRADE_TO_FREE",
  "retainedProfileId": "UUID",
  "reason": "TOO_EXPENSIVE"
}
```

Recommended PayPal approach:

1. call PayPal cancellation immediately to stop future billing;
2. mark local status cancellation-pending until confirmed;
3. preserve TenderBell paid entitlements through the computed already-paid-through time;
4. at that cutoff, downgrade or stop alerts according to the user's explicit selection.

This avoids depending on a future cancellation job that could fail after PayPal has already
scheduled another renewal.

### 10.6 Resubscription

```http
POST /api/v1/subscribers/{subscriberId}/resubscribe/free
```

Free resubscription requires explicit confirmation of email delivery. A fully cancelled PayPal
subscription normally requires a new PayPal approval and a new billing-subscription row. If a
subscription is merely suspended and PayPal allows activation, use the activate operation and
wait for confirmed provider state.

---

## 11. React Frontend Specification

### 11.1 Suggested routes

```text
/signup
/signup/paypal/return
/manage
/manage/alerts
/manage/billing
/manage/cancel
/unsubscribe
/resubscribe
/portal                 # Max only
```

### 11.2 Suggested components

```text
PlanSelector
PayPalSubscriptionButton
SubscriptionSummaryCard
BillingStatusBanner
AlertChannelControls
CategoryProfileEditor
PauseAlertsDialog
CancelSubscriptionDialog
DowngradeChoice
UnsubscribeConfirmation
ResubscribeConfirmation
```

### 11.3 PayPal SDK loading

Load the SDK with the public client ID and subscription parameters:

```text
https://www.paypal.com/sdk/js
  ?client-id=PUBLIC_CLIENT_ID
  &components=buttons
  &vault=true
  &intent=subscription
  &currency=USD
```

Illustrative React/TypeScript integration:

```tsx
<PayPalButtons
  createSubscription={(_data, actions) =>
    actions.subscription.create({ plan_id: selectedPayPalPlanId })
  }
  onApprove={async (data) => {
    if (!data.subscriptionID) throw new Error("PayPal did not return a subscription ID");
    await api.confirmPayPalSubscription({
      paypalSubscriptionId: data.subscriptionID,
      requestedPlan: selectedPlan,
    });
    navigate("/manage?subscription=active");
  }}
  onCancel={() => setCheckoutState("cancelled")}
  onError={() => setCheckoutState("failed")}
/>
```

Exact library choice is an implementation decision. The security invariant is that React only
initiates approval and passes the resulting subscription ID to Spring Boot; React never grants
itself a plan.

### 11.4 UI requirements

- Disable duplicate checkout submission while approval is in progress.
- Display an explicit plan and price immediately above the PayPal button.
- Show that billing is monthly and continues until cancelled.
- On callback failure, explain that payment may still be processing and provide a retry-status
  action rather than creating another subscription immediately.
- Never collect PayPal credentials or card details in TenderBell forms.
- Cancellation requires a review screen and final confirmation.
- Clearly state whether the user selected downgrade-to-Free or stop-all-alerts.
- Max-only routes must be authorized by backend entitlements, not merely hidden in React.

---

## 12. Email and WhatsApp Requirements

### 12.1 Email footer

Every alert or digest should include:

```text
You are receiving this alert because it matched your saved
“IT & Software” tender category.

Manage alerts | Pause alerts | Manage subscription
Stop email alerts | Privacy Policy | Contact TenderBell
```

For Free, `Manage subscription` may read `View plans`. For Pro and Max, it opens the billing
management page.

### 12.2 One-click unsubscribe

Implement both visible body links and RFC 8058-compatible headers for subscription email:

```text
List-Unsubscribe: <https://YOUR_API_DOMAIN/api/v1/unsubscribe/one-click?token=OPAQUE_TOKEN>
List-Unsubscribe-Post: List-Unsubscribe=One-Click
```

The one-click endpoint must accept POST and unsubscribe without login or additional interaction.
The visible body link may open a preference page where the user chooses email-only opt-out,
pause, or all-alert opt-out.

Use a POST endpoint for one-click state changes and a non-mutating GET confirmation page so
automated link scanners do not accidentally unsubscribe users.

### 12.3 Re-subscription

Re-enabling email after an unsubscribe must:

- be an explicit user action;
- confirm ownership using a magic-link flow or a single-purpose confirmation token;
- set `emailConsentAt`;
- write a preference audit event;
- not reactivate PayPal billing unless the user completes PayPal approval.

### 12.4 WhatsApp

Paid alerts should include a concise preference link and a clear opt-out instruction. An inbound
`STOP` or equivalent supported interaction should disable WhatsApp only, unless the user chooses
to stop all channels. Record opt-out time and source. Do not interpret WhatsApp opt-out as PayPal
cancellation.

---

## 13. Payment Failure and Grace Policy

Recommended MVP policy:

| Time | TenderBell behavior |
|---|---|
| Failure received | Set `PAYMENT_FAILED`; notify customer once for the attempt |
| Grace days 0–3 | Preserve paid delivery while PayPal retries |
| Day 3 | Reminder with secure PayPal resolution link |
| Day 5 | Final warning and exact service cutoff |
| Day 7 or PayPal suspension | Remove paid entitlements |

After paid access ends:

- do not silently start Free email if the user selected “stop all alerts”;
- if the user previously chose fallback to Free, retain one selected profile and send weekly;
- for Max, archive profiles beyond the one retained Free profile;
- preserve archived settings for a documented retention period so resubscription is easy;
- show `Resolve payment` and `Subscribe again` actions as appropriate.

PayPal's configured retry and failure threshold remain provider behavior. TenderBell's grace
period controls only TenderBell entitlements and must not claim to alter PayPal's collection
schedule.

---

## 14. Plan Changes

### 14.1 Pro to Max

PayPal supports subscription revision, but plan changes may require buyer approval. Treat a plan
revision as pending until PayPal reports the new plan and active state. Do not grant Max portal
access merely because React initiated a revision.

### 14.2 Max to Pro

Before downgrade:

- explain the loss of the Market Insights Portal;
- require selection of the one profile retained by Pro;
- archive additional profiles;
- define when the plan change takes effect;
- confirm the provider's revised plan through PayPal.

### 14.3 Paid to Free

Cancellation presents two explicit options:

1. **Continue with Free weekly alerts** — retain one selected category and email consent.
2. **Stop all TenderBell alerts** — disable all delivery at paid-access cutoff.

---

## 15. Security Requirements

- Keep PayPal Client Secret and access tokens server-side only.
- Verify every webhook before changing state.
- Store webhook event IDs and process idempotently.
- Treat browser approval as provisional until the backend verifies with PayPal.
- Validate PayPal plan ID against server configuration, never a client-supplied price.
- Prevent one PayPal subscription ID from attaching to multiple subscribers.
- Use opaque random tokens; store only hashes for unsubscribe and magic-link tokens.
- Do not put email addresses, plan names, or subscriber IDs in unsubscribe-token payloads.
- Apply rate limits to magic-link, confirmation, and PayPal lookup endpoints.
- Require CSRF protection when using cookie authentication. Bearer-token implementations must
  still enforce subscriber ownership.
- Validate outbound PayPal URLs and provider identifiers.
- Configure HTTP connect/read timeouts and bounded retries.
- Never retry a cancellation request blindly without first fetching current provider state.
- Do not log secrets, bearer tokens, unsubscribe tokens, PayPal access tokens, or full payer data.
- Encrypt sensitive production data at rest through the hosting/database platform.
- Restrict billing support actions and record an audit trail.

---

## 16. Operational Monitoring

Track at minimum:

- active Pro and Max subscriptions;
- approval-pending subscriptions older than one hour;
- payment failures and recovery rate;
- PayPal/local state mismatches;
- webhook verification failures;
- duplicate webhook deliveries;
- webhook processing latency;
- reconciliation failures;
- cancellation requests and confirmations;
- email and WhatsApp opt-out rates;
- alerts skipped because of plan or preference state.

Alert operators when:

- webhook signature verification fails repeatedly;
- no PayPal webhooks have arrived during an expected activity window;
- reconciliation mismatch count exceeds a threshold;
- cancellation API calls fail;
- a paid user has no current billing-subscription record;
- a Free or Pro account exceeds its category limit;
- a non-Max user gains portal entitlements.

---

## 17. Testing Strategy

### 17.1 Kotlin unit tests

- plan-to-entitlement mapping;
- alert eligibility for each billing/alert-state combination;
- PayPal plan-ID validation;
- payment-failure grace calculations;
- paid-access cutoff calculations;
- cancellation outcome selection;
- Max-to-Free profile archival;
- one-click unsubscribe idempotency;
- resubscription consent recording;
- webhook event mapping;
- stale event protection.

### 17.2 Spring integration tests

- webhook signature success and failure;
- duplicate event delivery;
- activation only after PayPal verification;
- subscription replay against a second subscriber;
- Pro and Max plan distinction;
- cancellation API failure does not report success;
- webhook database transaction rollback;
- reconciliation repairs missed webhook state;
- ownership enforcement on every management route;
- unsubscribed subscribers are excluded from all notification jobs;
- paid features stop after access cutoff.

Use MockWebServer or an equivalent controlled HTTP server for PayPal client tests. CI must not call
the live or sandbox PayPal API.

### 17.3 React tests

- correct PayPal plan ID selected for Pro and Max;
- checkout loading, approval, cancellation, and error states;
- repeated clicks do not create multiple subscriptions;
- plan status banners;
- cancellation confirmation and downgrade choice;
- channel toggle independence;
- Max route denied when backend reports no Max entitlement;
- accessible keyboard and screen-reader behavior.

### 17.4 Sandbox acceptance tests

- new Pro subscription;
- new Max subscription;
- Free user upgrades in place without losing preferences;
- PayPal approval abandoned;
- recurring payment event processed;
- payment failure and retry;
- suspension;
- cancellation;
- resubscription after cancellation;
- upgrade and downgrade revision;
- duplicate webhook replay;
- webhook delivery delayed until reconciliation;
- email unsubscribe does not cancel PayPal;
- PayPal cancellation does not silently opt the user into Free.

---

## 18. Rollout Plan

### Phase 1 — Data and entitlement foundation

- add `FREE`, `PRO`, and `MAX`;
- add billing and alert states;
- implement the three-plan model and entitlement service;
- update notification queries and tests.

### Phase 2 — PayPal plans and checkout

- create sandbox product and plans;
- configure separate Pro and Max IDs;
- implement the PayPal client;
- port the checkout into React;
- implement server-side confirmation.

### Phase 3 — Webhooks and reconciliation

- add verified webhook endpoint and inbox;
- implement event processing;
- add reconciliation job;
- add monitoring and operator alerts.

### Phase 4 — Self-service preferences and cancellation

- build Manage TenderBell pages;
- add pause/resume and per-channel controls;
- implement cancellation outcomes;
- implement email one-click unsubscribe headers;
- add explicit resubscription.

### Phase 5 — Max portal integration and production launch

- enforce portal entitlements server-side;
- add Max billing/preferences screen;
- complete sandbox acceptance tests;
- create live PayPal resources;
- perform controlled live verification;
- obtain legal/privacy review.

---

## 19. Acceptance Criteria

The feature is complete only when:

1. Free, Pro, and Max exist as separate backend plans.
2. Pro and Max use different configured PayPal plan IDs.
3. No browser-supplied price or plan claim can grant paid access.
4. Every PayPal webhook is verified and deduplicated.
5. Missed webhooks can be repaired by reconciliation.
6. Free users can manage one category, pause, unsubscribe, and resubscribe.
7. Pro users can independently manage email, WhatsApp, billing, and cancellation.
8. Max users can perform those actions inside the Market Insights Portal.
9. Unsubscribing from email never silently cancels or continues PayPal billing.
10. Cancelling PayPal never silently subscribes the user to Free.
11. Cancellation clearly states the paid-access cutoff.
12. Failed payments produce a deterministic grace and suspension outcome.
13. Max-to-lower-tier transitions archive excess profiles safely.
14. One-click email unsubscribe is idempotent and does not require login.
15. Fully cancelled users can create a new PayPal subscription without losing retained settings.
16. Secrets and access tokens never reach React or application logs.
17. Unit, integration, React, and PayPal sandbox acceptance tests pass.
18. Product copy matches the actual aggregation and delivery schedule.

---

## 20. Decisions Required Before Implementation

The product owner must confirm:

1. whether paid cancellation is immediate or access continues through the paid-through date;
2. the exact failed-payment grace period;
3. whether a payment failure defaults to stopped alerts or an expressly preselected Free fallback;
4. how long archived Max profiles are retained;
5. the refund policy and how refunds affect access;
6. whether users may pause billing, or only pause alert delivery;
7. whether plan upgrades take effect immediately and how PayPal proration is handled;
8. the final public web and API domains;
9. the production email provider and its support for RFC 8058 headers;
10. the final Monday digest time in Africa/Johannesburg time.

Recommended MVP decisions are:

- cancel PayPal immediately but keep TenderBell access through the paid-through date;
- use a seven-day failed-payment grace period;
- never silently downgrade to Free—ask the customer during cancellation and payment recovery;
- retain archived profiles for 90 days;
- allow pausing alert delivery, not billing, in the first release;
- defer automated proration and use fresh approval where plan-change behavior is ambiguous;
- deliver the Free digest every Monday at 08:00 Africa/Johannesburg.

---

## 21. References

- [PayPal Subscriptions overview](https://developer.paypal.com/platforms/subscriptions/)
- [PayPal subscription integration](https://developer.paypal.com/subscriptions/integrate)
- [PayPal subscription customization](https://developer.paypal.com/subscriptions/customize)
- [PayPal REST authentication](https://developer.paypal.com/api/rest/authentication/)
- [PayPal subscription details API](https://developer.paypal.com/api/subscriptions/v1/subscriptions-get/)
- [PayPal webhook overview](https://developer.paypal.com/api/rest/webhooks)
- [PayPal webhook integration and verification](https://developer.paypal.com/api/rest/webhooks/rest/)
- [PayPal webhook event names](https://developer.paypal.com/api/rest/webhooks/event-names/)
- [PayPal payment-failure handling](https://developer.paypal.com/api/handle-payment-failures/)
- [PayPal JavaScript SDK reference](https://developer.paypal.com/sdk/js/reference/)
- [Google email sender guidelines](https://support.google.com/mail/answer/81126)
- [Google email subscription guidelines](https://support.google.com/mail/answer/15263077)
