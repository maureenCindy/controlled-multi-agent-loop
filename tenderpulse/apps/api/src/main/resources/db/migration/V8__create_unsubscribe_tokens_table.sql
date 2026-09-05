-- Baseline migration (#61) for com.tenderpulse.auth.UnsubscribeToken (TP-057/#57) — see V1
-- header for the methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not
-- re-derived from the entity class).
--
-- Note: like magic_link_tokens, subscriber_id here is a plain uuid column, not an FK, matching
-- what Hibernate actually created (see com.tenderpulse.auth.Models.kt).

CREATE TABLE unsubscribe_tokens (
    id            uuid NOT NULL,
    created_at    timestamp(6) with time zone,
    subscriber_id uuid NOT NULL,
    token_hash    varchar(255) NOT NULL,
    used_at       timestamp(6) with time zone,
    CONSTRAINT pk_unsubscribe_tokens PRIMARY KEY (id),
    CONSTRAINT uq_unsubscribe_tokens_token_hash UNIQUE (token_hash)
);
