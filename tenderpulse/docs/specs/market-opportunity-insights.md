# TenderBell Market Opportunity Insights Specification

**Status:** Proposed for product and data review<br />
**Date:** 2026-09-08<br />
**Product:** TenderBell Max<br />
**Primary experience:** TenderBell Market Insights Portal<br />
**Audience:** Product, design, data engineering, backend, frontend, and commercial teams

---

## 1. Purpose

This specification defines the Market Opportunity Insights experience included with TenderBell
Max. It describes:

- the questions the product must answer for different subscriber types;
- the insight groups and metrics presented in the portal;
- the data required from published tenders, award notices, and annual procurement plans;
- the interpretation and display rules that make insights understandable and trustworthy;
- the proposed Opportunity Score and New Entrant Fit methodologies;
- tender lifecycle and failed-procurement analysis;
- regional opportunity mapping;
- data-quality, traceability, and coverage requirements;
- an incremental delivery plan.

The objective is not to reproduce a procurement database. TenderBell should turn published public
procurement data into clear answers to three subscriber questions:

1. **Where is the opportunity?**
2. **Is the opportunity growing and realistically accessible to me?**
3. **What should I do next?**

Every major insight should therefore combine a finding, its supporting evidence, a plain-language
interpretation, and a relevant next action.

---

## 2. Product Promise and Boundaries

Market Opportunity Insights helps subscribers understand published government demand, spending,
procurement activity, award patterns, and forward procurement plans. It is a decision-support
product, not financial, investment, legal, or bid-success advice.

The experience must not:

- imply that published records represent all government procurement activity;
- treat missing award values as zero-value contracts;
- promise that a high-scoring market is easy to enter or that a subscriber will win;
- describe historical patterns as forecasts unless a separately validated forecasting model exists;
- infer a reason for a failed procurement where the source does not publish one;
- rank a supplier negatively merely because no published award notice is available;
- hide source-data limitations behind a composite score.

All calculated insights must remain traceable to the source tenders, awards, or annual procurement
plan items from which they were derived.

---

## 3. Target Subscribers and Decisions

| Subscriber group | Primary decision | Most valuable insight groups |
|---|---|---|
| Aspiring entrepreneur | Which industry or service should I enter? | New Entrant Fit, growing categories, planned demand, accessible contract bands |
| Business starter | What opportunities can my early-stage business realistically pursue? | Smaller awards, RFQ activity, buyer breadth, response time, local opportunities |
| Growing SME | Where can I win more work or expand? | Personal matches, active buyers, adjacent categories, regional hotspots |
| Established business | Which markets, regions, and buyers should we prioritise? | Buyer intelligence, award concentration, category growth, procurement pipeline |
| Consultant | Where are relevant engagements appearing? | Consulting demand, buyer recurrence, response windows, accessible award sizes |
| Investor | Where is public spending and economic activity moving? | Sector growth, award value, procurement plans, geographic concentration |
| Business strategist | What is changing, and what could change next? | Trend shifts, seasonality, planned-versus-actual activity, competitive structure |

Subscriber type should be captured during Max onboarding and remain editable. It affects dashboard
ordering and explanations; it must not silently change the underlying metric definitions.

---

## 4. Insight Information Architecture

The Max portal should organise intelligence into the following groups:

1. Overall Market Size & Scale
2. Demand & Growth Trends
3. Procuring-Entity Intelligence
4. Sector & Category Opportunity
5. New Entrant Accessibility
6. Award & Competitive Intelligence
7. Regional Opportunity Map
8. Tender Lifecycle & Outcome Intelligence
9. Annual Procurement Plan Intelligence
10. Personalised Opportunity Intelligence
11. Market Opportunity Summary — “Where should I start my business?”

The portal should open with a concise executive overview. Detailed analysis belongs in dedicated
views so the subscriber is not confronted with every available chart at once.

---

## 5. Insight Groups and Metrics

### 5.1 Overall Market Size & Scale

This group establishes the size, coverage, and recent activity of the observable procurement
market.

#### Core metrics

| Metric | Definition | Display guidance |
|---|---|---|
| Active procurement categories | Distinct source categories represented by at least one published tender in the selected period | Do not call this “registered suppliers” or “supplier registrations” |
| Available category taxonomy | Total distinct supplier/procurement categories available from the official source | Show separately from active categories |
| Active procuring entities | Distinct entities that published at least one tender or award in the period | Include change versus the comparable period |
| Tenders published | Count of unique tender notices first published in the period | Deduplicate revisions and repeated ingestion |
| Tender awards published | Count of unique published award notices in the period | Label explicitly as published awards |
| Total disclosed award value | Sum of published award amounts after currency filtering or conversion | Never combine currencies without a stated conversion policy |
| Median disclosed award value | Median amount among awards with a disclosed, parseable value | Prefer median to average when values are skewed |
| Average disclosed award value | Mean amount among awards with a disclosed value | Display alongside the median where useful |
| Planned procurement value | Sum of estimated budgets in applicable annual procurement plans | Label as planned, not committed or awarded expenditure |
| Award-value coverage | Awards with usable values divided by all published awards | Always accompany value-based market metrics |
| Award-publication coverage | Tenders linked to an award notice divided by eligible closed tenders | Explain that an unlinked tender is not necessarily unawarded |

#### Example insight

> **184 tenders were published in the last 90 days**
> Activity increased 12% compared with the previous 90 days. Thirty-one procuring entities were
> active, and 78% of published awards included a usable contract value.

### 5.2 Demand & Growth Trends

This group explains which goods, services, and works are in demand and how demand is changing.

#### Metrics

- tender count by category and procurement class;
- tender growth rate by category;
- disclosed tender or award value by category;
- number and breadth of active buyers per category;
- recurring versus once-off demand;
- rolling three-, six-, and twelve-month trends;
- category momentum: acceleration or deceleration across consecutive periods;
- emerging category detection;
- demand seasonality by month or quarter;
- planned future demand from annual procurement plans;
- demand concentration by buyer and region;
- ratio of published tender volume to published award volume.

Demand should be available in two modes:

- **Activity:** number of published opportunities; and
- **Value:** disclosed award value or planned budget, with coverage shown.

Value must not be used as a silent substitute for activity when coverage is low.

#### Emerging-category rule

An emerging category should satisfy all of the following configurable conditions:

- a minimum baseline number of tenders;
- material growth across at least two comparable periods;
- activity from more than one procuring entity;
- acceptable category-classification confidence.

This prevents one unusual tender from producing a misleading “fastest-growing market” claim.

### 5.3 Procuring-Entity Intelligence

This group helps subscribers understand who buys, what they buy, and how reliably they follow
published procurement schedules.

#### Metrics

- most active procuring entities by tender count;
- highest-spending entities by disclosed award value;
- highest planned budgets by entity and year;
- fastest-growing buyers;
- dominant categories for each entity;
- average and median contract value;
- preferred procurement methods;
- average publication-to-closing period;
- average closing-to-award period;
- plan-to-publication conversion rate;
- planned-versus-actual publication timing;
- failed or cancelled procurement rate;
- repeat procurement patterns;
- supplier concentration among published awards;
- source-of-funds distribution where published.

#### Example insight

> **City of Bulawayo is increasing ICT procurement**
> It published 14 ICT opportunities in the last 90 days, 40% more than in the previous period.
> Its annual procurement plan lists four additional ICT items scheduled before year-end.

### 5.4 Sector & Category Opportunity

This group provides a comparable profile for each procurement category.

Each category profile should include:

- current tender volume;
- historical and planned growth;
- total and median disclosed award value;
- planned budget;
- number of active procuring entities;
- regional reach;
- procurement-method mix;
- average response window;
- recurring-demand frequency;
- seasonality;
- buyer concentration;
- awardee concentration;
- failed-procurement rate;
- award-value and classification coverage;
- Opportunity Score with component explanation.

Categories must be mapped to a stable internal taxonomy while retaining the exact official category
text. Changes in source category names should not break historical trends.

### 5.5 New Entrant Accessibility

This group helps early-stage businesses identify markets that may have lower observable barriers to
entry. It must use the label **New Entrant Fit**, not “easy to win.”

#### Indicators

- median disclosed contract value within a subscriber-selected budget range;
- percentage of awards below configurable value thresholds;
- use of Request for Quotation and other relatively lightweight procurement methods;
- average response window;
- number and diversity of published awardees;
- share of first-time or infrequent awardees;
- buyer and awardee concentration;
- frequency of smaller, repeatable requirements;
- number of active buyers;
- geographic reach;
- failed-procurement rate;
- expected delivery lead time from procurement plans;
- prequalification or EOI requirements;
- SPOC oversight indicator, displayed separately and not scored as a barrier until its relationship
  to new-entrant accessibility is validated;
- data confidence and coverage.

#### Example insight

> **Professional Consulting shows strong New Entrant Fit**
> Sixty-one percent of disclosed awards were below US$10,000, awards were distributed among 18
> suppliers, and RFQs accounted for 46% of published activity.

This is an evidence-based market characteristic, not an assurance of eligibility or success.

### 5.6 Award & Competitive Intelligence

This group explains who is winning, what buyers are spending, and what published award decisions
reveal about the market.

#### Core metrics

- award count and disclosed value by supplier;
- award count and disclosed value by category, region, and procuring entity;
- repeat awardees;
- new awardees entering a category;
- awardee concentration and top-supplier share;
- median winning contract amount;
- award value distribution by band;
- procurement method used for awarded contracts;
- tender-closing-to-award duration;
- award publication delay;
- single-awardee versus multi-award outcomes, where the source supports them;
- awarded budget variance where an annual plan item can be linked;
- award-reason distribution and trends.

#### Award Reason Analysis

TenderBell should retain the original published `reasonForAward` text and classify it into a
normalised, versioned set of analysis labels.

Initial labels should include:

| Normalised reason | Example source language |
|---|---|
| Lowest evaluated price | “LOWEST BIDDER”, “lowest responsive bid” |
| Best evaluated bid | “best evaluated bidder” |
| Technical compliance | “technically compliant”, “met specifications” |
| Only responsive bid | “sole responsive bidder”, “only compliant bidder” |
| Direct or single-source selection | Direct award or single-source wording |
| Highest score or quality | Quality/technical scoring language |
| Administrative or policy basis | Preference, framework, or policy-based language |
| Other published reason | A reason exists but does not match a current class |
| Not published | No usable reason was supplied |

The classifier must store:

- original text;
- normalised reason;
- classifier version;
- confidence score;
- whether the classification was rules-based, model-assisted, or human-reviewed;
- review status.

#### Award-reason insights

- most common award reasons overall and by category;
- percentage of awards attributed to lowest evaluated price;
- categories where technical compliance or quality appears more influential;
- award-reason distribution by procurement method and entity;
- changes in award-reason distribution over time;
- award-reason publication coverage;
- relationship between published reason and contract-value band;
- relationship between reason and supplier concentration.

The UI must not reduce procurement strategy to “bid the lowest.” “Lowest bidder” may be source
shorthand for a responsive or evaluated bid, and the portal must display the official notice for
context.

### 5.7 Regional Opportunity Map

This group visualises where published and planned opportunities are concentrated.

#### Map modes

- published tender count;
- published award count;
- disclosed award value;
- planned procurement count;
- planned budget;
- matched opportunities for the current subscriber;
- growth rate;
- New Entrant Fit;
- failed-procurement rate.

#### Required interactions

- switch between provinces, cities/localities, and national opportunities where data allows;
- select a date or procurement-plan period;
- filter by category, procurement class, method, buyer, value band, and status;
- hover or select a region to see value, volume, growth, coverage, and leading categories;
- move from a region to its underlying tender, award, or plan records;
- compare two regions;
- clearly separate nationwide procurements from a procuring entity’s office address.

#### Location rules

Tender location, delivery location, procuring-entity location, and buyer headquarters are not
interchangeable. The source field used for a map must be visible. Records without a defensible
location remain in **Location not specified** and must not be silently assigned to Harare or another
default region.

Annual procurement plan rows do not provide a location directly. A plan item may contribute to a
regional view only when it is linked to a published tender with an explicit applicable location, or
when a separately maintained procuring-entity location is used and clearly labelled as the buyer’s
location rather than the place of delivery. Otherwise, the plan item remains **Location not
specified**.

#### Example insight

> **Bulawayo is an emerging ICT hotspot**
> Published ICT opportunities grew 35% in the selected period. Harare still leads in total volume,
> while annual procurement plans show additional ICT activity scheduled in Bulawayo next quarter.

### 5.8 Tender Lifecycle & Outcome Intelligence

The source may expose tenders with statuses including `Failed` and `Closed`. TenderBell must model
source status separately from its own derived lifecycle state.

#### Normalised lifecycle states

| State | Meaning |
|---|---|
| Scheduled | Present in an annual procurement plan but not linked to a published tender |
| Published/Open | Published and still accepting responses |
| Closing soon | Derived state: open and within the configured reminder window |
| Closed | Bidding has ended. It is not yet verified whether the source also uses this status for a completed procurement |
| Awarded | Linked to one or more published award notices |
| Failed | Official source explicitly marks the procurement as failed; the source does not provide a failure reason |
| Cancelled/Withdrawn | Official source explicitly identifies cancellation or withdrawal |
| Unknown | Current state cannot be determined safely |

`Closing soon` is not a source outcome. It is a time-based TenderBell state. `Closed` must not be
treated as equivalent to `Awarded` or completed until the source semantics are fully verified.
`Failed` must not be treated as a scraping or system error.

#### Lifecycle metrics

- open, closing-soon, closed, awarded, failed, and cancelled counts;
- status distribution by entity, category, procurement method, region, and period;
- published-to-closed duration;
- closed-to-award duration;
- tender-to-award cycle duration;
- failed-procurement rate;
- cancellation/withdrawal rate;
- closed-without-published-award rate;
- re-publication rate and repeated requirement detection;
- procurement method associated with higher failure rates;
- planned activity cycle versus observed activity cycle;
- status-transition completeness and freshness.

#### Failed-procurement insights

The current source does not publish failure reasons. TenderBell may therefore analyse observable
failure patterns, but it must not infer or present a cause. Potential insight areas include:

- categories with unusually high failure rates;
- entities with recurring failed procurements;
- procurement methods associated with failures;
- estimated budgets, response windows, or requirement types associated with failure;
- failed tenders later reissued;
- elapsed time before re-publication.

TenderBell must state **“failure reason not provided by the official source”** wherever a user might
otherwise expect an explanation. If the source begins publishing reasons later, the data model may
be extended to retain and normalise them without rewriting historical records.

### 5.9 Annual Procurement Plan Intelligence

Annual procurement plans provide a forward-looking view of intended procurement activity by entity
and year. They are plans, not live tenders or guarantees of expenditure.

#### Source fields

The ingestion model must preserve and normalise the supplied columns:

| Source column | Normalised representation |
|---|---|
| ItemID | `sourceItemId` |
| Ref No | `referenceNumber` |
| Class of Procurement | `procurementClass` |
| Object Code | `objectCode` |
| Description of Requirements | `description` |
| PMO / End-User | `pmoOrEndUser` |
| Procurement Method | `procurementMethod` |
| Prequalification/EOI (Y/N) | `prequalificationOrEoiRequired` |
| EOI Publication Date | `eoiPublicationDate` |
| EOI Closing Date | `eoiClosingDate` |
| Tender Publication Date | `plannedTenderPublicationDate` |
| Bid Closing Date | `plannedBidClosingDate` |
| Publication of Award Notice | `plannedAwardNoticeDate` |
| Contract Signing | `plannedContractSigningDate` |
| Activity Cycle (work days), Tender to Award Notice | `plannedTenderToAwardWorkDays` |
| Lead Time, Contract Signing to Expected Delivery | `plannedDeliveryLeadTime` plus a source-preserved unit |
| SPOC (Y/N) | `specialProcurementOversightCommitteeRequired` |
| Source of Funds | `sourceOfFunds` |
| Estimated Budget (US$) | `estimatedBudgetAmount`, `estimatedBudgetCurrency`, and the original column label |
| Unit of Measurement (UoM) | `unitOfMeasurement` |
| Quantity | `quantity` |
| Comments | `comments` |
| APP/iAPP Supplement | `planTypeOrSupplementReference` |

The original row and document metadata must also be retained for auditability.

#### SPOC interpretation

`SPOC` means **Special Procurement Oversight Committee**. The body provides oversight intended to
support transparency and value for money in high-value or sensitive government contracts. A `Y`
value should therefore be presented as an oversight indicator, not as a warning or negative signal.

Potential SPOC analysis includes:

- number and planned value of SPOC-flagged items;
- SPOC activity by procuring entity, class, category, and procurement method;
- typical planned and observed tender-to-award cycle for SPOC items;
- plan-to-publication and plan-to-award progress for SPOC items.

#### Currency handling

Zimbabwe’s supported procurement currencies include:

- Zimbabwe Gold (`ZWG`); and
- United States dollar (`USD`, which may be displayed as `US$` or `$`).

TenderBell must store amount and currency separately and retain the original monetary text and
column label. When a plan column explicitly states `Estimated Budget (US$)`, the parsed currency may
be recorded as `USD`; other source variants must be inspected for an explicit currency indicator.
An amount without a defensible currency remains **currency unknown** and must not be included in a
currency-specific total.

USD and ZWG values must never be added together directly. Any future converted view must disclose
the exchange-rate source, applicable date, calculation time, and original values. Default portal
views should prefer currency-separated totals.

#### Required plan metadata

- procuring entity;
- procurement/fiscal year;
- original plan or supplementary plan indicator;
- publication date and retrieval timestamp;
- source URL or document identifier;
- source document version or checksum;
- ingestion version;
- row-level parse warnings;
- active/superseded status.

#### Procurement-plan metrics

- planned item count by year, entity, category, method, class, and region;
- total and median estimated budget;
- estimated budget by sector and procuring entity;
- upcoming planned publications by month or quarter;
- EOI and prequalification pipeline;
- planned procurement-method mix;
- planned source-of-funds mix;
- average planned activity cycle;
- expected delivery lead time;
- high-value planned items;
- plan amendments and supplementary activity;
- plan-to-tender publication conversion rate;
- plan-to-award conversion rate;
- planned-versus-actual publication variance;
- planned-versus-actual closing-date variance;
- planned-versus-actual award-date variance;
- estimated-budget-versus-award-value variance;
- delayed, completed, failed, and apparently unexecuted plan items.

#### Plan-to-actual linking

An annual plan item should be linked to a published tender or award using a tiered strategy:

1. exact procuring entity and reference-number match;
2. exact or mapped object code and strong reference similarity;
3. entity, normalised requirement description, category, and plausible date-window match;
4. reviewed manual match for strategically important ambiguous records.

Every link must retain:

- match method;
- match score;
- matched fields;
- linkage version;
- review status;
- ability to unlink an incorrect association.

Low-confidence links must not contribute to definitive plan-conversion metrics without being
labelled as estimated.

#### Plan-driven insights

Examples include:

> **US$2.4 million in ICT procurement is planned for the next two quarters**
> Twelve entities list 38 planned ICT requirements. Seven require an EOI or prequalification step.

> **Prepare for City of Bulawayo’s systems procurement**
> Its annual plan schedules a vendor-management system tender for September, with a 30-work-day
> tender-to-award cycle.

> **This buyer publishes later than planned**
> Over the available matched history, tender notices appeared a median of 24 days after their annual
> plan dates.

### 5.10 Personalised Opportunity Intelligence

This group should be the visual centre of the Max dashboard. General market data establishes
credibility; personally relevant signals create recurring value.

#### Inputs

- subscriber type;
- selected and inferred business capabilities;
- tracked categories;
- preferred regions;
- realistic contract-value range;
- preferred procurement methods;
- optional delivery capacity and lead-time constraints;
- alert and insight preferences.

#### Personalised insights

- new tenders matching tracked categories;
- closing-soon matches;
- relevant annual-plan items scheduled for publication;
- tracked categories gaining or losing momentum;
- buyers becoming more active in the subscriber’s categories;
- regional growth relevant to the subscriber;
- typical contract values and response windows;
- adjacent categories supported by similar capability profiles;
- awards and award reasons in tracked markets;
- planned requirements aligned with subscriber capacity;
- weekly personalised market-opportunity summary.

#### Example

> **Your strongest opportunity signal this month**
> Software-development demand increased 28%. Six procuring entities published 12 matching tenders,
> and four further requirements appear in current annual procurement plans.

### 5.11 Market Opportunity Summary — “Where Should I Start My Business?”

This guided view is designed for aspiring entrepreneurs and business starters. It translates the
full dataset into a shortlist of markets for further investigation.

The user should first provide:

- available starting capital or preferred contract-value band;
- current skills, certifications, equipment, or supplier relationships;
- region and delivery reach;
- interest in goods, services, works, or consulting;
- willingness to complete prequalification or EOI processes;
- preferred delivery lead time;
- optional industries they wish to exclude.

The result should present three to five potential starting points. Each recommendation card should
show:

1. category or market;
2. Opportunity Score and New Entrant Fit;
3. current tender demand;
4. planned future demand;
5. median disclosed contract value;
6. number of active buyers;
7. procurement-method mix;
8. regional availability;
9. typical response and delivery windows;
10. main opportunity drivers;
11. material risks or barriers;
12. data confidence;
13. actions such as tracking the category or viewing upcoming plan items.

#### Example summary

> **A possible starting point: ICT support services**
> This market fits your US$2,000–US$10,000 target range and existing technical skills. Demand grew
> 19%, eight buyers were active, and nine related requirements are listed in annual procurement
> plans. Competition is moderately concentrated and some opportunities require prior experience.

The interface must use language such as **possible starting point**, **strong fit based on selected
criteria**, and **worth investigating**. It must not say “start this business” as an instruction or
guarantee commercial success.

---

## 6. Composite Scores

### 6.1 Opportunity Score

The initial category-level Opportunity Score may use the following transparent components:

| Component | Proposed weight | Example inputs |
|---|---:|---|
| Current demand | 20% | Tender count, active buyers |
| Demand growth | 15% | Comparable-period and rolling trend |
| Planned pipeline | 20% | Planned item count and budget, adjusted for past plan execution |
| Buyer breadth | 10% | Distinct buyers and concentration |
| Award activity | 10% | Published awards and disclosed value |
| Recurrence and stability | 10% | Repeat demand and seasonality |
| Accessibility | 15% | Procurement methods, value bands, awardee diversity, prequalification |

Scores should be calculated within comparable categories and time windows. Data availability must
affect confidence, not be interpreted as poor market performance.

### 6.2 New Entrant Fit

The initial New Entrant Fit score may use:

| Component | Proposed weight |
|---|---:|
| Alignment with selected capital/value range | 20% |
| Accessible procurement-method mix | 15% |
| Awardee diversity | 15% |
| Buyer breadth | 10% |
| Low supplier concentration | 10% |
| Response-window practicality | 10% |
| Limited prequalification burden | 10% |
| Repeatable demand | 10% |

Weights are proposals, not final facts. They must be validated against real source coverage and
subscriber research before being marketed as a stable methodology.

### 6.3 Score display rules

- show component contributions;
- show the time period and filters;
- show data confidence;
- provide underlying records;
- version score methodologies;
- recalculate historical scores using the selected version or clearly disclose version changes;
- avoid false precision: display bands such as Strong, Moderate, or Limited alongside a rounded score;
- never suppress a material negative factor merely to produce an attractive recommendation.

---

## 7. Common Filters and Comparisons

All applicable portal views should share a consistent filter model:

- date range or procurement-plan year;
- tender publication, closing, award, or planned date basis;
- category;
- procurement class;
- procurement method;
- procuring entity;
- region;
- source of funds;
- contract or estimated-budget band;
- lifecycle status;
- prequalification/EOI requirement;
- matched-to-my-business only;
- data-confidence threshold.

Every comparison must identify its baseline, for example:

- previous equal-length period;
- same period in the previous year;
- selected entity versus overall market;
- selected category versus its broader procurement class.

---

## 8. Presentation and Readability Standard

### 8.1 Insight-card anatomy

Every important insight card should provide:

1. **Finding:** a plain-language headline;
2. **Evidence:** the value, trend, and comparison;
3. **Meaning:** why the finding matters;
4. **Action:** a relevant next step;
5. **Confidence:** coverage or data-quality status;
6. **Source:** access to the underlying official records.

### 8.2 Visual hierarchy

Prefer:

- four to six executive metric cards at the top of a view;
- ranked horizontal bars for category, buyer, and regional comparisons;
- simple lines or grouped bars for trends;
- a Zimbabwe regional map only when location provenance is defensible;
- concise opportunity-signal cards;
- small multiples for comparing similar categories;
- tables for exact records and export;
- progressive disclosure for methodology and data quality.

Avoid:

- dashboards filled with equally prominent widgets;
- unexplained gauges;
- excessive pie or donut charts;
- red/green colour as the only indicator;
- unlabeled composite scores;
- mixing tender counts, planned budgets, and award values on the same scale;
- suggesting precision beyond source-data quality.

### 8.3 Recommended dashboard order

1. Personalised opportunity summary
2. Immediate matching tenders and deadlines
3. Overall Market Size & Scale
4. Strongest demand and planned-pipeline movements
5. Regional opportunity signal
6. Buyer and award intelligence
7. Data-coverage summary

Subscribers may customise dashboard modules later, but the initial default should remain opinionated
and easy to scan.

---

## 9. Data Model and Provenance Requirements

### 9.1 Published tender

At minimum, retain:

- source tender ID and reference number;
- title and requirement description;
- procuring entity;
- official categories and internal category mappings;
- procurement class and method;
- publication and closing dates;
- delivery or activity location where explicitly supplied;
- currency and estimated value where published;
- source status and normalised lifecycle status;
- official source URL;
- first-seen, last-seen, and last-updated timestamps;
- raw source snapshot or content checksum;
- parse and classification confidence.

### 9.2 Award notice

At minimum, retain:

- award notice ID;
- tender ID and tender reference number;
- award type;
- awardee name and stable normalised supplier identity where possible;
- contract amount and currency;
- award date and publication date;
- original and normalised award reason;
- procuring entity;
- procurement method and class;
- required supplier categories;
- source URL and provenance metadata;
- tender-link confidence.

### 9.3 Data lineage

Every derived metric should be reproducible from versioned source records and transformations. Store:

- source retrieval time;
- parser version;
- taxonomy version;
- currency-conversion source and rate date, if conversion is introduced;
- metric-definition version;
- score-model version;
- correction and reprocessing history.

---

## 10. Data Quality and Trust

### 10.1 Coverage indicators

The portal should calculate coverage for:

- category classification;
- procuring-entity normalisation;
- geographic location;
- award value;
- award reason;
- tender-to-award linkage;
- procurement-plan-to-tender linkage;
- lifecycle status freshness.

Coverage should be visible beside any metric materially affected by missing fields.

### 10.2 Confidence language

| Confidence | Suggested meaning |
|---|---|
| High | Strong source coverage and direct or reviewed linkage |
| Moderate | Usable coverage with some missing fields or probabilistic linkage |
| Limited | Sparse coverage; directional interpretation only |

### 10.3 Freshness

Every view should show:

- last successful source refresh;
- selected analysis period;
- known source delays;
- whether an annual plan has been superseded by a supplement;
- whether an insight is based on tender publications, award publications, or planned activity.

---

## 11. Delivery Plan

### Phase 1 — Metric and source audit

- inventory every available field from tenders, awards, statuses, and annual procurement plans;
- profile missingness, consistency, cardinality, and historical coverage;
- identify the technical source fields or events that produce `Failed` and `Closed`;
- verify whether `Closed` is ever used for a completed procurement in addition to ended bidding;
- confirm how original APPs and iAPP supplements are versioned, replaced, or combined;
- create the versioned metric dictionary;
- define the category, buyer, supplier, procurement-method, and geographic normalisation rules;
- establish data-quality thresholds for public-facing insights.

### Phase 2 — Trustworthy descriptive MVP

Launch:

- Overall Market Size & Scale;
- demand and growth trends;
- procuring-entity intelligence;
- tender lifecycle distribution;
- basic award analysis;
- personalised tender and award matches;
- visible coverage and provenance.

Avoid composite scores until the underlying data profile is understood.

### Phase 3 — Annual Procurement Plan pipeline

- ingest original and supplementary plans;
- preserve document and row versions;
- expose upcoming planned procurement;
- link plan items to published tenders and awards;
- calculate schedule variance and plan-conversion metrics;
- add planned-pipeline signals to category and buyer views.

### Phase 4 — Decision-support insights

- introduce Opportunity Score;
- introduce New Entrant Fit;
- add award-reason analysis;
- add the regional opportunity map;
- launch “Where should I start my business?”;
- provide transparent explanations, confidence, and underlying evidence.

### Phase 5 — Personalisation and validation

- tailor insight ordering by subscriber type;
- add subscriber budget, capability, and geographic-fit inputs;
- measure which insights lead to category tracking and official-tender views;
- validate score usefulness through subscriber interviews;
- tune thresholds and weights using observed data and feedback;
- consider forecasting only after sufficient historical depth and back-testing.

---

## 12. Initial Metric Audit Checklist

Before finalising the portal, explicitly review whether the following potentially valuable metrics
are supported and trustworthy:

### Market activity

- unique tenders versus tender lots;
- amendments and re-publications;
- value bands rather than only totals;
- open opportunity value where published;
- procurement class mix: goods, services, works, and consulting;
- procurement-method mix;
- source-of-funds mix;
- market concentration and diversification.

### Timing

- publication-to-closing response time;
- closing-to-award time;
- award-to-publication delay;
- planned-versus-actual dates;
- delivery lead time;
- seasonality;
- deadline clustering that could affect supplier capacity.

### Outcomes

- failed and cancelled rate;
- closed-without-published-award rate;
- reissued failed procurements;
- award reason distribution;
- budget-to-award variance;
- single versus multiple awardees;
- new versus repeat awardees.

### Opportunity accessibility

- prequalification/EOI requirement rate;
- opportunities within subscriber budget bands;
- smaller-contract frequency;
- awardee diversity;
- buyer breadth;
- regional delivery requirements;
- practical response and delivery windows.

### Procurement-plan execution

- planned items published;
- planned items awarded;
- apparent schedule slippage;
- supplementary plan growth or reduction;
- planned budget changes;
- entities with high or low observable plan execution;
- planned demand aligned with subscriber categories.

### Subscriber value

- relevant new matches;
- relevant planned pipeline;
- category momentum changes;
- emerging relevant buyers;
- adjacent category suggestions;
- saved insight or exported report activity;
- official-source detail views initiated from insights.

This checklist is intentionally broader than the first release. The metric dictionary and source
audit determine which measures are included, deferred, or rejected.

---

## 13. Product Success Measures

Measure whether the intelligence changes useful subscriber behaviour rather than optimising only for
dashboard visits.

Candidate product measures include:

- Max activation: subscriber configures capabilities, regions, and value band;
- percentage of Max subscribers who view a personalised insight weekly;
- insight-to-category-tracking conversion;
- insight-to-official-tender-detail conversion;
- procurement-plan-item-to-alert-follow conversion;
- saved or exported insight reports;
- subscriber-rated usefulness and clarity;
- retention by subscriber persona;
- percentage of visible insights with High or Moderate confidence;
- correction rate for category, buyer, supplier, location, and plan linkages.

Do not use tender wins as the only product success measure because TenderBell does not control bid
quality, eligibility, pricing, evaluation, or the completeness of published outcomes.

---

## 14. Acceptance Criteria

The Market Opportunity Insights design is ready for implementation planning when:

1. Every launch metric has an agreed definition, formula, and source field mapping.
2. Tender source status and TenderBell lifecycle status are stored separately.
3. `Closed`, `Failed`, `Awarded`, and `Closing soon` have unambiguous meanings.
4. Annual procurement plan rows are versioned and distinguish original plans from supplements.
5. Planned activity is visually and semantically separate from published tenders and awarded spend.
6. Award reasons retain their original wording and show normalisation confidence.
7. Plan-to-tender and tender-to-award links retain evidence and confidence.
8. Currency, missing-value, duplicate, and amendment policies are documented.
9. Opportunity and New Entrant Fit scores expose their contributing factors.
10. The regional map identifies which location field it represents.
11. “Where should I start my business?” presents evidence, risks, and data confidence—not guarantees.
12. Every aggregate insight can open or reference its underlying official records.
13. The Max dashboard has a concise default hierarchy tested with target subscribers.
14. Accessibility, responsive behaviour, and light/dark presentation are included in design review.

---

## 15. Open Questions and Decision Log

The following table is the working decision log. **Confirmed** means the product specification can
rely on the answer. **Partially answered** means part of the behaviour is known but implementation
still requires source verification.

| # | Question | Status | Current answer | Product implication / next verification |
|---:|---|---|---|---|
| 1 | Does the source expose a supplier registry, or only supplier/procurement category definitions? | Open | Not yet established. | Do not display supplier-registration counts. Use **Available category taxonomy** and **Active procurement categories** until an actual supplier registry is confirmed. |
| 2 | What exact source event or field produces `Failed` and `Closed`, and are failure reasons available? | Partially answered | The source exposes the statuses, but the exact technical event or field still needs to be documented. Failure reasons are not provided. | Capture the original source status. Analyse failure patterns only; always state that the official source does not provide a reason. Inspect the source response or page transition that sets each status. |
| 3 | Does `Closed` mean only that bidding ended, or can it also represent a completed procurement? | Partially answered | `Closed` means bidding has ended. It is not yet verified whether the publisher also uses it for a completed procurement. | Treat `Closed` as ended bidding with outcome not established. Do not count it as completed or awarded until source behaviour is verified. |
| 4 | What does `SPOC` mean in the annual procurement plan source? | Confirmed | **Special Procurement Oversight Committee**, an oversight body concerned with transparency and value for money in high-value or sensitive government contracts. | Store the field as `specialProcurementOversightCommitteeRequired`. Present it as an oversight indicator and support SPOC pipeline/cycle analysis. |
| 5 | How are APP and iAPP supplements versioned, replaced, or combined by the publisher? | Open | Not yet established. | Preserve each retrieved document and row version independently. Do not merge or supersede a plan automatically until publisher behaviour is verified. |
| 6 | Are annual plan locations available directly, or must location be derived from another field? | Confirmed | Locations are not available directly in annual procurement plan rows. | Keep location unspecified unless a plan item is linked to a tender with an applicable explicit location. A buyer-location proxy must be labelled as such and must not be presented as delivery location. |
| 7 | Are estimated budgets always in US dollars despite the column label, and how are local-currency records represented? | Partially answered | Zimbabwe currently supports Zimbabwe Gold (`ZWG`) and United States dollars (`USD`/`US$`/`$`). Exact per-record representation still needs to be profiled. | Store amount, currency, raw text, and source label separately. Do not combine USD and ZWG totals. Verify how each source variant identifies currency. |
| 8 | Can one tender or plan item contain multiple lots, budgets, awards, or awardees? | Open | Not yet established. | Use a model capable of representing one-to-many lots and awards. Profile source examples before defining tender and award counting rules. |
| 9 | How much reliable history is available for year-over-year comparison and seasonality? | Open | Not yet established. | Do not launch year-over-year, seasonality, or forecasting claims until historical completeness and consistency are measured. |
| 10 | Which value bands best represent the practical budgets of TenderBell’s target business starters? | Open | Requires customer research and source-value profiling. | Start with configurable bands internally; validate understandable and realistic bands with prospective and existing subscribers before publishing them. |
| 11 | Should subscriber capabilities use a controlled taxonomy, free text, or a combination? | Open | Product decision pending. | Evaluate a hybrid model: controlled categories for reliable matching plus optional free text for nuance, mapped back to the taxonomy where possible. |
| 12 | Which three insights do existing subscribers find most actionable in a weekly summary? | Open | Requires interviews or structured feedback from current subscribers. | Test candidate summaries covering immediate matches, closing deadlines, and one personalised market or pipeline signal. Record which insight leads to action. |
