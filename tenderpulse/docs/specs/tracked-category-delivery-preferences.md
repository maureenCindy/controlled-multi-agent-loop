# TenderBell Tracked Categories and WhatsApp Allowance Specification

**Status:** Proposed for product and implementation review
**Date:** 2026-09-08
**Product:** TenderBell Max
**Backend target:** Spring Boot, Kotlin, PostgreSQL, Flyway
**Frontend target:** React + TypeScript
**Related specifications:** `alert-inbox.md`, `max-dashboard.md`, and
`subscription-lifecycle-paypal.md`

---

## 1. Purpose

This specification defines how a Max subscriber manages tracked procurement categories, default
Email delivery, category-level WhatsApp alert types, and the plan allowance of 100 WhatsApp
messages per monthly billing cycle.

The design must make four facts clear:

1. Active tracked categories determine which procurement events can generate subscriber alerts.
2. Email is the default, low-cost delivery channel.
3. WhatsApp is optional and configurable by alert type for each active category.
4. All categories share one 100-message WhatsApp allowance for the current Max billing cycle.

---

## 2. Product Decisions

### 2.1 Tracked-category terminology

Use **Tracked categories** in subscriber-facing navigation and page titles. A tracked category is a
category the subscriber selected. A matched tender or award is a result produced from that
configuration.

### 2.2 Supported alert types

The category-level WhatsApp controls cover:

| Internal value | Subscriber label | Email default | WhatsApp default for a new category |
|---|---|---:|---:|
| `TENDER_PUBLISHED` | Newly published tenders | On | On |
| `TENDER_CLOSING_SOON` | Closing-soon reminders | On | Off |
| `AWARD_NOTICE_PUBLISHED` | Award notices | On | Off |

New-tender WhatsApp delivery is enabled by default because it provides the earliest opportunity
signal. Reminders and awards remain Email-only until the subscriber opts in to WhatsApp for that
category.

### 2.3 Email behaviour

- Email is enabled by default at account level.
- While Email is enabled, every supported alert type for every active tracked category is eligible
  for Email delivery.
- The initial release does not require per-category Email switches.
- Subscribers can disable Email globally through Delivery Preferences or use the unsubscribe and
  preference links included in every applicable email.
- Disabling Email does not pause categories and does not disable WhatsApp.
- Email delivery continues when the WhatsApp allowance is exhausted.
- Consent, suppression, bounce, and verified-destination rules still apply; “default on” must never
  bypass a legal or provider suppression state.

### 2.4 WhatsApp behaviour

- WhatsApp has an account-level master switch.
- A verified WhatsApp destination is required.
- Every active category has an independent Boolean preference for each supported alert type.
- Category-level switches are retained while the category is paused.
- Stopping a category ends future matching but does not delete prior Inbox alerts or delivery
  history.
- The effective setting is calculated on the backend; the frontend must not infer entitlement from
  visual toggle state alone.

---

## 3. Effective Delivery Rules

### 3.1 Email eligibility

```text
subscription permits alert type
+ account Email enabled
+ verified and deliverable Email destination
+ at least one active category matched the event
+ destination not unsubscribed, bounced, or suppressed
= create one Email delivery
```

### 3.2 WhatsApp eligibility

```text
Max subscription active
+ account WhatsApp enabled
+ verified and consented WhatsApp destination
+ at least one active matched category enables WhatsApp for this alert type
+ destination not opted out or suppressed
+ monthly allowance can be reserved
= create one WhatsApp delivery
```

Every condition is required.

### 3.3 Multiple-category matches

A procurement event can match several tracked categories. TenderBell must create one subscriber
alert and at most one delivery per channel for the event.

- Email is sent once when any active category matches.
- WhatsApp is sent once when at least one active matched category enables WhatsApp for that alert
  type.
- One WhatsApp message consumes one allowance unit, regardless of how many categories matched.
- Match evidence records every contributing category so the Inbox can explain why the subscriber
  received the alert.

---

## 4. Tracked Categories Page

### 4.1 Page structure

1. Heading and **Add category** action
2. Compact WhatsApp allowance strip
3. Search and status filter
4. Tracked-category management list
5. Category lifecycle guidance

Do not show alert-volume or market-performance statistics on this page. Those metrics belong on the
Dashboard and Insights pages.

### 4.2 WhatsApp allowance strip

Show:

- used messages out of 100;
- remaining messages;
- progress indicator;
- billing-cycle reset date;
- statement that the allowance is shared across categories and alert types;
- link to **Delivery preferences**.

The strip is operational context, not an analytics card.

### 4.3 Category row

Each row shows:

- complete official category name;
- short description where useful;
- lifecycle state: Active or Paused;
- `Email · Default` when account Email delivery is enabled;
- concise WhatsApp configuration summary, for example:
  - `WhatsApp · New tenders`;
  - `WhatsApp · New tenders + reminders`;
  - `WhatsApp · New tenders + awards`;
  - `WhatsApp · Off`;
- **Configure** WhatsApp action for active categories;
- **Pause alerts** or **Resume alerts**;
- **Stop alerts**.

Long category names must wrap without hiding actions or forcing horizontal page scrolling.

### 4.4 Configure WhatsApp dialog

The dialog identifies the selected category and contains three independent switches:

- Newly published tenders;
- Closing-soon reminders;
- Award notices.

It also shows:

- Email remains enabled by default and is unaffected by these switches;
- the 100-message allowance is shared;
- multi-category matches are deduplicated;
- exhausted-allowance messages are not sent later.

**Save WhatsApp settings** persists all three values atomically. **Cancel**, Escape, and the close
control discard unsaved changes and return focus to the action that opened the dialog.

### 4.5 Pause, resume, and stop

| Action | Matching | Saved delivery settings | Historical Inbox alerts |
|---|---|---|---|
| Pause alerts | Stops | Preserved | Preserved |
| Resume alerts | Restarts from resume time | Restored | Preserved |
| Stop alerts | Ends | Retained only for audit/recovery policy | Preserved |

Stopping requires confirmation:

> **Stop alerts for this category?**
> You will no longer receive newly published tender, closing-soon, or award alerts for this
> category. Existing Alert Inbox history will remain available.

Do not generate catch-up alerts for events published while a category was paused. Resume affects
newly detected or newly eligible events only.

---

## 5. Delivery Preferences Page

### 5.1 Account-level channel controls

Show:

- Email master switch and verified destination;
- WhatsApp master switch and verified destination;
- reminder and billing-cycle display timezone;
- explicit explanation that category WhatsApp choices apply only while the master switch is on.

Turning the WhatsApp master switch off preserves category-level choices. Turning it back on restores
their effect, subject to verification, consent, entitlement, and remaining allowance.

Account-level channel switches save immediately; there is no page-level **Save preferences** action.
The frontend may update optimistically but must show a saved confirmation, roll back on failure, and
provide a retryable error without leaving the displayed switch inconsistent with the backend.

### 5.2 Allowance card

Show:

- plan allowance: 100;
- consumed count;
- reserved/in-flight count only when useful for near-real-time accuracy;
- remaining count;
- period start and end/reset date;
- progress bar;
- Max entitlement state.

The subscriber-facing `used` count is the number of provider-accepted WhatsApp messages. The UI may
temporarily include reservations in an **Available now** calculation to avoid implying capacity
that has already been assigned to queued messages.

### 5.3 Defaults for newly tracked categories

Defaults are:

- Newly published tenders: On;
- Closing-soon reminders: Off;
- Award notices: Off.

Changing defaults affects categories created afterwards. It must not silently overwrite existing
category preferences.

Each new-category default switch saves immediately using the same confirmation and rollback
behaviour as the account-level channel switches.

An explicit **Apply defaults to all active categories** action may update existing categories. It
requires confirmation, reports the affected category count, and applies all three values in one
transaction.

---

## 6. WhatsApp Allowance Semantics

### 6.1 Allowance period

The Max allowance is 100 WhatsApp messages per monthly subscription billing cycle. The period is
anchored to the subscription renewal boundary received and reconciled from PayPal.

Store explicit `period_start` and `period_end` timestamps. Do not calculate the current period only
from the calendar month because subscribers can renew on different days.

If PayPal renewal information is delayed, continue using the last authoritative entitlement period
within the permitted billing grace policy. Reconciliation must correct the period without granting
or consuming the same allowance twice.

### 6.2 What consumes one message

- Reserve one unit immediately before enqueueing an eligible WhatsApp delivery.
- Convert the reservation to consumed when the provider accepts the submission and returns its
  message identifier.
- Release the reservation after a terminal failure that occurred before provider acceptance.
- A message accepted by the provider remains consumed even if it later fails delivery, because the
  provider-side cost may already have been incurred.
- A provider-accepted manual or automatic retry consumes another unit.
- Suppressed, deduplicated, cancelled-before-submission, and ineligible messages consume no unit.

Provider billing semantics may later require an adapter-specific reconciliation, but the product
allowance must retain one stable internal rule.

### 6.3 Atomic reservation

Allowance checks and reservations must be atomic. Concurrent workers must not push an account above
100.

Conceptual invariant:

```text
consumed_count + reserved_count <= allowance_limit
```

Use a locked allowance-period row, optimistic version check with retry, or an equivalent atomic SQL
update. A read-then-write check without concurrency control is not acceptable.

### 6.4 Exhaustion behaviour

When no unit can be reserved:

- do not enqueue WhatsApp;
- create a channel suppression record with reason `MONTHLY_ALLOWANCE_EXHAUSTED`;
- continue Email when eligible;
- retain the subscriber’s WhatsApp category settings;
- do not backfill the suppressed message after reset;
- show the suppression in the Alert Inbox delivery history;
- notify the subscriber through Email and the portal at 100 used.

Send allowance notices at 75, 90, and 100 consumed messages. These notices must not themselves
consume WhatsApp allowance; use Email and in-portal messaging.

---

## 7. Recommended Data Model

### 7.1 `subscriber_delivery_preference`

| Field | Purpose |
|---|---|
| `subscriber_id` | Owning subscriber/workspace |
| `email_enabled` | Account-level Email master setting |
| `whatsapp_enabled` | Account-level WhatsApp master setting |
| `email_destination_id` | Protected verified destination reference |
| `whatsapp_destination_id` | Protected verified and consented destination reference |
| `timezone` | Display and reminder timezone |
| `new_category_whatsapp_tender_published` | Default for new categories |
| `new_category_whatsapp_closing_soon` | Default for new categories |
| `new_category_whatsapp_award` | Default for new categories |
| `version` | Optimistic lock |
| `created_at`, `updated_at` | Audit timestamps |

### 7.2 `subscriber_tracked_category`

| Field | Purpose |
|---|---|
| `id` | Opaque category-subscription identifier |
| `subscriber_id` | Owner |
| `category_id` | Controlled procurement category |
| `state` | `ACTIVE`, `PAUSED`, or `STOPPED` |
| `paused_at`, `resumed_at`, `stopped_at` | Lifecycle audit timestamps |
| `created_at`, `updated_at` | Audit timestamps |
| `version` | Optimistic lock |

Unique current configuration: `(subscriber_id, category_id)` according to the reactivation policy.

### 7.3 `category_whatsapp_preference`

| Field | Purpose |
|---|---|
| `tracked_category_id` | Owning tracked category |
| `tender_published_enabled` | WhatsApp new-tender switch |
| `closing_soon_enabled` | WhatsApp reminder switch |
| `award_published_enabled` | WhatsApp award switch |
| `created_at`, `updated_at` | Audit timestamps |
| `version` | Optimistic lock |

The three fields are saved atomically. Keep an audit trail of who changed them and their before/after
values.

### 7.4 `whatsapp_allowance_period`

| Field | Purpose |
|---|---|
| `id` | Allowance-period identifier |
| `subscriber_id` | Max subscriber/workspace |
| `subscription_period_key` | Stable PayPal/TenderBell billing-period correlation key |
| `period_start`, `period_end` | Authoritative entitlement interval |
| `allowance_limit` | Snapshot of the plan allowance, initially 100 |
| `reserved_count` | Units held by queued/in-flight messages |
| `consumed_count` | Provider-accepted messages |
| `version` | Concurrency control |
| `created_at`, `updated_at` | Audit timestamps |

Unique constraint: `(subscriber_id, subscription_period_key)`.

### 7.5 `whatsapp_allowance_ledger`

Use an immutable ledger for audit and reconciliation.

| Field | Purpose |
|---|---|
| `id` | Ledger entry identifier |
| `allowance_period_id` | Owning period |
| `subscriber_alert_id` | Related Inbox alert |
| `delivery_attempt_id` | Related attempt or retry |
| `entry_type` | `RESERVE`, `CONSUME`, `RELEASE`, or `ADJUST` |
| `units` | Positive unit count, initially one |
| `reason` | Stable reason code |
| `idempotency_key` | Prevents duplicate ledger effects |
| `occurred_at`, `created_at` | Business and persistence times |
| `metadata` | Restricted reconciliation context |

Counters must be derivable from the ledger, while the allowance-period counters support efficient
atomic checks.

---

## 8. Backend Processing Flow

For each subscriber-specific alert event:

1. Resolve all active category matches.
2. Create or obtain the idempotent `subscriber_alert`.
3. Evaluate Email independently and enqueue at most one Email delivery.
4. Evaluate the account-level WhatsApp prerequisites.
5. Apply OR semantics across matched-category preferences for the event type.
6. Check that no WhatsApp attempt already exists for the alert and delivery generation.
7. Atomically reserve one unit in the active allowance period.
8. Persist the delivery attempt and transactional outbox command in the same transaction as the
   reservation.
9. On provider acceptance, atomically consume the reservation.
10. On terminal pre-acceptance failure or cancellation, atomically release it.
11. Apply later delivery/read callbacks without changing consumption.

If the allowance cannot be reserved, persist a suppressed channel record rather than silently doing
nothing.

---

## 9. API Requirements

All endpoints require authentication, Max entitlement where applicable, subscriber ownership, and
optimistic concurrency protection.

### 9.1 Read delivery preferences and allowance

```http
GET /api/v1/me/delivery-preferences
```

Return global channel state, masked verified destinations, new-category defaults, entitlement,
current allowance period, used/reserved/remaining counts, and reset time.

### 9.2 Update account-level preferences

```http
PATCH /api/v1/me/delivery-preferences
```

Allow explicit updates to Email, WhatsApp master state, timezone, and defaults. Omitted values remain
unchanged. Return the effective saved projection and new version.

### 9.3 List tracked categories

```http
GET /api/v1/me/tracked-categories
```

Support search, lifecycle-state filtering, and cursor pagination when the list becomes large. Return
the official category, lifecycle state, category WhatsApp settings, effective channel summary, and
permitted actions.

### 9.4 Update category WhatsApp settings

```http
PUT /api/v1/me/tracked-categories/{trackedCategoryId}/whatsapp-preferences
```

The request supplies all three Booleans and the expected version. Replace the three settings
atomically and return the effective category projection.

### 9.5 Lifecycle actions

```http
POST /api/v1/me/tracked-categories/{trackedCategoryId}/pause
POST /api/v1/me/tracked-categories/{trackedCategoryId}/resume
POST /api/v1/me/tracked-categories/{trackedCategoryId}/stop
```

Operations are idempotent. Stop requires an explicit confirmation token or semantic confirmation
field in the request; UI wording alone is not an authorisation boundary.

### 9.6 Apply defaults to active categories

```http
POST /api/v1/me/tracked-categories/actions/apply-whatsapp-defaults
```

Require the expected defaults version and explicit confirmation. Update all active categories in one
transaction and return the affected count.

---

## 10. Subscription and Billing Integration

- The Max plan entitlement provides an allowance limit of 100 per monthly billing period.
- A successful PayPal activation or renewal establishes the next allowance period from the
  authoritative internal subscription entitlement.
- Do not call PayPal during message dispatch; delivery uses locally reconciled entitlement state.
- Webhook processing must be signature-verified, idempotent, and safe against duplicate or
  out-of-order events.
- Cancellation at period end leaves the current allowance available only while Max remains active.
- Suspension, failed renewal, or plan downgrade follows the subscription grace policy. After Max
  entitlement ends, new WhatsApp reservations are denied.
- A plan change must never reset the same period repeatedly or duplicate remaining allowance.

---

## 11. Security, Consent, and Privacy

- Require a verified destination and recorded WhatsApp consent before enabling effective delivery.
- Respect WhatsApp opt-out independently of the visible category switches.
- Respect Email unsubscribe and suppression state before every send.
- Mask destinations in subscriber projections and standard logs.
- Encrypt destination data or reference a protected contact record.
- Do not expose provider tokens, raw webhook payloads, or internal errors.
- Authorise every category and preference mutation by subscriber ownership.
- Rate-limit preference updates, bulk apply, lifecycle actions, and delivery retries.
- Validate and allowlist resolved official-notice URLs used in template action buttons.
- Record an audit event for global channel, category preference, bulk apply, pause, resume, and stop
  changes.

---

## 12. Observability and Operations

Track:

- allowance reserved, consumed, released, suppressed, and adjusted;
- consumption by alert type and tracked category;
- multi-category messages deduplicated;
- subscribers reaching 75, 90, and 100 messages;
- reservation age and leaked-reservation recovery;
- pre-acceptance and post-acceptance failure rates;
- retry consumption;
- Email continuation after WhatsApp exhaustion;
- preference updates and bulk-apply failures;
- billing-period reconciliation differences.

Alert on:

- `consumed + reserved > limit` invariant violations;
- duplicate consumption for one delivery attempt;
- reservations older than the dispatch timeout;
- WhatsApp sends without active Max entitlement or consent;
- unexpected period creation/reset rates;
- sustained exhaustion across a large share of Max subscribers.

Provide an operational reconciliation job that compares counters with the immutable ledger and
repairs only through audited `ADJUST` entries.

---

## 13. Testing Requirements

### 13.1 Unit tests

- Email and WhatsApp effective-delivery rules;
- OR semantics for multi-category matches;
- default preference application;
- allowance reservation/consume/release rules;
- remaining-count calculation;
- period-boundary and timezone rendering;
- lifecycle transition rules;
- suppression-reason mapping;
- retry eligibility and cost accounting.

### 13.2 Integration tests

- concurrent workers cannot reserve unit 101;
- repeated event processing creates one WhatsApp delivery;
- one event matching several enabled categories consumes one unit;
- provider acceptance consumes exactly one reservation;
- pre-acceptance terminal failure releases the reservation;
- post-acceptance failure remains consumed;
- accepted retry consumes another unit;
- exhaustion suppresses WhatsApp while Email continues;
- suppressed alerts are not backfilled after reset;
- pause retains settings and resume restores them;
- stop preserves Inbox history;
- PayPal webhook replay does not duplicate an allowance period;
- bulk default application is atomic.

### 13.3 API and frontend tests

- masked destinations only;
- category search and lifecycle filtering;
- Configure dialog focus, Escape, Cancel, and Save behaviour;
- all three category switches save atomically;
- category summary reflects saved values;
- active, paused, and stopped action permissions;
- allowance thresholds and exhausted state;
- global WhatsApp off with category choices preserved;
- Email unsubscribe reflected accurately;
- mobile wrapping for long category names;
- keyboard navigation and non-colour status indicators;
- cross-subscriber access returns `404`.

---

## 14. Acceptance Criteria

The feature is ready when:

1. Email defaults on without bypassing unsubscribe or suppression.
2. Every active category can configure WhatsApp independently for the three supported alert types.
3. New categories default to WhatsApp for newly published tenders only.
4. Delivery Preferences provides Email and WhatsApp master controls.
5. The UI shows used, remaining, and reset date for the shared 100-message allowance.
6. Multi-category matches produce at most one WhatsApp message and consume one unit.
7. Quota reservation is atomic and the allowance cannot exceed 100.
8. Provider-accepted messages consume allowance; terminal pre-acceptance failures do not.
9. Each accepted retry consumes a new unit.
10. Exhausted WhatsApp alerts are recorded as suppressed and are never backfilled.
11. Email continues when WhatsApp is disabled, ineligible, failed, or exhausted.
12. Pausing preserves settings; stopping preserves historical Inbox alerts.
13. Global WhatsApp disablement preserves category choices.
14. Defaults do not rewrite existing categories unless the subscriber explicitly applies them.
15. Allowance periods align with reconciled monthly subscription periods.
16. Consent, ownership, URL validation, and plan entitlement are enforced on the backend.
17. Every preference and allowance mutation is auditable and idempotent.

---

## 15. Open Questions

| # | Question | Current direction |
|---:|---|---|
| 1 | Is the 100-message period aligned to PayPal renewal or a calendar month? | Align to the subscriber’s reconciled monthly billing period. |
| 2 | Are additional message packs available after exhaustion? | Not in the initial release; continue Email and consider add-ons after usage data exists. |
| 3 | Can Max administrators manage preferences for multiple recipients? | Initial model assumes one subscriber delivery profile; design destination references for future expansion. |
| 4 | Does the selected WhatsApp provider charge every accepted template message in the same way? | Keep one stable internal allowance rule and reconcile provider-specific billing separately. |
| 5 | Should subscribers allocate reserved sub-budgets by alert type? | Do not add initially; use conservative defaults and threshold warnings first. |
| 6 | What grace period applies after a failed PayPal renewal? | Reuse the decision in the subscription lifecycle specification. |
