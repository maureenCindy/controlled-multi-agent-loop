-- Baseline migration (#61) for com.tenderpulse.domain.DigestQueueEntry — see V1 header for the
-- methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not re-derived from the
-- entity class).

CREATE TABLE digest_queue_entries (
    id            uuid NOT NULL,
    digested_at   timestamp(6) with time zone,
    queued_at     timestamp(6) with time zone,
    profile_id    uuid NOT NULL,
    subscriber_id uuid NOT NULL,
    tender_id     uuid NOT NULL,
    CONSTRAINT pk_digest_queue_entries PRIMARY KEY (id),
    CONSTRAINT fk_digest_queue_entries_profile FOREIGN KEY (profile_id) REFERENCES interest_profiles (id),
    CONSTRAINT fk_digest_queue_entries_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id),
    CONSTRAINT fk_digest_queue_entries_tender FOREIGN KEY (tender_id) REFERENCES tenders (id)
);
