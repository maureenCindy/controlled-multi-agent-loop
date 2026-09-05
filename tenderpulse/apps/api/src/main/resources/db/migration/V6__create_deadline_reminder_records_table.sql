-- Baseline migration (#61) for com.tenderpulse.domain.DeadlineReminderRecord (TP-056/#56) — see
-- V1 header for the methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not
-- re-derived from the entity class).

CREATE TABLE deadline_reminder_records (
    id            uuid NOT NULL,
    sent_at       timestamp(6) with time zone,
    subscriber_id uuid NOT NULL,
    tender_id     uuid NOT NULL,
    CONSTRAINT pk_deadline_reminder_records PRIMARY KEY (id),
    CONSTRAINT uq_deadline_reminder_records_subscriber_tender UNIQUE (subscriber_id, tender_id),
    CONSTRAINT fk_deadline_reminder_records_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id),
    CONSTRAINT fk_deadline_reminder_records_tender FOREIGN KEY (tender_id) REFERENCES tenders (id)
);
