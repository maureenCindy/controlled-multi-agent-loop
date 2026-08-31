# TenderPulse Domain Schema — v1 (ZW-ready)

**Status:** Finalised for MVP aggregation + matching  
**Target:** Zimbabwe public procurement via PRAZ e-GP  
**Date:** 2026-08-31

---

## Overview

This document defines the normalised data model for TenderPulse alerts. All tenders are stored in a single **`Tender`** entity that holds required and optional fields from any supported source. Zimbabwe-specific field support (e.g. currency, province) is built in from v1.

---

## Tender Entity

The core entity for publishing opportunities.

### Required Fields

| Field | Type | Example | Notes |
|-------|------|---------|-------|
| `id` | UUID | `f47ac10b-58cc-4372-a567-0e02b2c3d479` | Primary key; auto-generated on create |
| `title` | String | Supply and Delivery of Computer Consumables | Procuring entity's official tender title |
| `issuingAuthority` | String | Ministry of Finance, Economic Development and Investment Promotion | Entity issuing the tender |
| `sourceUrl` | String | https://egp.praz.org.zw/tenders/2026/TR22053 | Official link to full tender details |
| `sourceName` | String | egp.praz.org.zw | Source domain/identifier |
| `publishedAt` | Instant (UTC) | 2026-08-31T10:30:00Z | Date/time tender was published |

### Optional Fields

| Field | Type | Example | Notes |
|-------|------|---------|-------|
| `description` | String (TEXT) | Full tender description / scope | Populated from detail page (not usually on bulletin list) |
| `externalTenderId` | String | TR22053 | Source system's tender reference number (e.g. e-GP reference) |
| `sector` | Enum: `Sector` | `IT` | Inferred or mapped from source category codes; defaults to `OTHER` |
| `deadline` | Instant (UTC) | 2026-09-15T15:00:00Z | Tender closing date/time; nullable if source doesn't provide |
| `valueMin` | BigDecimal | 100000.00 | Minimum estimated contract value (currency in `currency` field) |
| `valueMax` | BigDecimal | 500000.00 | Maximum estimated contract value |
| `currency` | String | USD, ZWL | Currency code for tender value (e.g. USD, ZWL for Zimbabwe) |
| `region` | String | Harare, Bulawayo, Mashonaland East | Province or administrative region; free text (no enum yet) |
| `keywords` | Set<String> | {network, switches, ICT} | Extracted from title, category, or description |
| `createdAt` | Instant (UTC) | 2026-08-31T10:35:00Z | Timestamp when record was stored |

---

## Sector Enum

Maps procuring entity category codes to procurement domains:

```
CONSTRUCTION
IT
HEALTHCARE
EDUCATION
TRANSPORT
ENERGY
AGRICULTURE
OTHER (default)
```

---

## Mapping: PRAZ e-GP → Tender

### Bulletin Board Columns → Fields

| e-GP Column | Tender Field | Required | Notes |
|-------------|--------------|----------|-------|
| Tender Id | `externalTenderId` | ✅ (for deduping) | Reference code, e.g. TR22053 |
| Tender Title | `title` | ✅ | Official procuring entity title |
| Procuring Entity | `issuingAuthority` | ✅ | Ministry or government body |
| Scope / Category | `sector` + `keywords` | ❌ | Map PRAZ category codes (e.g. GC006) to `Sector` enum; code in keywords |
| Publish Date | `publishedAt` | ✅ | Date tender posted to board |
| Closing Date | `deadline` | ✅ | Due date/time for submissions |
| Detail / View Link | `sourceUrl` | ✅ | Full URL including tender id |

### Optional Enrichment (from detail page scrape)

| Source | Tender Field | Notes |
|--------|--------------|-------|
| Tender description / scope | `description` | Full text; may require second fetch |
| Tender value range | `valueMin`, `valueMax`, `currency` | Often not on bulletin list; may be in PDF documents |
| Supplying categories listed | `keywords` | Additional detail |

---

## Test Coverage

### Test Case 1: Sample ZW Tender with All Required Fields

**Scenario:** Map a real e-GP bulletin notice to the Tender schema.

**Sample:** 
```
Title: Supply and Delivery of Computer Consumables
Reference: TR22053
Entity: Ministry of Finance, Economic Development and Investment Promotion
Category: Computers, Printers, Photocopiers, Networking Equipment and Accessories (GC006)
Publish Date: 2026-08-31 10:00:00 UTC
Closing Date: 2026-09-15 15:00:00 UTC
Source URL: https://egp.praz.org.zw/tenders/2026/TR22053
```

**Expected Storage:**
- `title` = "Supply and Delivery of Computer Consumables"
- `issuingAuthority` = "Ministry of Finance, Economic Development and Investment Promotion"
- `externalTenderId` = "TR22053"
- `publishedAt` = 2026-08-31 10:00:00 UTC
- `deadline` = 2026-09-15 15:00:00 UTC
- `sourceUrl` = "https://egp.praz.org.zw/tenders/2026/TR22053"
- `sourceName` = "egp.praz.org.zw"
- `sector` = `IT` (inferred from category code GC006)
- `keywords` includes "GC006", "computers", "printers"

**Validation:** All required fields present; no critical orphan data.

### Test Case 2: Missing Deadline

**Scenario:** Tender posted to e-GP but closing date not yet set or not displayed.

**Expected Storage:**
- All required fields except `deadline` (nullable)
- `deadline` = null
- Matching and aggregation proceed normally

**Validation:** Entity accepts null deadline; MatchingService does not fail.

---

## Notes

- **No migration needed:** This schema is compatible with existing tests and sample data. New fields are all optional with safe defaults.
- **Currency field:** Supports multi-currency tenders; default for ZW is USD or ZWL as per source.
- **External ID field:** Prevents duplicate imports of same tender from e-GP on re-run.
- **Region field:** Currently free text; future versions may introduce a ZW Province enum.
- **Matching:** Existing matching logic (sector, value, authority, region, keywords) is unaffected by optional additions.

---

## Database Migration

**Schema changes:**
- Add column `external_tender_id VARCHAR(255) NULL` (index for deduping recommended)
- Add column `currency VARCHAR(10) NULL` (e.g., USD, ZWL, GBP)

**Data:** No backfill required (all new columns are nullable).

---

## Future Extensions (Phase 2 / not MVP)

- **Province enum:** Map `region` to ZW provinces (Harare, Bulawayo, etc.) once data is richer.
- **Attachments:** Link to full bid documents (if we later host copies).
- **Application templates:** Link to standard forms for each category.
- **Supplier registration:** Link to pre-qualification data for applicants.

---

## References

- [TP-001 Zimbabwe Tender Sources](zw-tender-sources.md)  
- [TP-003 e-GP Adapter](https://github.com/maureen/controlled-multi-agent-loop/issues/3) (future)  
- PRAZ e-GP: https://egp.praz.org.zw/  
