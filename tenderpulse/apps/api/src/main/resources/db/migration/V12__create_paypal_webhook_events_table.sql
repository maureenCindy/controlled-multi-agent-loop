-- TP-121 (issue #121): paypal_webhook_events per
-- tenderpulse/docs/specs/subscription-lifecycle-paypal.md §7.3 -- the webhook inbox a later phase
-- in this milestone (webhooks and reconciliation) will insert into keyed by paypal_event_id, the
-- "final protection against duplicate webhook delivery" per the spec. No JPA entity/repository is
-- added for this table in this phase -- it is schema-only groundwork (see issue #121 scope:
-- "webhook handling logic ... none of that is this task").
--
-- payload is stored as `text` (the original verified PayPal payload, serialized) rather than the
-- spec's literal `jsonb` -- verified empirically that this test suite's @DataJpaTest-based tests
-- (e.g. InterestProfileRepositoryTest) run against an H2 instance in H2's *default* mode, not the
-- PostgreSQL-compatibility mode configured on this app's own datasource URL:
-- org.springframework.boot.test.autoconfigure.jdbc.TestDatabaseAutoConfiguration silently replaces
-- the configured 'dataSource' bean with its own embedded H2 instance (a Spring Boot Test default,
-- there to keep each @DataJpaTest isolated on its own fresh database) whose URL it builds itself,
-- without the MODE=PostgreSQL parameter. H2 only registers the `jsonb` type alias in
-- PostgreSQL-compatibility mode (confirmed directly against H2 2.3.232: `CREATE TABLE t (payload
-- jsonb)` fails with "Unknown data type: JSONB" in H2's default mode, succeeds with
-- MODE=PostgreSQL) -- so a real `jsonb` column here broke every @DataJpaTest in this suite the
-- moment Flyway tried to run this migration against that auto-replaced datasource. Forcing that
-- auto-replacement off (`spring.test.database.replace: none`) was considered and rejected: it
-- makes every @DataJpaTest share the single already-populated `tenderpulse` H2 database that
-- @SpringBootTest-based integration tests commit real rows into over the course of the suite,
-- which broke InterestProfileRepositoryTest's exact-count assertion (`assertEquals(1,
-- results.size)`) by letting unrelated committed subscribers/profiles leak in -- a real regression,
-- not an acceptable trade-off. `text` is portable across both H2 modes and real Postgres (Postgres
-- accepts a plain string in a `text` column the same as `jsonb`, just without jsonb's query
-- operators/indexing -- acceptable here since nothing queries this column's structure yet; no JPA
-- entity/repository consumes this table in this phase at all, see this migration's header).
CREATE TABLE paypal_webhook_events (
    id                    uuid NOT NULL,
    paypal_event_id       varchar(255) NOT NULL,
    event_type            varchar(255) NOT NULL,
    resource_id           varchar(255),
    payload               text NOT NULL,
    verification_status   varchar(255) NOT NULL,
    processing_status     varchar(255) NOT NULL,
    received_at           timestamp(6) with time zone NOT NULL,
    processed_at          timestamp(6) with time zone,
    processing_error      text,
    CONSTRAINT pk_paypal_webhook_events PRIMARY KEY (id),
    CONSTRAINT uq_paypal_webhook_events_paypal_event_id UNIQUE (paypal_event_id),
    CONSTRAINT paypal_webhook_events_verification_status_check CHECK (verification_status IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT paypal_webhook_events_processing_status_check CHECK (processing_status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED'))
);
