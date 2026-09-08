# TenderBell Alert Inbox Specification

**Status:** Proposed for product and implementation review<br />
**Date:** 2026-09-08<br />
**Product:** TenderBell Pro and Max alert service; Max portal Inbox<br />
**Backend target:** Spring Boot, Kotlin, PostgreSQL, Flyway<br />
**Frontend target:** React + TypeScript<br />
**Related specifications:** `max-dashboard.md`, `market-opportunity-insights.md`,
`tracked-category-delivery-preferences.md`, and `subscription-lifecycle-paypal.md`

---

## 1. Purpose

This specification defines the TenderBell Alert Inbox, its grouped notification presentation, and
the backend services required to generate, persist, deliver, query, and audit subscriber alerts.

The Inbox must answer:

1. What procurement event caused this alert?
2. Why did it match my tracked category?
3. What are the most important tender or award details?
4. Which notification channels were used?
5. When was each channel sent, delivered, read, or failed?
6. What can I do when delivery fails?

The design must preserve three independent concepts:

- **Inbox state:** unread or read;
- **Procurement state:** open, closing soon, closed, failed, or awarded;
- **Delivery state:** queued, sent, delivered, read, failed, bounced, suppressed, or cancelled.

These states must never be represented by a single generic `status` field.

---

## 2. Alert Types

The initial Inbox supports three subscriber-facing alert types.

| Type | Display label | Trigger |
|---|---|---|
| `TENDER_PUBLISHED` | Newly Published Tender Alert | TenderBell first detects a unique published tender that matches an active tracked category |
| `TENDER_CLOSING_SOON` | Published Tender Closing Soon Alert | An open matching tender enters the subscriber’s configured reminder window |
| `AWARD_NOTICE_PUBLISHED` | Award Notice Alert | TenderBell first detects a unique published award notice linked to an active tracked category |

Alert labels must remain consistent across email, WhatsApp, Dashboard, Inbox, and preferences.

Potential future types such as tender amendment, failed tender, procurement-plan reminder, or source
correction require separate product approval and deduplication rules. They must not be forced into
one of the three initial types merely to reuse the UI.

---

## 3. Inbox Information Architecture

The page order is:

1. Page heading and bulk action
2. Summary counts
3. Search and filters
4. Result count and ordering
5. Alert groups with channel-specific notification records
6. Pagination or progressive loading

### 3.1 Summary counts

| Counter | Definition |
|---|---|
| All alerts | Subscriber alerts in the default retention window |
| Unread | Subscriber alerts without `readAt` |
| Closing soon | Closing-soon alerts whose tender is still open |
| Delivery issues | Alerts whose latest channel delivery roll-up is Partial failure or Failed and may require action |

Counters must be calculated with the same subscriber scope and retention rules as the list. A
counter may apply a different state filter only when its label makes that clear.

### 3.2 Default ordering

The Inbox is chronological and defaults to newest alert generation time first. Urgency-based
prioritisation belongs on the Dashboard.

Optional orderings:

- newest first;
- oldest first;
- closing date soonest;
- delivery issues first.

### 3.3 Pagination

- Use cursor-based Previous and Next navigation with a visible page/result context.
- Disable Previous on the first page and Next when no following cursor exists.
- Preserve search, filters, and sort order when moving between pages.
- Changing any search, filter, or sort value returns the subscriber to the first result page.
- The range label, such as `1–20 of 127 alerts`, counts subscriber alerts rather than channel
  delivery records.
- Numbered direct-page navigation is optional because arbitrary page jumps conflict with stable
  cursor pagination when new alerts are arriving.

---

## 4. Search and Filters

### 4.1 Searchable fields

- tender or requirement title;
- tender reference number;
- tender ID where available;
- procuring entity;
- awardee;
- official and preferred category names.

Search should be case-insensitive and tolerant of common punctuation differences. Do not search
provider error payloads or raw recipient contact details.

### 4.2 Filters

- alert type;
- tracked category;
- Inbox state;
- procurement lifecycle state;
- channel used;
- delivery state;
- alert-created date range;
- tender closing-date range;
- unread only;

### 4.3 Filter semantics

- `channel=WHATSAPP` means a WhatsApp delivery record exists for the alert.
- `deliveryStatus=FAILED` means the latest applicable attempt for at least one selected channel
  failed.
- `closingSoon=true` must also confirm that the tender remains open.
- Category filtering uses the match recorded when the alert was generated, not only the subscriber’s
  current category list.
- Removing a tracked category must not rewrite historical Inbox records.

---

## 5. Alert Group Presentation

One procurement event creates one subscriber alert in the Inbox. Email and WhatsApp are child
notification records within that alert; they must not appear as duplicate top-level alert rows.
Headline counts and pagination count subscriber alerts, not channel deliveries.

Each child notification presents the channel-appropriate content that TenderBell actually sent and
its delivery result. The content is an immutable, versioned snapshot. If the official notice later
changes, the current change appears separately from the sent snapshots so the Inbox never rewrites
delivery history.

### 5.1 Common content

- unread/read indicator;
- alert-type badge;
- alert generation date and time;
- title;
- matched category;
- procuring entity or awardee context;
- most relevant procurement dates;
- official source;
- a notification record and delivery result for each attempted channel;
- official source link;
- an expand/collapse indicator only for alert types that use a disclosure.

### 5.2 Newly Published Tender Alert

The collapsed Newly Published Tender Alert shows a concise summary with:

- tender title;
- tender reference number;
- matched category;
- procuring entity;
- publication date;
- closing date and time;
- compact Email and WhatsApp delivery states;
- adjacent **View Tender Details** and **View Alert** actions.

**View Tender Details** opens the official publisher page without expanding the row. **View Alert**
expands the row and reveals both the Email and WhatsApp snapshots. The expanded Email snapshot must
reproduce the branded content and layout of the actual sent email; the WhatsApp snapshot must
reproduce the actual channel-specific message. The expanded content includes:

- procurement method and class;
- project or delivery location;
- delivery period;
- funding source;
- material updates or addendums in a separate current-update callout.

Avoid fields that do not help the subscriber decide or act: do not repeat `Status: Published`, and
omit date created, download count, and a zero bid-form fee from the default presentation. A
non-zero fee may be shown when it materially affects participation.

Within the group:

- **Email alert** shows the full, formatted email notification snapshot and its masked destination,
  sent time, and latest delivery state/time.
- **WhatsApp alert** shows the exact channel-appropriate message snapshot and its masked
  destination, sent time, delivered time, and read time where the provider confirms them. It must
  preserve the approved template structure—including its header, body variables, footer, and
  configured **View Tender Details** URL action button.
- The Inbox-level official tender action appears once in the collapsed action row. A source link
  visible inside a faithful message snapshot is part of the reproduced message, not a second Inbox
  action.
- A later source update must not be inserted into either historical message snapshot.

### 5.3 Published Tender Closing Soon Alert

Use the same collapsed-summary and expanded-snapshot pattern. Show:

- tender title;
- matched category;
- procuring entity;
- exact closing date and time;
- calculated time remaining;
- original publication date;
- official source;
- reminder delivery summary.
- adjacent **View Tender Details** and **View Alert** actions.

**View Alert** reveals the branded Email reminder and the WhatsApp closing-soon template, including
the WhatsApp **View Tender Details** action. If a channel failed, still show the immutable message
that TenderBell attempted to send, label it as failed, and show the safe recovery actions beneath
the snapshots.

Time remaining is display data derived from a stable closing instant and the current time. The
backend remains authoritative for whether the tender qualifies as closing soon.

### 5.4 Award Notice Alert

Use the same collapsed-summary and expanded-snapshot pattern. Show:

- tender or requirement title;
- matched category;
- procuring entity where available;
- awardee;
- contract amount and currency;
- award date;
- award publication date;
- official source;
- delivery summary.
- adjacent **View Award Details** and **View Alert** actions.

**View Alert** reveals the branded Email award alert and WhatsApp award template. Their embedded
template actions use **View Award Details** and resolve to the official award notice.

---

## 6. Expanded Alert Snapshots

All three alert types use the same in-place disclosure pattern. **View Alert** reveals the Email and
WhatsApp snapshots that were generated for that subscriber alert. If only one channel was attempted,
show only that channel. Expanded content has three conceptual blocks.

### 6.1 Message content

Each channel reproduces its actual sent or attempted template rather than showing a generic database
details panel. Do not add fields to the historical snapshot that were absent from the message.

For a closing-soon tender:

- tender ID and reference number;
- complete title;
- official category text;
- procuring entity;
- procurement class;
- procurement method;
- publication date;
- closing date and time;
- current source status and TenderBell lifecycle state;
- official tender URL;
- source last checked time.

For an award:

- award notice ID;
- tender ID and reference number;
- requirement or tender title;
- procuring entity;
- awardee;
- amount and currency;
- original reason for award;
- normalised award-reason class, where available;
- award date;
- publication date;
- procurement class and method;
- official category text;
- official award-notice URL.

### 6.2 Match explanation

Show:

- subscriber’s tracked category at generation time;
- official source category or categories;
- matching rule or evidence;
- match confidence only when calibrated and explainable;
- category taxonomy and matcher version in internal support details.

For an exact category match, prefer **Exact category match** over an artificial percentage. A match
percentage should be displayed only if a validated weighted method makes that number meaningful.

### 6.3 Delivery details

Each delivery channel appears separately and includes:

- channel;
- masked destination;
- current attempt status;
- queued time;
- provider-submitted or sent time;
- delivered time, when confirmed;
- read time, where supported and appropriate;
- failure time and user-safe explanation;
- retry action where allowed.

Provider identifiers, raw webhook payloads, internal failure stacks, and unmasked destinations are
not displayed in the standard subscriber interface.

---

## 7. User Interactions

### 7.1 Expand and collapse

- Selecting the non-interactive area of a collapsible alert toggles its expanded state.
- The card is keyboard focusable and toggles with Enter or Space.
- The control exposes `aria-expanded` and an associated content region.
- Opening an official link must not also toggle the card.
- More than one alert may remain expanded unless usability testing supports accordion-only behaviour.
- Expansion state may be represented in the URL for deep linking to support cases.
- A notification-snapshot disclosure must say **View Alert**, changing to **Hide Alert** while
  expanded.

### 7.2 Read state

Proposed initial behaviour:

- Opening a collapsible unread alert marks it read after its details load successfully.
- Opening the official notice also marks it read.
- There is no manual **Mark unread** action in the initial release.
- Mark all as read affects the current subscriber and requires confirmation when a filter is active.
- Reading an email or WhatsApp message does not automatically mean the portal Inbox alert has been
  read unless a deliberate cross-channel product rule is introduced.

There is no archive or permanent-delete action in the initial release. Alerts remain available
according to the configured retention policy.

### 7.3 Official source links

- Open in a new browser tab.
- Use the source URL stored against the underlying tender or award snapshot.
- Display the source name, such as PRAZ e-GP.
- Use an allowlist or validated source-host mapping to prevent stored open-redirect or malicious-link
  behaviour.
- Make clear that the subscriber is leaving TenderBell.

### 7.4 Retry delivery

- Retry is offered only for retryable failed attempts.
- Retry applies to one channel, not every successful channel.
- Retrying creates a new delivery attempt; it does not erase or reset the failed attempt.
- Apply per-alert, per-channel, per-subscriber rate limits.
- Disable duplicate retry requests while a retry is queued or in progress.
- Non-retryable outcomes direct the subscriber to update or verify their destination.

---

## 8. State Models

### 8.1 Inbox state

| State | Representation |
|---|---|
| Unread | `readAt = null` |
| Read | `readAt != null` |

Retain the read timestamp instead of storing only a mutable enum.

### 8.2 Procurement lifecycle state

The Inbox consumes the normalised states defined in `market-opportunity-insights.md`:

- Scheduled;
- Published/Open;
- Closing soon, derived by TenderBell;
- Closed;
- Awarded;
- Failed;
- Cancelled/Withdrawn;
- Unknown.

`Closed` currently means bidding ended; it must not automatically be treated as completed or
awarded. The official source does not currently provide reasons for `Failed` tenders.

### 8.3 Delivery attempt state

| State | Meaning |
|---|---|
| `QUEUED` | Accepted for asynchronous dispatch but not yet submitted to the provider |
| `SUBMITTED` | Provider accepted the send request and returned an identifier |
| `SENT` | Provider reports the message sent onward; this is not proof of delivery |
| `DELIVERED` | Provider explicitly confirms destination delivery |
| `READ` | Provider explicitly reports read status where supported, primarily WhatsApp |
| `FAILED` | Attempt failed before confirmed delivery |
| `BOUNCED` | Email provider reports a bounce after submission |
| `SUPPRESSED` | TenderBell/provider intentionally did not send, for example unsubscribe or invalid destination |
| `CANCELLED` | A queued attempt was intentionally cancelled before submission |

Email open tracking is not equivalent to reliable read confirmation and should not be presented as
`READ` unless product, privacy, and provider semantics are deliberately approved.

### 8.4 Delivery transitions

Normal successful progression:

```text
QUEUED → SUBMITTED → SENT → DELIVERED → READ
```

Allowed terminal or exceptional transitions include:

```text
QUEUED → SUPPRESSED
QUEUED → CANCELLED
QUEUED | SUBMITTED | SENT → FAILED
SUBMITTED | SENT | DELIVERED → BOUNCED
```

Provider callbacks may arrive late, be duplicated, or arrive out of order. Transition handling must
be idempotent and use provider event time plus an allowed transition policy. A late `SENT` callback
must not regress an attempt already marked `DELIVERED`.

### 8.5 Delivery roll-up

The alert-level delivery summary is calculated from the latest attempts per enabled channel:

| Roll-up | Rule |
|---|---|
| Pending | At least one latest attempt is Queued, Submitted, or Sent and none has a failure requiring action |
| Successful | Every attempted channel is Delivered or Read |
| Partially successful | At least one channel is Delivered/Read and at least one is Failed/Bounced |
| Failed | Every attempted channel is Failed/Bounced and none succeeded |
| Suppressed | No channel was attempted because delivery was suppressed or disabled |

Each alert group should still show every channel status; the roll-up is useful for counters and
filtering, not a replacement for channel detail.

---

## 9. Backend Domain Model

The recommended model separates a source event, a subscriber-specific Inbox record, match evidence,
and delivery attempts.

### 9.1 `alert_event`

Represents a deduplicated procurement event independent of any subscriber.

| Field | Purpose |
|---|---|
| `id` | Internal immutable identifier |
| `event_type` | Tender published, closing soon, or award published |
| `subject_type` | Tender or award notice |
| `subject_id` | Internal tender/award identifier |
| `event_key` | Stable idempotency key |
| `source_version_id` | Source snapshot that caused the event |
| `occurred_at` | Source event time where available |
| `detected_at` | When TenderBell detected it |
| `created_at` | Persistence time |
| `content_snapshot` | Versioned alert-relevant fields at generation time, preferably structured JSON |

`content_snapshot` protects the historical procurement event from becoming misleading when a
tender later changes. The UI may separately indicate current tender state and material updates,
with their own source-observed timestamp.

### 9.2 `subscriber_alert`

Represents the Inbox item owned by one subscriber.

| Field | Purpose |
|---|---|
| `id` | Subscriber-visible opaque identifier |
| `subscriber_id` | Owning subscriber |
| `alert_event_id` | Underlying deduplicated event |
| `priority` | Computed display/processing priority |
| `generated_at` | When the subscriber alert was created |
| `read_at` | Portal read timestamp |
| `created_at`, `updated_at` | Audit timestamps |
| `version` | Optimistic-lock version |

Unique constraint: `(subscriber_id, alert_event_id)`.

### 9.3 `subscriber_alert_match`

One alert may match more than one category or rule.

| Field | Purpose |
|---|---|
| `subscriber_alert_id` | Owning subscriber alert |
| `tracked_category_id` | Category configuration at generation time |
| `source_category_text` | Exact source wording |
| `match_type` | Exact category, mapped category, keyword, or future supported type |
| `match_score` | Optional calibrated value |
| `match_evidence` | Structured contributing factors |
| `matcher_version` | Reproducibility and support |

Deleting or changing a current tracked category must not delete historical match evidence.

### 9.4 `alert_delivery_attempt`

Every dispatch or retry is a separate immutable attempt.

| Field | Purpose |
|---|---|
| `id` | Delivery-attempt identifier |
| `subscriber_alert_id` | Related Inbox alert |
| `channel` | Email or WhatsApp |
| `attempt_number` | Monotonic attempt number within alert and channel |
| `destination_encrypted` or destination reference | Protected send destination |
| `destination_masked` | Safe display value or reproducible masking source |
| `provider` | Configured provider identifier |
| `provider_message_id` | Provider correlation identifier |
| `template_key` | Stable notification-template identifier |
| `template_version` | Exact template version used for this attempt |
| `rendered_content_snapshot` | Immutable structured snapshot of what was sent; never mutable current tender data |
| `content_fingerprint` | Hash used for audit, diagnostics, and duplicate-send investigation |
| `status` | Delivery attempt state |
| `queued_at` | Queue time |
| `submitted_at` | Provider acceptance time |
| `sent_at` | Provider sent time |
| `delivered_at` | Provider-confirmed delivery time |
| `read_at` | Provider-confirmed read time where supported |
| `failed_at` | Failure time |
| `failure_code` | Normalised internal failure code |
| `failure_detail` | Protected diagnostic context |
| `retryable` | Whether another attempt may be requested |
| `created_at`, `updated_at` | Audit timestamps |

Recommended unique constraints:

- `(subscriber_alert_id, channel, attempt_number)`;
- `(provider, provider_message_id)` when the provider guarantees identifier uniqueness.

### 9.5 Webhook receipt ledger

Store verified provider callbacks before applying transitions.

Suggested fields:

- provider;
- provider event ID;
- provider message ID;
- event type;
- provider event time;
- received time;
- signature-verification result;
- encrypted or access-controlled payload;
- processing state and error;
- applied delivery attempt;
- replay count.

Use a unique provider event key to make webhook processing idempotent.

---

## 10. Event and Idempotency Rules

### 10.1 Tender-published event key

Derive from stable source identity and the publication event version, for example:

```text
TENDER_PUBLISHED:{source}:{sourceTenderId}:{materialPublicationVersion}
```

A harmless parser re-run must not create a new event. A material amendment should become a future
amendment event only when that alert type is supported.

### 10.2 Closing-soon event key

Derive from subscriber, tender, configured reminder boundary, and closing-date version:

```text
TENDER_CLOSING_SOON:{subscriberId}:{tenderId}:{reminderWindow}:{closingInstantVersion}
```

If the official closing date changes, re-evaluate reminder eligibility. Do not repeatedly alert the
same unchanged deadline on every scheduler run.

### 10.3 Award event key

Derive from the source award-notice identity and version:

```text
AWARD_NOTICE_PUBLISHED:{source}:{sourceAwardNoticeId}:{materialAwardVersion}
```

If a notice contains multiple independently identifiable lots or awardees, event granularity remains
an open data-model decision and must follow the official source structure.

### 10.4 Transactional outbox

Persist subscriber alerts and outgoing delivery commands in the same database transaction. A
background dispatcher reads the outbox and submits messages to providers.

This prevents:

- a notification being sent without an Inbox record;
- an Inbox record being committed while its delivery command is lost;
- duplicate sends after transaction retries.

Dispatch remains at-least-once internally. Provider submission must use idempotency keys where
available, and TenderBell must deduplicate retries and worker redelivery.

---

## 11. API Requirements

All subscriber endpoints require authentication and ownership checks.

### 11.1 List alerts

```http
GET /api/v1/me/alerts
```

Suggested query parameters:

- `cursor`;
- `limit` with a safe maximum;
- `query`;
- `alertType`;
- `trackedCategoryId`;
- `inboxState`;
- `procurementState`;
- `channel`;
- `deliveryStatus`;
- `createdFrom`, `createdTo`;
- `closingFrom`, `closingTo`;
- `sort`.

Return subscriber-alert groups, their channel notification snapshots and latest delivery states,
summary counts, active filter facets, next cursor, and source freshness where appropriate. The API
must not emit one top-level result per delivery channel.

### 11.2 Get alert details

```http
GET /api/v1/me/alerts/{alertId}
```

Return:

- common alert data;
- type-specific tender or award event snapshot;
- current procurement state separately;
- all match evidence;
- the immutable structured message snapshot for each channel;
- latest delivery attempt plus permitted historical attempts per channel;
- allowed actions;
- source link;
- relevant coverage or freshness information.

Return `404` rather than revealing whether an alert owned by another subscriber exists.

### 11.3 Mark an alert read

```http
POST /api/v1/me/alerts/{alertId}/read
```

This operation is idempotent and sets `readAt` only when it is currently null. The initial release
does not expose a corresponding mark-unread operation.

### 11.4 Mark all as read

```http
POST /api/v1/me/alerts/actions/mark-read
```

The request must state whether it applies to all retained alerts or the supplied filter set. The
response should return the affected count.

### 11.5 Retry delivery

```http
POST /api/v1/me/alerts/{alertId}/deliveries/{channel}/retry
```

Requirements:

- validate subscriber ownership;
- validate channel entitlement and current preferences;
- ensure the latest attempt is retryable;
- reject or idempotently return an already queued retry;
- apply rate limits;
- create a new attempt and outbox command;
- never resend a channel that already succeeded unless an authorised support workflow permits it.

### 11.6 Preferences link

The Inbox may link to the existing notification-preferences API and screen. Retry must not silently
enable a channel the subscriber disabled or re-subscribe an unsubscribed destination.

---

## 12. Example Alert Projection

```json
{
  "id": "alrt_01JXYZ",
  "type": "TENDER_PUBLISHED",
  "generatedAt": "2026-09-08T04:16:00Z",
  "readAt": null,
  "title": "Provision Of Vendor Management System",
  "procurement": {
    "subjectType": "TENDER",
    "referenceNumber": "COB/ICT/VM/2026",
    "entityName": "City Of Bulawayo",
    "publishedAt": "2026-09-08T00:00:00Z",
    "closesAt": "2026-09-29T09:45:00Z",
    "sourceStatus": "Published",
    "lifecycleState": "OPEN",
    "sourceName": "PRAZ e-GP",
    "sourceUrl": "https://egp.praz.org.zw/..."
  },
  "currentSourceUpdate": {
    "lastObservedAt": "2026-09-08T06:00:00Z",
    "lastUpdatedAt": "2026-09-08T05:39:00Z",
    "addendumCount": 1
  },
  "matches": [
    {
      "trackedCategoryId": "cat_software",
      "trackedCategoryName": "Software Development and Computer Applications",
      "sourceCategoryText": "Software Development and Computer Applications, Computer Security Systems Installation and Consultants Services",
      "matchType": "EXACT_OR_MAPPED_CATEGORY",
      "matcherVersion": "category-v1"
    }
  ],
  "deliverySummary": "SUCCESSFUL",
  "deliveries": [
    {
      "channel": "EMAIL",
      "destinationMasked": "ln***@business.co.zw",
      "messageSnapshot": {
        "templateKey": "tender-published-email",
        "templateVersion": "1.2",
        "format": "STRUCTURED_EMAIL",
        "subject": "New tender match",
        "contentFingerprint": "sha256:..."
      },
      "status": "DELIVERED",
      "sentAt": "2026-09-08T04:17:00Z",
      "deliveredAt": "2026-09-08T04:18:00Z"
    },
    {
      "channel": "WHATSAPP",
      "destinationMasked": "+263 7* *** 482",
      "messageSnapshot": {
        "templateKey": "tender-published-whatsapp",
        "templateVersion": "1.1",
        "format": "STRUCTURED_WHATSAPP",
        "buttons": [
          {
            "type": "URL",
            "label": "View Tender Details",
            "url": "https://egp.praz.org.zw/..."
          }
        ],
        "contentFingerprint": "sha256:..."
      },
      "status": "READ",
      "sentAt": "2026-09-08T04:17:00Z",
      "deliveredAt": "2026-09-08T04:19:00Z",
      "readAt": "2026-09-08T04:21:00Z"
    }
  ]
}
```

The abbreviated `messageSnapshot` objects above also contain the structured, display-safe content
sent through each channel; they are shortened here for readability. Stored HTML must never be
injected into the page without sanitisation. Prefer rendering trusted React components from a
versioned structured snapshot.

All timestamps are transported in UTC with offsets and rendered in the subscriber’s configured
timezone.

---

## 13. Provider Integration and Webhooks

### 13.1 Outbound submission

- Use provider-specific adapters behind a channel-neutral delivery service.
- Store the rendered, structured message snapshot, template version, and content fingerprint for
  every delivery attempt.
- Store provider acceptance and message identifiers.
- Apply connect, request, and overall timeouts.
- Distinguish retryable network/provider failures from permanent destination failures.
- Avoid logging message bodies or unmasked destinations at normal log levels.

### 13.2 Inbound callbacks

- Verify signatures according to the provider’s current official process.
- Reject invalid signatures before state mutation.
- Preserve the raw request securely for a limited diagnostic period.
- Deduplicate callbacks.
- Resolve callbacks through provider and provider-message ID.
- tolerate out-of-order callbacks without regressing status;
- record unknown message IDs for investigation without creating subscriber alerts;
- process callbacks asynchronously after safe receipt when practical;
- return provider-required response codes promptly.

### 13.3 Failure normalisation

Map provider-specific errors into stable internal categories such as:

- invalid destination;
- unverified destination;
- unsubscribed or opted out;
- provider rate limit;
- provider temporary outage;
- rejected template;
- bounced mailbox;
- mailbox full;
- blocked WhatsApp recipient;
- expired message window or policy restriction;
- unknown provider failure.

Expose only safe, actionable explanations to subscribers.

---

## 14. Retry Policy

Background retries and subscriber-requested retries are distinct.

### 14.1 Automatic retries

- Retry only transient errors.
- Use exponential backoff with jitter.
- Cap attempt count and total retry duration.
- Stop when a delivery succeeds, the tender becomes irrelevant, the destination opts out, or the
  alert passes an age threshold.
- Do not retry a message after the tender deadline if its value depended on acting before that
  deadline.

### 14.2 Manual retries

- Available only after automatic processing reaches a user-actionable state.
- Revalidate preference, consent, entitlement, destination, and tender relevance.
- Show immediate queued feedback.
- Create a fresh attempt linked to the original failed attempt.
- Audit who requested the retry and when.

---

## 15. Security and Privacy

- Authenticate every Inbox request.
- Authorise every alert by subscriber ownership.
- Enforce plan entitlements on the backend, not only in the sidebar.
- Mask destinations in API projections intended for standard UI display.
- Encrypt sensitive destination data or reference a protected contact record.
- Do not expose provider access tokens, signatures, raw errors, or raw provider payloads.
- Treat rendered notification snapshots as subscriber data: authorise access, apply retention, and
  never render untrusted stored HTML directly.
- Validate/allowlist official source URLs.
- Protect state-changing requests against CSRF where cookie authentication is used.
- Rate-limit search, bulk updates, and manual retry.
- Record security-relevant actions without logging sensitive content.
- Respect email and WhatsApp opt-out state before every send and retry.
- Do not let a billing transition silently restore a previously opted-out channel.

Delivery history is personal data. Retention and deletion behaviour must align with the privacy
policy, operational audit needs, and applicable legal requirements.

---

## 16. Persistence and Query Performance

Recommended indexes include:

- `subscriber_alert(subscriber_id, generated_at desc)`;
- `subscriber_alert(subscriber_id, read_at, generated_at desc)`;
- `subscriber_alert(alert_event_id)`;
- `subscriber_alert_match(subscriber_alert_id, tracked_category_id)`;
- `alert_event(event_type, detected_at desc)`;
- `alert_delivery_attempt(subscriber_alert_id, channel, attempt_number desc)`;
- `alert_delivery_attempt(provider, provider_message_id)`;
- searchable tender reference, normalised entity, awardee, and title fields;
- partial indexes for unread or failed records where justified by measured queries.

Use cursor pagination based on stable `(generated_at, id)` ordering. Offset pagination will degrade and
can duplicate or skip records when new alerts arrive during navigation.

Summary counts and the first result page should be internally consistent enough that a new alert
does not create visibly contradictory UI. Exact transactional snapshot consistency is optional if
the API communicates live updates cleanly.

---

## 17. Observability and Operations

Track:

- alerts generated by type and plan;
- duplicate events rejected;
- subscribers matched per event;
- delivery attempts by channel and state;
- time from detection to queue, submission, and delivery;
- bounce, failure, suppression, and retry rates;
- webhook verification failures;
- duplicate and out-of-order callback rates;
- unknown provider message IDs;
- Inbox list/detail latency and errors;
- manual retry rate and success;
- source links reported invalid;
- read-state update failures.

Alert on:

- sustained channel failure increase;
- growing outbox or delivery queue age;
- missing provider callbacks beyond expected norms;
- webhook signature failures above baseline;
- duplicate-send anomalies;
- closing-soon alerts delivered after deadlines;
- subscriber data-leakage test failures.

Operational support tooling should locate a delivery by subscriber alert ID or provider message ID
without exposing sensitive information broadly.

---

## 18. Frontend Requirements

- Render one top-level group per subscriber alert, with Email and WhatsApp as child notification
  records rather than duplicate alerts.
- Render every alert type as a concise summary with an explicit **View Alert** disclosure that
  reveals its channel snapshots.
- Reproduce the actual branded Email layout and actual WhatsApp message for new-tender,
  closing-soon, and award alerts.
- Render configured WhatsApp template buttons inside the message bubble; preserve their sent label,
  type, and resolved destination URL.
- Use an accessible disclosure pattern for all alert types.
- Place **View Tender Details** or **View Award Details** beside **View Alert**, and keep the external
  action independent from expand/collapse.
- Lazy-load detail only for alert types whose list projections are intentionally compact.
- Mark read only after a successful state update, with optimistic rollback on failure.
- Preserve expanded state during a non-destructive refresh where practical.
- Display channel states separately.
- Clearly separate immutable sent-message snapshots from current source updates.
- Provide a user-safe failure explanation and recovery action.
- Avoid colour-only unread, urgency, failure, or success indicators.
- Show skeletons rather than zero-value flashes.
- Use stacked facts and delivery records on mobile.
- Ensure long official category names wrap without breaking the layout.
- Retain visible focus and readable contrast in light and dark themes.
- Provide accessible Previous/Next pagination with disabled boundary states and a clear result range.

---

## 19. Empty, Loading, and Error States

### 19.1 No alerts yet

> **Your Alert Inbox is ready.**
> TenderBell will add alerts here when a published tender or award matches one of your tracked
> categories.

Provide actions to review categories and delivery preferences.

### 19.2 No filter results

Show active filters and a **Reset filters** action. Do not imply that no alerts have ever existed.

### 19.3 Details unavailable

Keep the historical alert and available message snapshots visible, explain that current detail
could not load, and offer retry.

### 19.4 Delivery history delayed

Show the last known state and **Awaiting provider update** rather than incorrectly marking the
delivery failed.

### 19.5 Source unavailable

Keep the historical alert and identify that the official link is temporarily unavailable or has
changed. Do not remove the Inbox record.

---

## 20. Testing Requirements

### 20.1 Unit tests

- alert event-key generation;
- exact and mapped category evidence;
- reminder-window eligibility;
- time-zone and deadline calculations;
- delivery transition policy;
- delivery roll-up calculation;
- user-safe failure mapping;
- destination masking;
- retry eligibility;
- filter parsing and cursor encoding.

### 20.2 Repository/integration tests

- unique subscriber/event constraint;
- repeated scheduler execution creates no duplicate alert;
- outbox and subscriber alert commit atomically;
- cursor ordering under concurrent alert creation;
- unread and read filters;
- cross-subscriber access returns `404`;
- retry creates a new attempt and preserves failed history;
- out-of-order callback cannot regress Delivered to Sent;
- duplicate webhook receipt has no duplicate effect;
- opt-out suppresses delivery and manual retry;
- a changed closing date does not spam repeated reminders.

### 20.3 API contract tests

- grouped list projection with channel children;
- type-specific detail projection;
- immutable channel message snapshots and separately projected current source updates;
- summary counter consistency;
- supported filter combinations;
- invalid cursor and date ranges;
- optimistic update conflicts;
- allowed-action calculation;
- source URL validation;
- masked PII only.

### 20.4 End-to-end tests

- open and close all three alert types using **View Alert**;
- verify both Email and WhatsApp snapshots appear for a multi-channel alert;
- compare rendered snapshots with stored sent-message fixtures;
- verify a failed channel shows its attempted snapshot, failure state, and permitted recovery action;
- official link does not toggle the card;
- unread changes to read according to the approved visibility/disclosure rule;
- one procurement event delivered by Email and WhatsApp remains one top-level Inbox alert;
- sent snapshots remain unchanged when current tender data changes;
- filters and reset;
- Previous/Next pagination, disabled boundary states, and filter preservation;
- delivery issue recovery;
- responsive mobile layout;
- keyboard and screen-reader disclosure semantics;
- light and dark mode;
- deep link to one expanded alert.

---

## 21. Delivery Phases

### Phase 1 — Durable Inbox foundation

- event, subscriber alert, match evidence, outbox, and delivery-attempt tables;
- three initial alert types;
- idempotent generation;
- chronological grouped list and type-appropriate channel snapshots;
- unread/read state;
- email and WhatsApp channel records;
- basic filters and cursor pagination.

### Phase 2 — Provider status integration

- verified delivery webhooks;
- sent, delivered, read, bounce, and failure transitions;
- delivery roll-ups and issue counter;
- safe error explanations;

### Phase 3 — Recovery and operations

- automatic retry policy;
- subscriber-requested retry;
- destination verification paths;
- operational search and reconciliation;
- delivery monitoring and alerts.

### Phase 4 — Refinement

- saved filters;
- bulk actions beyond mark-read;
- amendment alerts if approved;
- cross-channel read-state decision;
- deep-linked support cases;
- subscriber-tested density and ordering.

---

## 22. Acceptance Criteria

The Alert Inbox is ready for release when:

1. The three initial alert types use consistent product labels.
2. Each procurement event appears once, with Email and WhatsApp represented as child notification
   records.
3. Every alert provides adjacent official-details and **View Alert** actions, and the external link
   does not expand the alert.
4. Each channel preserves the exact versioned content snapshot that TenderBell sent.
5. Selecting **View Alert** reveals both Email and WhatsApp snapshots when both channels were sent.
6. The Email snapshot reproduces the actual branded email and the WhatsApp snapshot reproduces the
   actual sent message.
7. Each WhatsApp snapshot includes its configured **View Tender Details** or **View Award Details**
   template action button and resolved, validated official URL.
8. Current tender updates are timestamped and displayed separately from historical messages.
9. The official source action appears once in the Inbox action row and does not toggle a disclosure.
10. Inbox, procurement, and delivery states are modelled separately.
11. Each enabled channel has an independent delivery record and timestamps.
12. `Delivered` appears only after explicit provider confirmation.
13. Provider callbacks are signature-verified, idempotent, and safe against out-of-order events.
14. Repeated matching or scheduler execution cannot create duplicate subscriber alerts.
15. A delivery retry creates a new attempt and preserves the original failure.
16. Successful channels are not resent when retrying one failed channel.
17. Opt-out and channel preferences are revalidated before every attempt.
18. Destinations are masked in the subscriber UI and normal application logs.
19. All list and detail requests enforce subscriber ownership.
20. Search, filters, stable ordering, and cursor pagination work together.
21. Long titles and categories remain usable on mobile.
22. Empty, delayed, partial-delivery, source-error, and loading states are designed.
23. Alert generation and delivery latency are observable.
24. Closing-soon alerts are not delivered after the applicable deadline.
25. Every alert retains traceable source and match evidence.

---

## 23. Open Questions and Decision Log

| # | Question | Status | Current direction | Required decision or validation |
|---:|---|---|---|---|
| 1 | Does selecting **View Alert** automatically mark it read? | Proposed | Yes, after the message snapshots load successfully. | Validate subscriber expectations and accessibility behaviour. |
| 2 | Can more than one alert remain expanded? | Proposed | Yes. | Confirm after testing long Inbox pages. |
| 3 | How long are Inbox alerts retained? | Open | Retain long enough to support award and delivery history; no archive or permanent-delete action is planned initially. | Align product value, privacy, legal, and storage requirements. |
| 4 | Which email and WhatsApp providers will supply delivery callbacks? | Open | Use provider-neutral internal states. | Select providers and map their exact callback semantics. |
| 5 | Should email open tracking be enabled? | Proposed against | Do not equate an email open pixel with reliable read status. | Complete privacy and product review before enabling. |
| 6 | Should reading a WhatsApp message mark the portal alert read? | Open | Keep channel read and Inbox read separate initially. | Validate whether cross-channel read state helps or surprises users. |
| 7 | Which failures are subscriber-retryable? | Open | Temporary failures only; invalid or opted-out destinations require correction. | Define after provider selection. |
| 8 | Should tender amendment become a fourth alert type? | Open | Defer until the three primary types are stable. | Audit whether the source exposes material amendments reliably. |
| 9 | Can award notices contain multiple lots or awardees? | Open | Model one-to-many capability until source structure is verified. | Profile real award notices and define event granularity. |
| 10 | Should Pro subscribers receive a lightweight Inbox outside the Max portal? | Open | Current scope guarantees the full Inbox in Max. | Decide whether delivery history is also a Pro self-service feature. |
