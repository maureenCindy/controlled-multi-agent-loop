# Aggregation & notification policy (TenderPulse MVP)

**Status:** Agreed 2026-08-31  
**Applies to:** TP-003, TP-004, TP-012, TP-013  

---

## Principle

| Concern | Rule |
|---------|------|
| **Fetch (aggregation)** | Shared for all users — same tenders in the database |
| **Notify** | Differs by tier (Free vs Paid) |

Do **not** run a separate scrape schedule per tier.

---

## Aggregation (shared)

| Setting | MVP value |
|---------|-----------|
| **Frequency** | **3× per day** |
| **Suggested cron (CAT)** | `0 0 7,13,19 * * *` (07:00, 13:00, 19:00) |
| **Source** | PRAZ e-GP bulletin board (primary) — see `tenderpulse/docs/specs/zw-tender-sources.md` |
| **Behaviour** | Fetch → normalise → dedupe by `sourceUrl` → store → run matching |
| **Politeness** | Sequential page fetches; min delay between pages (e.g. 2s); identifiable User-Agent; backoff on errors |
| **Scope** | Public open-tender **summaries only** + official link; no full bid document hosting |

Config example:

```yaml
tenderpulse:
  aggregation:
    cron: "0 0 7,13,19 * * *"
    rate-limit:
      min-delay-ms: 2000
    user-agent: "TenderPulseBot/0.1 (+https://yoursite; alerts)"
```

Frequency must be **config-driven** (not hard-coded) so it can change without a code rewrite.

---

## Notification by tier

```
After each aggregation run:
  match new tenders against active profiles
       │
       ├─ PAID  → send channel notification immediately (email first)
       │
       └─ FREE  → enqueue match for daily digest only
```

| Tier | When the user is notified | Channel (MVP) |
|------|---------------------------|---------------|
| **FREE** | **Once per day** — digest of matches since last digest | Email |
| **PAID** | **After each aggregation** that produces a new match (up to 3×/day) | Email (SMS/in-app later) |

### Free digest

| Setting | MVP value |
|---------|-----------|
| **When** | After the evening aggregation (e.g. 19:30 CAT) |
| **Content** | Summary lines: title, entity, deadline, link to source |
| **Empty day** | Do **not** send an email |

### Paid

| Setting | MVP value |
|---------|-----------|
| **When** | As soon as matching finds a new tender for that profile after a run |
| **Idempotency** | One notification record per (subscriber/profile, tender); no duplicate spam on re-runs |

---

## Product copy (honest)

- Free: “We check Zimbabwe tenders three times a day; you get one daily summary of matches.”  
- Paid: “Same checks — you get an alert when we find a match, up to three times a day.”

Avoid promising “instant” or “real-time” until frequency or push channels support it.

---

## Compliance (short)

- Respect robots.txt if published; polite rate limits always.  
- Public list data + link back to e-GP; no republishing full bid packs.  
- See `tenderpulse/docs/specs/zw-tender-sources.md` for source and legal notes.  
- Revisit schedule and PRAZ contact before high volume or heavy commercial scale.

---

## Task mapping

| Task | Must implement |
|------|----------------|
| **TP-003** | Adapter; fixtures; no live dependency in CI |
| **TP-004** | Full cycle job; cron 3×/day (configurable); counts in response |
| **TP-012** | FREE → digest queue; PAID → immediate send path |
| **TP-013** | Daily digest job for FREE only |

---

## Change log

| Date | Change |
|------|--------|
| 2026-08-31 | Initial: 3×/day aggregate; Free daily digest; Paid on-match |
