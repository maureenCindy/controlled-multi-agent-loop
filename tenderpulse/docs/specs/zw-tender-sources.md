# Zimbabwe tender sources inventory (TP-001)

**Status:** Complete for MVP decision  
**Primary source for first adapter:** PRAZ e-GP bulletin board  
**Date:** 2026-08-31  

---

## Summary

| # | Source | Method | Priority | Notes |
|---|--------|--------|----------|--------|
| 1 | **PRAZ e-GP** — https://egp.praz.org.zw/ | HTML scrape / possible XHR | **PRIMARY** | Official live bulletin board; richest structured list |
| 2 | **PRAZ website** — https://www.praz.org.zw/ | HTML / PDF | Secondary | Rules, templates, some Gazette PDFs; not the live tender feed |
| 3 | **Government Gazette** (via PRAZ / Gazettes) | PDF | Secondary | Legal publication channel; slower / partial digital archive |
| 4 | **Entity sites** (e.g. RBZ) | HTML | Optional | Overlap with e-GP; useful for high-value orgs only |
| 5 | **Commercial aggregators** (zimbabwetenders.com, etc.) | HTML / paid API | Avoid for MVP | Not official; ToS / cost / double-count risk |

**Recommendation:** Implement **one adapter** against the **e-GP open tenders bulletin board**. Link every alert to the official e-GP (or Gazette) URL. Do not republish full bid documents.

---

## 1. PRAZ electronic Government Procurement (e-GP) — PRIMARY

| Field | Detail |
|-------|--------|
| **URL** | https://egp.praz.org.zw/ |
| **Operator** | Procurement Regulatory Authority of Zimbabwe (PRAZ) |
| **Live since** | ~January 2024 (phased rollout; e-bidding and bulletin board in active use in 2025–2026) |
| **Access method** | **Scrape** of public bulletin board HTML (paginated tables). No public documented open API found for third-party consumers. |
| **Update frequency** | Continuous; many new notices per day across ministries, parastatals, councils |
| **Public without login?** | Bulletin board / open tender listings appear publicly viewable. Bidding and documents typically require supplier registration on the same portal. |

### Sample fields visible on the bulletin board

| e-GP column | Maps to TenderPulse |
|-------------|---------------------|
| Tender Id | external id / part of `sourceUrl` |
| Tender Reference Number | reference code (store in title or keywords) |
| Tender Title | `title` |
| Required Supplier Category Code / Name | `sector` / `keywords` (map codes → our Sector enum) |
| Procuring Entity | `issuingAuthority` |
| Scope (e.g. Open) | optional metadata |
| Publish Date | `publishedAt` |
| Closing Date | `deadline` |
| Detail / view link | `sourceUrl` |

### Sample notice (illustrative, from public board)

- **Title:** Supply and Delivery of Computer Consumables  
- **Reference:** TR22053  
- **Entity:** Ministry of Finance Economic Development and Investment Promotion  
- **Category:** Computers, Printers, Photocopiers, Networking Equipment and Accessories (GC006)  
- **Publish / close:** listed on board with explicit times  
- **Source:** e-GP board row + detail URL on egp.praz.org.zw  

### Legal / operational notes

- Official channel for invitations to bid; Gazette still used in parallel for some notices.  
- **Fair use for MVP:** store summary fields + link to official page; do **not** host full bidding documents.  
- Respect `robots.txt`, rate limits, and polite polling (e.g. every 15–60 minutes, not aggressive parallel scraping).  
- Support contact published for portal issues: egpsupport@praz.org.zw / feedback@praz.org.zw (not for scraping permission).  
- HTML structure and query params (`page`, `direction=BulletinBoardLive.id`) may change → isolate parsing in one adapter class.

### Adapter implications (for TP-003)

1. Fetch bulletin page(s) for open tenders.  
2. Parse table rows → normalised `Tender`.  
3. Deduplicate on stable `sourceUrl` (prefer detail URL including tender id).  
4. Fixture-based tests with saved HTML snapshots (do not depend on live network in CI).

---

## 2. PRAZ corporate site — secondary

| Field | Detail |
|-------|--------|
| **URL** | https://www.praz.org.zw/ |
| **Method** | HTML / PDF downloads |
| **Content** | Regulations, standard bidding documents, circulars, debarment lists, **selected Government Gazette** PDFs |
| **Use for MVP** | Reference only; not primary live feed |

---

## 3. Government Gazette — secondary

| Field | Detail |
|-------|--------|
| **Role** | Statutory publication of many procurement notices |
| **Digital access** | Partial; selected editions via PRAZ / other archives; not a clean machine feed |
| **Method** | PDF scrape / manual — **high effort, low yield for v1** |
| **Use for MVP** | Optional later enrichment; e-GP already carries most competitive opportunities |

---

## 4. Selected procuring-entity sites — optional

Example: **Reserve Bank of Zimbabwe** procurement page — https://www.rbz.co.zw/index.php/procurement  

- Lists some RBZ tenders with closing dates.  
- Overlaps e-GP for many public entities.  
- **MVP:** skip unless a pilot customer specifically needs RBZ-only depth.

---

## 5. Commercial aggregators — not for MVP core

Sites such as zimbabwetenders.com, TendersGo, Tender Impulse aggregate ZW (and global) tenders, sometimes with paid APIs.

| Pros | Cons |
|------|------|
| Structured data, alerts | Not official; licensing cost |
| Less scraping work | Dependency risk; may lag or mis-attribute |
| | Our product value is **official + matching**, not reselling a third-party feed |

**Decision:** Do not depend on these for the first adapter. Revisit only if e-GP scraping is blocked long-term.

---

## Field mapping checklist (feeds TP-002)

Minimum for a usable alert:

| Required | Optional / later |
|----------|------------------|
| title | description (from detail page) |
| issuingAuthority | valueMin / valueMax (often missing on board) |
| sourceUrl | region / province (infer from entity later) |
| sourceName = `egp.praz.org.zw` | currency (USD often referenced in APPs) |
| publishedAt | |
| deadline | |
| keywords / category codes | |

**Note:** Tender **value** is often not on the open list (pricing more common at award). Matching should not require value.

---

## Acceptance criteria (TP-001) — checklist

- [x] ≥3 viable sources documented with URL and method  
- [x] Each marked API / RSS / scrape  
- [x] **Primary** source chosen: **PRAZ e-GP bulletin board**  
- [x] Notes on rate limits / robots / terms (polite scrape; summary + link only)  

**Test cases**

| # | Scenario | Result |
|---|----------|--------|
| 1 | Open https://egp.praz.org.zw/ bulletin | Loads; tables of open tenders with title, entity, dates |
| 2 | Map one sample row to our fields | title, authority, deadline, link present |

---

## Next tasks unlocked

- **TP-002** — Confirm/extend `Tender` entity for category codes + external tender id  
- **TP-003** — `PrazEgpTenderSource` implementing `TenderSource` with HTML fixtures  

---

## References

- e-GP portal: https://egp.praz.org.zw/  
- PRAZ: https://www.praz.org.zw/  
- MAPS assessment (Zimbabwe): public procurement via Gazette, PRAZ site, and e-GP bulletin board  
- Bid instructions consistently point suppliers to https://egp.praz.org.zw for documents and submission  
