-- Baseline migration (#61) for com.tenderpulse.auth.MagicLinkToken (TP-038/#39) — see V1 header
-- for the methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not re-derived
-- from the entity class).
--
-- Note: this table has no FK to subscribers — subscriber_id is stored as a plain uuid column on
-- the entity (see com.tenderpulse.auth.Models.kt), matching what Hibernate actually created.

CREATE TABLE magic_link_tokens (
    id            uuid NOT NULL,
    created_at    timestamp(6) with time zone,
    expires_at    timestamp(6) with time zone NOT NULL,
    subscriber_id uuid NOT NULL,
    token_hash    varchar(255) NOT NULL,
    used_at       timestamp(6) with time zone,
    CONSTRAINT pk_magic_link_tokens PRIMARY KEY (id),
    CONSTRAINT uq_magic_link_tokens_token_hash UNIQUE (token_hash)
);
