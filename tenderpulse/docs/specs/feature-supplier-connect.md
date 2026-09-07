# Feature Proposal: Supplier Connect

**Status:** Proposal — bundled with the TenderBell roadmap, but build-later (gated on TenderBell
traction, see "Relationship to MVP scope" below)
**Applies to:** Future roadmap beyond the current MVP (see "Relationship to MVP scope" below)
**Related:** [orchestrator-workflow.md](orchestrator-workflow.md), [aggregation-policy.md](aggregation-policy.md), [MVP_CHECKLIST_BOARD.md](MVP_CHECKLIST_BOARD.md)

---

## Relationship to MVP scope

This document captures an external feature pitch as-is, for reference. It is **not** an
accepted spec and has **no** task cards on the [MVP checklist board](MVP_CHECKLIST_BOARD.md).

**Build sequencing decision (2026-09-08):** Supplier Connect stays bundled with TenderBell as
one product story — same brand, same buyer base, the Find → Match → Quote → Compare → Win
narrative in "Strategic synergy with TenderBell" below — but it is explicitly **build-later**,
not simultaneous with the current MVP. It becomes eligible for scoping only once a traction gate
is met: **TenderBell has paying/retained customers beyond today's 2 manually-served ones.** That
gate matters for two reasons — it proves the core alert product before a second, much larger
product (transactions, ratings, payments) is layered on top, and it hands Supplier Connect a
real buyer base to seed its own cold-start problem (Gap 2 in "Customer validation notes" below)
instead of bootstrapping two unproven products at once.

- The current TenderBell MVP scrapes **PRAZ e-GP only** and is limited to tender **alerts**
  (find + award notifications) — see [zw-tender-sources.md](zw-tender-sources.md) and
  [aggregation-policy.md](aggregation-policy.md).
- CONTRIBUTING.md's Phase 2 rule ("Do not expand into Phase 2 — tender registration,
  application checklist, apply templates — unless the issue explicitly says so") already fences
  off scope this size. Supplier Connect is larger than Phase 2: it's a distinct marketplace
  product (supplier directory, RFQs, quote comparison, subscriptions) layered on top of the
  tender-alert product.
- This aligns with, and elaborates, the private-sector marketplace direction already discussed
  informally for TenderBell's long-term future (service-provider registration and
  quotation-request matching beyond government tenders).
- Before any part of this becomes buildable work, it needs to be broken into scoped issues with
  concrete acceptance criteria and test cases, per the standard `/loop` workflow — this document
  is not sufficient input for that on its own (no data model, no auth/payment design, no
  Zimbabwe-specific legal/compliance review for subscription billing).

---

## Service overview

Supplier Connect is a proposed B2B supplier marketplace and matching platform intended to solve
the manual, fragmented process of supplier discovery and quotation management for Zimbabwean
businesses. The idea is to digitize the procurement workflow end-to-end — from identifying
vetted suppliers to submitting and comparing quotes in one centralized dashboard.

## Core features & capabilities

### 1. Intelligent supplier-buyer matching

A matching algorithm connects buyers with relevant suppliers based on category, location, and
business needs, eliminating manual supplier discovery — comparable in approach to established
B2B marketplaces (e.g. IndiaMart) that connect buyers and suppliers across product categories
and geographies.

### 2. Verified supplier directory

A searchable database where suppliers list products and services. Trust is signaled through a
buyer-facing reputation system — ratings and reviews tied to completed transactions — rather
than through government registration status. (An earlier version of this proposal used a
"PRAZ-verified" badge as the trust signal; see "Customer validation notes" below for why that
was dropped — a PRAZ vendor number proves tender eligibility, not commercial reliability.)

### 3. One-click RFQ (Request for Quotation)

Replaces the manual, multi-step quotation process. A buyer posts a request and the system
automatically routes the RFQ to matched suppliers, reducing procurement time and administrative
burden.

### 4. Centralized quote management dashboard

A single place for buyers to receive, compare, and analyze quotes from multiple suppliers
side by side, replacing spreadsheets and scattered email threads.

### 5. Market opportunity insights integration

Leverages aggregated tender data for market intelligence:

- **Sector demand rankings** — which industries have the most tenders
- **Budget analysis** — which sectors have the highest/lowest barriers to entry
- **Regional trends** — which areas are publishing the most tenders
- **Entry-level opportunities** — which sectors are accessible to new businesses

### 6. Supplier prequalification hub

Guides suppliers through obtaining PRAZ vendor numbers and other certifications now required
for government business in Zimbabwe, simplifying digital registration.

## Revenue model

**Decision (2026-09-08):** the sole revenue stream is a **monthly supplier subscription**.
Transaction commissions and pay-per-lead fees — both in the original pitch — were considered
and dropped; see Gaps 3–5 in "Customer validation notes" below for why.

| Stream | Description |
|---|---|
| **Supplier subscriptions** (sole stream) | Freemium model, billed **monthly** — free tier (basic listing, limited lead access) and paid tiers (premium storefronts, analytics, priority placement, unlimited leads); priced in USD or ZiG |
| **Featured & sponsored listings** | Optional add-on — sponsored supplier placements and category sponsorships, sold on top of a subscription, not a replacement for one |
| **Market opportunity insights reports** | Premium data reports sold to investors, business strategists, and procurement professionals — independent of the supplier-facing subscription |

~~Transaction commissions~~ and ~~lead generation fees~~ — dropped (see Gaps 3–5 below).

## Strategic synergy with TenderBell

Supplier Connect is pitched as complementary to TenderBell, forming an end-to-end procurement
ecosystem:

| Stage | TenderBell | Supplier Connect |
|---|---|---|
| Find | ✅ Tender alerts | — |
| Match | — | ✅ Supplier discovery |
| Quote | — | ✅ One-click RFQ |
| Compare | — | ✅ Quote dashboard |
| Win | ✅ Award alerts | — |

The pitch assumes a network effect: more TenderBell buyers attract more suppliers to Supplier
Connect, and vice versa. Scope would include RFQ processing, quote comparison, contract
negotiation, and supply chain coordination workflows.

## Strategic alignment with government initiatives

Supplier Connect is positioned to align with Zimbabwe's digital procurement transformation —
the eGP system's reported 30% increase in bidder registrations reflects broader SME
participation in digital procurement. The "Supplier prequalification hub" feature helps
suppliers register for PRAZ vendor numbers as part of that shift, but PRAZ registration is
treated purely as a tender-eligibility credential in this proposal, not as the marketplace's
trust mechanism (see "Customer validation notes" below).

---

## Customer validation notes (buyer/supplier persona review)

Before iterating further, this proposal was reviewed against two target personas — a buyer
(procurement lead at a mid-size Zimbabwean firm sourcing materials/services) and a supplier
(small distributor currently relying on referrals and cold outreach for leads), across two
passes. The review validated the core pain point and surfaced five gaps the original pitch did
not address. Every gap below has since been resolved by either dropping the badge (Gap 1) or
simplifying the revenue model to a flat monthly subscription (Gaps 3–5).

**Pain point validated:** fragmented, manual, WhatsApp/phone-based supplier discovery and quote
comparison is a real, recurring pain for buyers — comparing quotes phoned or WhatsApped in
different formats, with no easy side-by-side view, wastes real time. This matches the manual
workaround already seen on TenderBell's tender-alert side, where customers are served by hand
today.

| # | Gap | Status | What we found | Resolution |
|---|---|---|---|---|
| 1 | Trust signal conflated with tender eligibility | ✅ Resolved | A PRAZ vendor number proves a supplier can bid on *government* tenders; it says nothing about reliability for *private* buyer-to-supplier trade, which is what this feature actually transacts. | Badge dropped. Trust now comes from a buyer-submitted ratings/reviews system tied to completed transactions, not a government registration lookup. |
| 2 | No cold-start / bootstrap plan | 🔲 Open | A two-sided marketplace has no value to either side until both sides have real listings; the pitch describes the mature-state network effect but not how the platform gets there from zero. | Needs an explicit bootstrap sequence before the general matching algorithm is built — e.g. seed the supplier side from existing outreach contacts ([zw-outreach-list.md](zw-outreach-list.md)) and the buyer side from TenderBell's existing manually-served customers, run a manually-brokered pilot (a human matching a handful of real RFQs to real suppliers) to prove demand, and only then invest in automated matching. |
| 3 | Commission rates unvalidated against local trade margins | ✅ Resolved | The original 3–15% GMV take rate was carried over from generic B2B marketplace benchmarks, not tested against real margins in Zimbabwean trading categories — a flat rate near the high end could exceed a supplier's entire margin on a low-margin order (e.g. building materials). | Commission dropped. Revenue model simplified to a flat monthly subscription (see "Revenue model" above), which removes margin-erosion risk entirely instead of tuning it per category. |
| 4 | Commission tied to quote acceptance, not to whether the supplier actually got paid | ✅ Resolved | A second supplier-persona pass surfaced this: a GMV commission charges the supplier the moment a quote is accepted, but the supplier's real risk is buyer non-payment or late payment after delivery — a problem the platform did nothing to solve. | A flat monthly subscription doesn't depend on any single transaction completing or being paid, so this misalignment is moot — revenue no longer rides on the supplier's collection risk. |
| 5 | Disintermediation: repeat business would predictably move off-platform | ✅ Resolved | Once a buyer and supplier complete one transaction through the platform, neither has a reason to pay commission again for repeat orders. A GMV-commission model would need to either accept steady revenue leakage or attempt "non-circumvention" contract terms against small suppliers — an enforcement fight the platform likely loses. | A flat monthly subscription doesn't try to capture a cut of every transaction, so there's nothing to lose to disintermediation — the platform earns on ongoing access to leads and tools, not on policing what suppliers do with a relationship after the first match. |

---

## Operational requirements (platform admin review)

Reviewed from the perspective of whoever actually runs Supplier Connect day to day — a small
team, plausibly sharing operational load with TenderBell rather than staffing it separately.
These aren't buyer/supplier viability gaps like the table above; they're what blocks a real
launch regardless of product-market fit.

- **Moderation queue** for new supplier listings and reviews — reviewed before they go live, not
  after, to catch fake suppliers, spam listings, and the retaliation-review risk flagged in Gap 1.
- **RFQ rate-limiting** on the buyer side, with an admin-tunable cap — addresses the low-intent
  RFQ problem in "Open questions" below and guards against scraping/abuse.
- **Suspend/kill switch** per account, independent of any formal dispute-resolution process —
  an active-harm listing can't wait on a dispute to resolve.
- **Billing operations**: dunning/retry on failed monthly payments, a grace period before
  auto-downgrade to free tier, and free-tier abuse detection (one supplier running multiple free
  accounts to dodge lead limits).
- **Subscription-appropriate metrics**: subscriber count, monthly churn, and free-to-paid
  conversion — GMV dashboards no longer apply now that commissions are dropped. Ideally surfaced
  in the same admin console TenderBell already uses, not a second one, given the team size this
  project assumes.
- **An instrumented traction gate**: turn the "TenderBell has paying/retained customers beyond
  today's 2" build-later condition (see "Relationship to MVP scope" above) into a simple
  report/dashboard tracking paying-customer count over time, so it's a number to check rather
  than a judgment call to remember.
- **Admin-side tooling for the review-dispute/appeal process** referenced in Gap 1 above —
  currently named as a requirement with no mechanism specified for who actually adjudicates it.

---

## Open questions before this can be scoped

These are not answered by the source pitch and would need resolution before any task cards are
written:

- Data model for suppliers, listings, RFQs, quotes, and ratings/reviews, and how it relates to
  the existing tender/notification schema ([domain-schema.md](domain-schema.md)).
- Design of the ratings/reviews system that replaces the dropped "PRAZ-verified" badge as the
  trust mechanism (see "Customer validation notes" above) — what's rated, when, by whom, and how
  abuse (fake reviews, retaliation reviews) is handled.
- Concrete bootstrap/pilot plan (Gap 2 above) — who runs the manually-brokered pilot, what
  counts as proof the demand is real, and what triggers investment in automated matching.
- Subscription billing handling — currency (USD/ZiG), monthly billing/collection mechanism, and
  Zimbabwe-specific compliance requirements; this is auth/payment-adjacent and would trigger the
  Reviewer gate per the orchestrator workflow's conditional triggers.
- Legal review for marketplace terms and subscription structure.
- Whether/how RFQ posting on the buyer side should carry any friction or verification, to limit
  low-intent inquiries suppliers have to spend time quoting against.
- How this affects [privacy-note.md](privacy-note.md) given supplier PII and buyer inquiry data
  now flowing through the platform.
