-- TP-121 (issue #121): subscriber_preference_events per
-- tenderpulse/docs/specs/subscription-lifecycle-paypal.md §7.4 -- the append-only preference audit
-- log a later phase in this milestone (self-service preferences) will write to. No JPA
-- entity/repository is added for this table in this phase -- it is schema-only groundwork (see
-- issue #121 scope: "self-service preference endpoints ... none of that is this task").
--
-- Deliberately excludes IP address / user agent columns even though spec §7.4 lists them as
-- optional ("IP address and user agent only if justified by the privacy policy"):
-- tenderpulse/docs/specs/privacy-note.md does not currently document collecting either, and this
-- task does not add new PII collection without that policy update happening first (per issue #121
-- instructions). Add those columns in a follow-up migration alongside a privacy-note.md update if
-- a later phase needs them.
--
-- Column named occurred_at rather than the spec's literal "timestamp" -- consistent with every
-- other event/audit table in this schema (sent_at, queued_at, received_at, processed_at) and
-- avoids the SQL type-name/column-name ambiguity of a column literally called "timestamp".
CREATE TABLE subscriber_preference_events (
    id                 uuid NOT NULL,
    subscriber_id      uuid NOT NULL,
    action             varchar(255) NOT NULL,
    source             varchar(255) NOT NULL,
    occurred_at        timestamp(6) with time zone NOT NULL,
    related_token_id   uuid,
    CONSTRAINT pk_subscriber_preference_events PRIMARY KEY (id),
    CONSTRAINT fk_subscriber_preference_events_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id),
    CONSTRAINT subscriber_preference_events_action_check CHECK (action IN ('EMAIL_OPT_IN', 'EMAIL_OPT_OUT', 'WHATSAPP_OPT_OUT', 'PAUSE', 'RESUME')),
    CONSTRAINT subscriber_preference_events_source_check CHECK (source IN ('SIGNUP', 'EMAIL_LINK', 'MANAGE_PAGE', 'WHATSAPP', 'ADMIN'))
);
