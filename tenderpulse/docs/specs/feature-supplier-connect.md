# Feature Proposal: Supplier Connect

**Status:** Proposal — vision-level, not scoped or committed
**Applies to:** Future roadmap beyond the current MVP (see "Relationship to MVP scope" below)
**Related:** [orchestrator-workflow.md](orchestrator-workflow.md), [aggregation-policy.md](aggregation-policy.md), [MVP_CHECKLIST_BOARD.md](MVP_CHECKLIST_BOARD.md)

---

## Relationship to MVP scope

This document captures an external feature pitch as-is, for reference. It is **not** an
accepted spec and has **no** task cards on the [MVP checklist board](MVP_CHECKLIST_BOARD.md).

- The current TenderBell MVP scrapes **PRAZ e-GP only** and is limited to tender **alerts**
  (find + award notifications) — see [zw-tender-sources.md](zw-tender-sources.md) and
  [aggregation-policy.md](aggregation-policy.md).
- CONTRIBUTING.md's Phase 2 rule ("Do not expand into Phase 2 — tender registration,
  application checklist, apply templates — unless the issue explicitly says so") already fences
  off scope this size. Supplier Connect is larger than Phase 2: it's a distinct marketplace
  product (supplier directory, RFQs, quote comparison, commissions) layered on top of the
  tender-alert product.
- This aligns with, and elaborates, the private-sector marketplace direction already discussed
  informally for TenderBell's long-term future (service-provider registration and
  quotation-request matching beyond government tenders).
- Before any part of this becomes buildable work, it needs to be broken into scoped issues with
  concrete acceptance criteria and test cases, per the standard `/loop` workflow — this document
  is not sufficient input for that on its own (no data model, no auth/payment design, no
  Zimbabwe-specific legal/compliance review for commissions or subscriptions).

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

A diversified monetization strategy modeled on proven B2B marketplace patterns:

| Stream | Description |
|---|---|
| **Supplier subscriptions** (primary) | Freemium model — free tier (basic listing, limited lead access) and paid tiers (premium storefronts, analytics, priority placement, unlimited leads); tiered pricing in USD or ZiG aligned with PRAZ registration fees |
| **Transaction commissions** | Percentage of Gross Merchandise Value (GMV) on completed transactions; take rates roughly 3–15% depending on category complexity and value-added services |
| **Lead generation fees** | Pay-per-lead / pay-per-quote, particularly for services and high-consideration B2B categories |
| **Featured & sponsored listings** | Advertising revenue from sponsored supplier placements and category sponsorships |
| **Market opportunity insights reports** | Premium data reports sold to investors, business strategists, and procurement professionals |
| **Advertising & promotions** | Sponsored listings and featured placements for suppliers seeking visibility |

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
(small distributor currently relying on referrals and cold outreach for leads). The review
validated the core pain point and surfaced three gaps the original pitch did not address. Each
gap must be resolved before this can be scoped into buildable work; the badge removal above is
the one already acted on.

**Pain point validated:** fragmented, manual, WhatsApp/phone-based supplier discovery and quote
comparison is a real, recurring pain for buyers — comparing quotes phoned or WhatsApped in
different formats, with no easy side-by-side view, wastes real time. This matches the manual
workaround already seen on TenderBell's tender-alert side, where customers are served by hand
today.

**Gap 1 — trust signal conflated with tender eligibility (resolved: badge dropped).** A PRAZ
vendor number proves a supplier can bid on *government* tenders; it says nothing about
reliability for *private* buyer-to-supplier trade, which is what this feature actually
transacts. **How it must be solved:** trust comes from a buyer-submitted ratings/reviews system
tied to completed transactions, not a government registration lookup.

**Gap 2 — no cold-start / bootstrap plan.** A two-sided marketplace has no value to either side
until both sides have real listings; the pitch describes the mature-state network effect but not
how the platform gets there from zero. **How it must be solved:** any scoped version needs an
explicit bootstrap sequence before the general matching algorithm is built — e.g. seed the
supplier side from existing outreach contacts ([zw-outreach-list.md](zw-outreach-list.md)) and
the buyer side from TenderBell's existing manually-served customers, run a manually-brokered
pilot (a human matching a handful of real RFQs to real suppliers) to prove demand, and only then
invest in automated matching.

**Gap 3 — commission rates unvalidated against local trade margins.** The 3–15% GMV take rate is
carried over from generic B2B marketplace benchmarks, not tested against real margins in
Zimbabwean trading categories — a flat rate near the high end could exceed a supplier's entire
margin on a low-margin order (e.g. building materials). **How it must be solved:** validate
proposed rates per category against real supplier margin data (starting with whatever categories
the Gap 2 pilot surfaces) before any commission structure ships, and expect rates to vary by
category rather than use one range platform-wide.

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
- Payments/commission handling — currency (USD/ZiG), settlement, and Zimbabwe-specific
  compliance requirements; this is auth/payment-adjacent and would trigger the Reviewer gate
  per the orchestrator workflow's conditional triggers.
- Legal review for marketplace terms, commission structure, and any claims tied to PRAZ
  branding or compliance status.
- How this affects [privacy-note.md](privacy-note.md) given supplier PII and buyer inquiry data
  now flowing through the platform.
