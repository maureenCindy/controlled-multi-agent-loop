-- Baseline migration (#61) for com.tenderpulse.domain.NotificationRecord — see V1 header for the
-- methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not re-derived from the
-- entity class).

CREATE TABLE notifications (
    id             uuid NOT NULL,
    channel        varchar(255),
    error_message  varchar(255),
    sent_at        timestamp(6) with time zone,
    success        boolean NOT NULL,
    subscriber_id  uuid NOT NULL,
    tender_id      uuid NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id),
    CONSTRAINT fk_notifications_tender FOREIGN KEY (tender_id) REFERENCES tenders (id),
    CONSTRAINT notifications_channel_check CHECK (channel IN ('EMAIL', 'SMS', 'IN_APP'))
);
