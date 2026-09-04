# TenderPulse MVP — Checklist Board (Zimbabwe first)

**Status:** Active  
**Focus:** Waitlist + core alert loop  
**Geo:** Zimbabwe  
**Template:** Goal · Scope · Assumptions · Acceptance criteria · Test cases (improve as tasks complete)

---

## How to use

- Work top to bottom within each column where dependencies allow.
- Check boxes only when **all acceptance criteria** pass.
- After each task: note what to improve in the task template (comments at bottom).
- Agent-friendly: each task can be handed to `/loop` with the acceptance criteria + test cases as the checker bar.

---

## Board

### 🔴 P0 — Do first (Week 1–2)

| ID | Task | Owner | Status | Depends on |
|----|------|-------|--------|------------|
| TP-001 | Define Zimbabwe tender source inventory | | ⬜ | — |
| TP-002 | Normalised tender schema (finalise for ZW) | | ⬜ | TP-001 |
| TP-003 | Implement first Zimbabwe tender source adapter | | ⬜ | TP-001, TP-002 |
| TP-004 | Aggregation cycle job (manual + scheduled stub) | | ⬜ | TP-003 |
| TP-020 | Waitlist capture API | | ⬜ | — |
| TP-030 | Waitlist landing page (ZW) | | ⬜ | TP-020 |
| TP-032 | Outbound list: ZW businesses to approach | | ⬜ | — |
| TP-011 | Matching rules hardening (ZW) | | ⬜ | TP-002 |
| TP-010 | Interest profile API (create/update/list) | | ⬜ | — |
| TP-012 | Free vs Paid notification behaviour | | ⬜ | TP-011 |
| TP-041 | Privacy / legal note for ZW | | ⬜ | TP-030 |

### 🟡 P1 — Next (Week 2–4)

| ID | Task | Owner | Status | Depends on |
|----|------|-------|--------|------------|
| TP-013 | Daily digest job (Free tier) | | ⬜ | TP-012 |
| TP-031 | X content kit (first 2 weeks) | | ⬜ | TP-030 |
| TP-033 | Outreach sequence (email/DM) | | ⬜ | TP-032, TP-030 |
| TP-021 | Convert waitlist → subscriber + default profile | | ⬜ | TP-020, TP-010 |
| TP-040 | Basic observability & admin | | ⬜ | TP-004 |
| TP-042 | MVP demo script | | ⬜ | TP-003, TP-011, TP-012 |

### ⚪ Later (Phase 2 — not MVP)

- Tender registration / application checklist  
- Q&A → generate application templates  
- Full self-serve apply workspace  

---

## Task cards (full spec)

---

### TP-001 — Define Zimbabwe tender source inventory

**Epic:** Aggregation | **Priority:** P0 | **Estimate:** S  

**Goal:** Know exactly which official/public sources we will use for Zimbabwe tenders.

**Scope**  
- **In:** List of portals/feeds, access method (API / RSS / HTML), update frequency, legal notes  
- **Out:** Implementing fetchers  

**Assumptions**  
- Public notices can power alerts; we link to the official source rather than republishing restricted full documents.  

**Acceptance criteria**  
- [ ] Documented list of ≥3 viable sources with URL, method, and sample notice fields  
- [ ] Each source marked: API / RSS / scrape  
- [ ] One **primary** source chosen for first integration  
- [ ] Notes on rate limits / robots / terms if known  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Open each listed URL | Page/feed loads; recent tenders visible |
| 2 | Map one sample notice to our fields | Title, authority, deadline, link present |

**Dependencies:** None  
**Deliverable:** `tenderpulse/docs/specs/zw-tender-sources.md`

---

### TP-002 — Normalised tender schema (finalise for ZW)

**Epic:** Domain | **Priority:** P0 | **Estimate:** S  

**Goal:** One shared tender shape for aggregation and matching (ZW-ready).

**Scope**  
- **In:** Fields for ZW (e.g. currency, province), align entity + docs  
- **Out:** Heavy migration tooling  

**Assumptions**  
- Existing `Tender` entity is the base; extend only where samples require it.  

**Acceptance criteria**  
- [ ] Schema documented (fields, types, required)  
- [ ] Sample ZW tender maps with no critical orphan data  
- [ ] Code/entity updated if gaps found  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Map sample from primary source | Required fields filled |
| 2 | Missing deadline | Stored; matching still runs |

**Dependencies:** TP-001  
**Deliverable:** Schema section in `tenderpulse/docs/specs/zw-tender-sources.md` or `tenderpulse/docs/specs/domain-schema.md` + code if needed  

---

### TP-003 — Implement first Zimbabwe tender source adapter

**Epic:** Aggregation | **Priority:** P0 | **Estimate:** M  

**Goal:** Pull notices from the primary ZW source into `Tender`.

**Scope**  
- **In:** One `TenderSource` implementation  
- **Out:** Multi-source polish  

**Assumptions**  
- HTML/RSS may change; keep parsing isolated.  

**Acceptance criteria**  
- [ ] `fetchNewNotices()` returns ≥1 real or fixture-backed tenders  
- [ ] Duplicates by `sourceUrl` skipped  
- [ ] Failures logged; cycle does not crash  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Fetch with fixtures | N tenders; required fields set |
| 2 | Same URL twice | Second run stores 0 new |
| 3 | Source timeout / error | Logged; empty list; no uncaught throw |

**Dependencies:** TP-001, TP-002  

---

### TP-004 — Aggregation cycle job (manual + scheduled stub)

**Epic:** Aggregation | **Priority:** P0 | **Estimate:** S  

**Goal:** One action runs fetch → store → match → notify.

**Acceptance criteria**  
- [ ] `POST /api/v1/admin/aggregate` runs full cycle  
- [ ] Response includes fetched, stored, notificationsSent  
- [ ] Optional `@Scheduled` stub exists; disabled by default via config  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Empty source | fetched=0, stored=0 |
| 2 | New tenders + matching profile | stored>0; notificationsSent ≥ 0 |

**Dependencies:** TP-003  

---

### TP-020 — Waitlist capture API

**Epic:** Marketing / API | **Priority:** P0 | **Estimate:** S  

**Goal:** Store waitlist signups for ZW launch.

**Acceptance criteria**  
- [ ] `POST /api/v1/waitlist` validates email and stores row  
- [ ] Fields: email (required), sector(s), province, company (optional)  
- [ ] Duplicate email is idempotent (update or 200, single logical record)  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Valid signup | 2xx + stored |
| 2 | Invalid email | 400 |
| 3 | Duplicate email | No crash; one logical record |

**Dependencies:** None  

---

### TP-030 — Waitlist landing page (ZW)

**Epic:** Marketing | **Priority:** P0 | **Estimate:** M  

**Goal:** Convert ZW businesses into waitlist emails.

**Scope**  
- **In:** Copy, form → waitlist API, mobile-friendly  
- **Out:** Full product app / apply helper  

**Acceptance criteria**  
- [ ] Problem → solution → CTA clear; Zimbabwe explicit  
- [ ] Form: email, sector, province (as agreed)  
- [ ] Success state + link to X for build updates  
- [ ] Short privacy/consent line (ties to TP-041)  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Valid submit | Success UI + API 2xx |
| 2 | Missing email | Client-side validation |

**Dependencies:** TP-020  

---

### TP-032 — Outbound list: ZW businesses to approach

**Epic:** Marketing | **Priority:** P0 | **Estimate:** M  

**Goal:** Named targets who already chase tenders.

**Acceptance criteria**  
- [ ] ≥30 targets with contact path (email / LinkedIn / phone where public)  
- [ ] Segmented by sector  
- [ ] Short outreach script (problem + waitlist + ask for call)  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Spot-check 5 contacts | Reachable or clearly marked “needs research” |

**Dependencies:** None  
**Deliverable:** `tenderpulse/docs/specs/zw-outreach-list.md` (or private sheet linked from docs)

---

### TP-011 — Matching rules hardening (ZW)

**Epic:** Matching | **Priority:** P0 | **Estimate:** S  

**Goal:** Reliable match behaviour for ZW profiles.

**Acceptance criteria**  
- [ ] Sector, value overlap, authority, region/province, keywords covered by tests  
- [ ] Empty filters = match all (documented)  
- [ ] Case-insensitive keyword/authority  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Wrong sector | No match |
| 2 | Keyword in title only | Match |
| 3 | Province mismatch | No match |

**Dependencies:** TP-002  

---

### TP-010 — Interest profile API (create/update/list)

**Epic:** Profiles | **Priority:** P0 | **Estimate:** M  

**Goal:** Subscribers can express what tenders they want.

**Acceptance criteria**  
- [ ] Create / list / update profile for a subscriber  
- [ ] Validation: email rules where applicable; valueMin ≤ valueMax  
- [ ] Region/province approach documented (enum or free text)  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | Valid profile | 201 + persisted |
| 2 | valueMin > valueMax | 400 |
| 3 | Inactive profile | Excluded from matching |

**Dependencies:** — (scaffold exists)

---

### TP-012 — Free vs Paid notification behaviour

**Epic:** Notifications | **Priority:** P0 | **Estimate:** M  

**Goal:** FREE digests; PAID immediate email (SMS/in-app remain stubs).

**Acceptance criteria**  
- [ ] FREE matches queued for digest  
- [ ] PAID triggers email sender immediately  
- [ ] Notification records for both paths  

**Test cases**  
| # | Scenario | Expected |
|---|----------|----------|
| 1 | FREE match | Digest queue entry; no immediate email required |
| 2 | PAID match | Email sender invoked; success recorded |

**Dependencies:** TP-011  

---

### TP-041 — Privacy / legal note for ZW

**Epic:** Governance | **Priority:** P0 | **Estimate:** S  

**Goal:** Basic consent and fair use on waitlist + alerts.

**Acceptance criteria**  
- [ ] Privacy/consent blurb on landing  
- [ ] Emails only to people who signed up  
- [ ] Alerts attribute and link to official source  

**Dependencies:** TP-030  

---

## Parallel tracks

```
Track A (Product):     TP-001 → TP-002 → TP-003 → TP-004
                       TP-002 → TP-011 → TP-012
                       TP-010 (parallel)

Track B (Growth):      TP-020 → TP-030 → TP-041
                       TP-032 (parallel with Track B)
```

---

## Definition of “MVP ready for first conversations”

- [ ] At least one ZW source adapter works in a full aggregate cycle  
- [ ] Matching + FREE/PAID notification behaviour verified by tests  
- [ ] Waitlist live and collecting sector + province  
- [ ] ≥30 businesses on outreach list; first messages sendable  
- [ ] Privacy line on landing; source links on alerts  

---

## Template improvement log

| Date | Task | What we learned | Template change |
|------|------|-----------------|-----------------|
| 2026-09-04 | TP-056 (#56) | AC/test cases didn't include "a previously-notified subscriber who has since opted out/deactivated" — the TP-041 consent guarantee wasn't treated as a standing invariant every new notification-dispatch feature must test against. Caught only at Reviewer stage, not Checker (Checker verifies against the issue's own AC, which didn't ask for this). | Any task that adds a new notification/email-dispatch code path must include an explicit test case: "a subscriber eligible by history but currently opted-out/deactivated receives nothing." |
| 2026-09-04 | TP-058 (#58) | AC didn't flag that adding a `NOT NULL` column under this project's `ddl-auto: update` strategy needs a migration-safety check against a populated table — H2-only tests can't catch this class of bug. Caught only at Reviewer stage (2nd time this exact class of bug needed a manual Postgres boot to catch — see #54). | Any task adding/modifying a column with a `NOT NULL` constraint must state in Assumptions/AC how migration safety against an already-populated table is verified (DB-level default, or explicit backfill). |

*(Fill after each completed task. When 2+ entries share a category, promote the pattern to [CONTRIBUTING.md's Cross-cutting invariants checklist](../../../CONTRIBUTING.md#cross-cutting-invariants-checklist) — that's what future task scoping and Reviewer actually check against, not this raw log.)*

---

## Quick agent prompt (optional)

```
/loop Complete TP-00X as specified in tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md.
Respect Scope in/out, meet every Acceptance criterion, and add or run the listed Test cases.
Do not expand into Phase 2 features.
```
