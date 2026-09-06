# WhatsApp Template: `tender_match_alert`

**Status:** Drafted, pending Meta approval
**Category:** Utility
**Language:** English (US) / `en_US`

Sent immediately when a Paid, opted-in subscriber's interest profile matches a new tender (TP-094).

## Header

```
Tender Match Alert
```

## Body

```
New tender match found

*Matched profile*: {{1}}: {{2}}
*Issuing authority*: {{3}}
*Deadline*: {{4}}

Open the link *{{5}}* for more details
```

## Footer

```
TenderBell — Never Miss a Tender Again
```

## Placeholders

| # | Meaning | Sample value (for Meta submission) |
|---|---------|--------------------------------------|
| `{{1}}` | Matched interest profile name | `IT Tenders Harare` |
| `{{2}}` | Tender title | `Supply and Delivery of Networking Equipment` |
| `{{3}}` | Issuing authority | `Ministry of ICT, Postal and Courier Services` |
| `{{4}}` | Deadline | `15 September 2026` |
| `{{5}}` | Official tender link (PRAZ e-GP) | `https://egp.praz.org.zw/tender/12345` |

Same 5 placeholders, same order, as [`tender_deadline_reminder`](tender_deadline_reminder.md) — both templates share one param-builder in code (TP-094/TP-095).

Once approved by Meta, update this file's **Status** line with the approval date, and record the exact approved name/language code/placeholder order in `WhatsAppTemplates.kt` (TP-092's closing acceptance criterion) — Meta occasionally adjusts wording slightly during review, so verify against what was actually approved, not this draft.
