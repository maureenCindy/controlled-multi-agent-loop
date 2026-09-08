# TenderBell Max Dashboard Specification

**Status:** Proposed for product and implementation review<br />
**Date:** 2026-09-08<br />
**Product:** TenderBell Max<br />
**Primary experience:** Authenticated Dashboard<br />
**Related specifications:** `market-opportunity-insights.md` and
`tracked-category-delivery-preferences.md`

---

## 1. Purpose

This specification defines the default Dashboard for a TenderBell Max subscriber. The Dashboard is
the subscriber’s category-focused action centre. It summarises what requires attention now across
the procurement categories the subscriber has chosen to track.

The Dashboard must answer:

1. What new opportunities match my business?
2. Which matching tenders require attention soon?
3. What happened in the award market for my categories?
4. What relevant procurement is planned but not yet published?
5. Which of my tracked categories currently shows the strongest opportunity signal?

The Dashboard is not a general national-procurement dashboard. Broad market totals, regional
exploration, complete annual-plan analysis, and detailed award-reason analysis belong in the
dedicated **Insights** views.

---

## 2. Product Principle

The Dashboard should feel like:

> **What matters to my business today?**

The Insights area should feel like:

> **What is happening in the wider procurement market, and what can I learn from it?**

This separation prevents a Max subscriber from seeing attractive but irrelevant national totals
before seeing their own matches and deadlines.

---

## 3. Terminology

Use **tracked categories** for the procurement categories selected by the subscriber.

Do not use **subscribed categories**, because the user subscribes to the Max plan and then tracks
categories within that subscription. Keeping those concepts distinct will make billing, category
management, and alert preferences easier to understand.

| Term | Meaning |
|---|---|
| Tracked category | A procurement/supplier category selected by the subscriber for matching |
| Matching tender | A unique published tender linked to at least one active tracked category |
| Closing soon | An open matching tender within the configured reminder window |
| Matching award | A published award notice linked to at least one tracked category |
| Planned opportunity | An annual procurement plan item matching a tracked category; not a live tender |
| Personal category insight | A data-backed observation scoped to one or more tracked categories |
| Opportunity briefing | The highest-priority plain-language summary generated for the subscriber |

---

## 4. Scope and Boundaries

### 4.1 Included

- active tracked-category scope;
- new matching tenders;
- closing-soon matching tenders;
- matching award notices;
- matching annual procurement plan items;
- category-level activity and trend comparison;
- personalised insight signals;
- links to alert, category, annual-plan, award, and market-insight views;
- source freshness and confidence where material.

### 4.2 Excluded from the default Dashboard

- total national market size;
- every active procurement category;
- all procuring entities;
- complete regional map;
- general top-spending buyer rankings;
- complete award-reason analysis;
- complete procurement-plan pipeline;
- unrelated categories;
- billing controls;
- detailed methodology documentation.

Those features remain accessible through the grouped sidebar navigation.

---

## 5. Information Hierarchy

The default page order is:

1. Page heading and category scope
2. Dashboard filters
3. Personal opportunity briefing
4. Immediate-action metrics
5. Opportunities requiring attention
6. Tracked category pulse
7. Upcoming planned matches
8. Personal category insights
9. Latest matching alerts

On smaller screens, the DOM and visual order must preserve urgent content before analytical
content. A subscriber should not scroll through general charts before reaching a closing deadline.

---

## 6. Dashboard Context and Filters

### 6.1 Default context

On first load, the Dashboard uses:

- all active tracked categories;
- the current week for newly published tenders and awards;
- the configured seven-day closing-soon window;
- the next calendar quarter for planned opportunities;
- all regions;
- the subscriber’s configured timezone.

Because different cards use different actionable windows, every metric must state its own period.
The filter bar must not imply that “This week” changes a card explicitly labelled “Next quarter.”

### 6.2 Filters

The Dashboard should expose only:

- all tracked categories or one tracked category;
- activity period;
- region;
- reset filters.

The **Manage categories** action opens the dedicated tracked-category view.

Complex analytical filters belong in Insights, including:

- procurement class;
- procurement method;
- procuring entity;
- award reason;
- source of funds;
- SPOC indicator;
- value bands;
- lifecycle state beyond immediate action states.

### 6.3 Filter behaviour

- Filters should update applicable cards without a full page reload.
- Active filter values should be represented in the URL or another restorable state mechanism.
- Category selections must be authorised against the subscriber’s active tracked categories.
- The selected category should persist when following a drill-down into Alerts or Insights.
- Reset returns to all tracked categories, the default activity period, and all regions.
- If a filter produces no results, show an explanatory empty state rather than zeros without context.

---

## 7. Personal Opportunity Briefing

### 7.1 Purpose

The briefing is the first narrative summary on the Dashboard. It should identify the most important
current signal across the subscriber’s tracked categories.

### 7.2 Required content

- tracked category or category group;
- plain-language finding;
- supporting tender, buyer, award, or plan evidence;
- time period;
- reason it matters;
- confidence or coverage label;
- primary action;
- secondary action where useful.

### 7.3 Example

> **ICT support services are leading your tracked categories this month.**
> Demand grew 19%, eight procuring entities published 12 matching tenders, and nine related
> requirements appear in current annual procurement plans.

Actions:

- See why this market fits
- View planned opportunities

### 7.4 Selection rules

Candidate briefings should be ranked by:

1. urgency of closing matching tenders;
2. material increase in relevant demand;
3. new high-relevance tenders;
4. upcoming procurement-plan matches;
5. material buyer activity in tracked categories;
6. material award-market change;
7. data confidence and freshness.

The same briefing should not remain pinned indefinitely. Record its generation time and suppress a
repeated message unless it remains materially important.

### 7.5 Fallback briefing

When there is no significant trend, use a factual summary rather than inventing importance:

> **Four new tenders match your tracked categories this week.**
> Two are in Software Development and two are in Consulting Services. The earliest closes on
> 14 September.

---

## 8. Immediate-Action Metrics

The Dashboard displays four metric cards.

| Metric | Default window | Definition | Primary action |
|---|---|---|---|
| New matching tenders | Current week | Unique tenders first published in the window that match at least one active tracked category | Open filtered Alert inbox |
| Closing soon | Next 7 days | Unique open matching tenders with a closing date inside the reminder window | View deadlines |
| New award notices | Current week | Unique award notices first published in the window and linked to a tracked category | Open filtered award alerts |
| Planned opportunities | Next calendar quarter | Unique annual procurement plan items matching tracked categories and scheduled for future publication | Open Annual procurement plans |

### 8.1 Counting rules

- Count a tender once in the total even if it matches multiple tracked categories.
- Category-level rows may each include the same tender; their sum can therefore exceed the unique
  Dashboard total.
- Deduplicate tender revisions and repeated ingestion.
- Count an award notice according to the source award-notice identity, not the number of notification
  deliveries.
- Count a plan item once per active plan version.
- Do not count superseded plan rows if the publisher’s versioning semantics have been established.
- Until APP/iAPP version behaviour is confirmed, identify potential duplicates and disclose the
  limitation rather than silently merging them.

### 8.2 Display rules

Each card must show:

- metric label;
- value;
- explicit period;
- whether filters apply;
- accessible icon or supporting text;
- click or keyboard action.

Do not display a trend percentage on an immediate-action card unless its comparison period is clear
and useful.

---

## 9. Opportunities Requiring Attention

### 9.1 Purpose

This is the main actionable list. It combines alert types only when combining them helps the
subscriber prioritise work.

### 9.2 Eligible items

- matching tenders closing soon;
- newly published high-relevance tenders;
- tender amendments affecting deadline or requirements;
- EOI or prequalification actions approaching a date;
- newly published matching award notices requiring review;
- exceptionally relevant planned requirements approaching their planned publication date.

### 9.3 Priority order

Recommended initial ordering:

1. open tender closing within 48 hours;
2. open tender closing within the configured reminder window;
3. EOI or prequalification deadline;
4. material amendment to a matching tender;
5. new high-confidence matching tender;
6. planned opportunity approaching expected publication;
7. matching award notice.

Within the same priority band, order by deadline, then match strength, then publication freshness.

### 9.4 Row content

- alert type;
- title or requirement;
- matched category;
- procuring entity;
- closing or event date;
- match strength where available;
- urgency label;
- link to the official record or appropriate detail view.

If a match-strength percentage is displayed, the matching method and factors must be explainable.
Do not use precise percentages for simple exact category matches unless the percentage has genuine
meaning.

---

## 10. Tracked Category Pulse

### 10.1 Purpose

The category pulse lets a subscriber compare tracked categories without opening a full analytical
view.

### 10.2 Columns

| Column | Definition |
|---|---|
| Tracked category | Subscriber-selected category using the preferred display name |
| New matches | Matching tenders first published in the selected activity period |
| Closing soon | Open matching tenders inside the configured reminder window |
| Demand trend | Change in unique published tender volume versus the comparable previous period |
| Planned | Future plan items within the planned-opportunity window |

Optional later columns:

- new award notices;
- active procuring entities;
- median disclosed award value;
- data confidence.

### 10.3 Behaviour

- Sort by urgency or new matching volume by default.
- Allow sorting by every numeric column.
- Selecting a row applies that category to the Dashboard.
- A separate action opens category management.
- Explain that row totals may overlap when one tender matches multiple categories.
- Use arrows plus text for trends; do not rely on colour alone.

---

## 11. Upcoming Planned Matches

### 11.1 Purpose

This module gives the subscriber preparation time before relevant requirements become live tenders.

### 11.2 Required fields

- requirement description;
- matched tracked category;
- procuring entity;
- planned tender-publication date;
- estimated budget amount and currency, where available;
- EOI or prequalification requirement;
- SPOC oversight indicator;
- procurement method;
- plan year and version;
- plan-to-tender linkage state;
- source record.

### 11.3 Trust rules

The module must prominently state:

> **Planned requirements are not live tenders and may change.**

Additional rules:

- Never label estimated budget as awarded or committed spend.
- Keep USD and ZWG values separate.
- Do not assign a region because of buyer headquarters unless labelled as buyer location.
- Annual procurement plan rows do not supply location directly.
- Present SPOC as oversight information, not a warning.
- Do not instruct the subscriber to submit a bid until a live tender is published.

### 11.4 Actions

- Follow planned requirement;
- Track procuring entity;
- Open annual plan record;
- View similar past tenders;
- Open full Annual procurement plans view.

---

## 12. Latest Matching Alerts

### 12.1 Purpose

The latest feed provides chronological confirmation of recent delivery activity. It is secondary to
the prioritised attention list.

### 12.2 Included alert types

- newly published tender alert;
- published tender closing-soon alert;
- tender award notice alert.

### 12.3 Display

Show approximately three to five items with:

- alert type;
- title;
- matched category;
- procuring entity or awardee context;
- event time;
- delivery channel/status where useful;
- View all alerts action.

The feed should not contain general market records outside the subscriber’s category scope.

---

## 13. Personal Category Insights

### 13.1 Purpose

This module previews deeper Max intelligence without turning the Dashboard into a complete analysis
workspace.

Display two or three concise signals selected from:

- demand change;
- buyer activity;
- relevant award-reason pattern;
- planned pipeline;
- regional change where defensible;
- response-window change;
- failed-procurement pattern;
- adjacent category suggestion.

### 13.2 Card pattern

Every signal should contain:

1. a plain-language finding;
2. one or two supporting facts;
3. the affected tracked category;
4. confidence or coverage when material;
5. a link to the relevant Insights screen.

Example:

> **Price leads published Electrical Products awards**
> Lowest evaluated price appears in 58% of classified award reasons in this tracked category.

The source’s original award-reason wording remains accessible in Award Intelligence.

---

## 14. Navigation and Drill-Down Behaviour

| Dashboard element | Destination |
|---|---|
| New matching tenders | Alert inbox filtered to new tenders |
| Closing soon | Alert inbox filtered to closing-soon tenders |
| New award notices | Alert inbox or Award Intelligence filtered to matching awards |
| Planned opportunities | Annual procurement plans filtered to tracked categories |
| Manage categories | Tracked categories |
| Opportunity briefing | Opportunity finder or relevant detailed insight |
| Category pulse row | Dashboard filtered to the selected category |
| Personal category insight | Relevant Market overview, Regional opportunities, Annual plans, or Award Intelligence view |
| View all alerts | Alert inbox preserving current category scope |

Browser Back and Forward actions should restore the previous view and filter state in the eventual
application.

---

## 15. Dashboard States

### 15.1 First-time Max subscriber

If no tracked categories exist, do not show empty metric cards. Show a guided setup state:

> **Choose the markets you want TenderBell to watch.**
> Add your first procurement categories to receive matches, deadlines, awards, and planned
> opportunity insights.

Primary action: **Add tracked categories**.

### 15.2 No activity in the selected period

Distinguish between:

- no matching records;
- no source records;
- source refresh delayed;
- filters excluding existing matches.

Offer useful next actions such as changing the date range, selecting all regions, or reviewing
tracked categories.

### 15.3 Partial data

If source values or annual plan fields are incomplete:

- show counts supported by reliable identity fields;
- show coverage beside affected value or insight metrics;
- do not display missing monetary values as zero;
- keep unknown currency separate;
- avoid an opportunity briefing whose evidence has Limited confidence.

### 15.4 Loading

- Preserve the page layout with skeleton states.
- Do not flash zeros before data loads.
- Allow independent modules to finish loading without blocking the entire Dashboard.
- Announce major asynchronous updates appropriately for assistive technology.

### 15.5 Source error or stale data

Show:

- the last successful refresh time;
- which source or module is affected;
- whether previously collected data is being displayed;
- a retry action where appropriate.

Do not present a failed source refresh as “No opportunities.”

---

## 16. Data and Calculation Requirements

The Dashboard depends on:

- subscriber identity and Max entitlement;
- active tracked categories and their taxonomy versions;
- matching rules and match evidence;
- normalised tender and award records;
- tender lifecycle status and closing dates;
- annual procurement plan items and versions;
- plan-to-tender and tender-to-award links;
- alert-generation and delivery records;
- source freshness;
- metric and insight-definition versions.

### 16.1 Dashboard summary response

The eventual application may retrieve the Dashboard through one composed backend response or a
small number of independently cacheable resources. The client should not calculate business-critical
counts from paginated table rows.

A Dashboard response should identify:

- subscriber timezone;
- selected filter context;
- tracked-category scope;
- metric values and their exact windows;
- briefing and its evidence identifiers;
- prioritised items;
- category pulse rows;
- planned matches;
- latest alert records;
- personal insight signals;
- source freshness and data-coverage metadata.

### 16.2 Authorisation

- Enforce Max access on the backend.
- Never trust a category identifier from the browser without confirming it belongs to the subscriber
  or is authorised for general Max analysis.
- Do not expose another subscriber’s profile, tracked categories, match evidence, or delivery history.
- Signed source links or internal identifiers must not weaken access controls.

---

## 17. Performance and Freshness

Recommended initial targets:

- useful Dashboard content visible within two seconds under normal conditions;
- filter interaction feedback within 100 milliseconds;
- cached summary response where insight calculation is expensive;
- alert and closing metrics refreshed after each successful monitoring cycle;
- procurement-plan matches refreshed after successful plan ingestion or relinking;
- source freshness displayed when outside the normal collection schedule.

Precomputed aggregates may be used, but the stored metric period and category scope must prevent
stale or cross-subscriber results.

---

## 18. Responsive and Accessible Presentation

### 18.1 Desktop

- Use a two-column action layout after the briefing and metrics.
- Keep Opportunities requiring attention in the wider column.
- Keep planned matches and concise signals in the supporting column.
- Use tables only where column comparison materially helps.

### 18.2 Mobile

Order content as:

1. heading and filters;
2. briefing;
3. immediate metrics;
4. opportunities requiring attention;
5. planned matches;
6. category pulse;
7. personal insights;
8. latest alerts.

Wide category tables may become stacked category cards rather than relying exclusively on horizontal
scrolling.

### 18.3 Accessibility

- Keyboard access for filters, metric cards, rows, and navigation;
- visible focus states;
- semantic headings and landmarks;
- alert types described in text, not colour alone;
- trend direction shown with words or icons plus accessible labels;
- sufficient contrast in light and dark modes;
- no automatic refresh that unexpectedly moves keyboard focus;
- responsive zoom without loss of content or function.

---

## 19. Analytics and Product Learning

Measure whether the Dashboard leads to useful action:

- Dashboard viewed;
- filter changed;
- briefing opened;
- official tender details opened;
- closing-soon item opened;
- category pulse row selected;
- planned requirement followed;
- tracked category added or removed;
- personal insight opened;
- Alert inbox opened;
- no-result recovery action used.

Events must not capture sensitive free text or unnecessarily duplicate tender content. Product
analytics should distinguish an interaction with sample/demo content from production subscriber
activity.

---

## 20. Acceptance Criteria

The Dashboard is ready for implementation when:

1. Every visible count is scoped to active tracked categories.
2. General national market metrics appear only in Insights.
3. Each metric states its own actionable time window.
4. A tender matching multiple categories is counted once in Dashboard totals.
5. Category pulse rows disclose that their totals can overlap.
6. Closing-soon status is derived only for open tenders with a valid closing date.
7. Planned opportunities are clearly distinguished from live tenders.
8. USD, ZWG, and unknown-currency values are not combined.
9. SPOC is presented as an oversight indicator rather than a negative warning.
10. The attention list has deterministic and testable priority rules.
11. Opportunity briefing evidence is traceable to source records and metric definitions.
12. Low-confidence data cannot produce a high-confidence briefing.
13. Every summary item has a useful drill-down destination.
14. Empty, loading, partial-data, stale-source, and error states are designed.
15. Desktop and mobile reading order prioritises urgent action.
16. Max access and subscriber-specific data are enforced on the backend.
17. Light and dark modes meet accessible contrast requirements.

---

## 21. Open Questions and Decision Log

| # | Question | Status | Current direction | Required decision or validation |
|---:|---|---|---|---|
| 1 | What is the default closing-soon window? | Proposed | Seven calendar days. | Validate against subscriber preparation needs and allow configuration later. |
| 2 | Should Dashboard activity default to this week or the last seven rolling days? | Open | The mock uses **This week**. | Test which interpretation subscribers understand more quickly. |
| 3 | How should tenders matching multiple tracked categories appear in Category Pulse? | Proposed | Include the tender in every relevant category row but once in overall totals. | Confirm and add an explanatory tooltip. |
| 4 | Is match strength available and meaningful enough to show as a percentage? | Open | Display only when supported by explainable weighted matching. | Audit current matching inputs and calibration. |
| 5 | How many attention items should appear before “View all”? | Proposed | Four on desktop, three on mobile. | Validate through responsive design testing. |
| 6 | Should planned matches support “Follow” before a tender is published? | Proposed | Yes, with an alert when a confidently linked live tender appears. | Define follow lifecycle and failed-link correction behaviour. |
| 7 | Should users be able to reorder tracked categories? | Open | Sort Category Pulse by urgency or match volume initially. | Test whether manual ordering adds meaningful value. |
| 8 | Which insight types qualify for the personal briefing? | Proposed | Urgency, demand, buyer, planned pipeline, and award signals with sufficient confidence. | Establish materiality thresholds using real data. |
| 9 | Should delivery status appear on the Dashboard? | Proposed | Show only delivery failures or required verification; keep normal preferences in Manage Alerts. | Define actionable delivery-health states. |
| 10 | What wording best distinguishes planned items from live tenders? | Proposed | “Planned opportunity” plus a persistent “Not a live tender” explanation. | Test comprehension with subscribers. |
