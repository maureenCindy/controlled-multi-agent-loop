-- Baseline migration (#61): captures the schema Hibernate's ddl-auto:update had already been
-- creating for com.tenderpulse.domain.Tender, verified empirically by booting the app (as it
-- stood immediately before this change) against a fresh, empty local Postgres instance
-- (tenderpulse/docker-compose.yml) and dumping the resulting schema with pg_dump --schema-only.
-- This migration reproduces that dump (table/column names, types, nullability, checks) as-is --
-- it is not a re-derivation from the entity class. Constraint names below are renamed from
-- Hibernate's generated hashes (e.g. "fkhyvnyabqmaq3l9ao0hry3qujk") to descriptive names for
-- readability; Hibernate's ddl-auto: validate (which this app now uses) validates table/column
-- existence, types and nullability, not constraint names, so this rename is safe.

CREATE TABLE tenders (
    id                  uuid NOT NULL,
    created_at          timestamp(6) with time zone,
    currency            varchar(255),
    deadline            timestamp(6) with time zone,
    description         text,
    external_tender_id  varchar(255),
    issuing_authority   varchar(255),
    published_at        timestamp(6) with time zone,
    region              varchar(255),
    sector              varchar(255),
    source_name         varchar(255),
    source_url          varchar(255),
    title               varchar(255) NOT NULL,
    value_max           numeric(38,2),
    value_min           numeric(38,2),
    CONSTRAINT pk_tenders PRIMARY KEY (id),
    CONSTRAINT tenders_sector_check CHECK (sector IN ('CONSTRUCTION', 'IT', 'HEALTHCARE', 'EDUCATION', 'TRANSPORT', 'ENERGY', 'AGRICULTURE', 'OTHER'))
);

-- @ElementCollection Set<String> keywords on Tender.
CREATE TABLE tender_keywords (
    tender_id uuid NOT NULL,
    keywords  varchar(255),
    CONSTRAINT fk_tender_keywords_tender FOREIGN KEY (tender_id) REFERENCES tenders (id)
);
