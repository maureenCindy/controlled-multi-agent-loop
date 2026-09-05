-- Baseline migration (#61) for com.tenderpulse.domain.Subscriber — see V1 header for the
-- methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not re-derived from the
-- entity class).

CREATE TABLE subscribers (
    id                     uuid NOT NULL,
    active                 boolean NOT NULL,
    created_at             timestamp(6) with time zone,
    email                  varchar(255) NOT NULL,
    email_opt_out          boolean NOT NULL,
    paypal_subscription_id varchar(255),
    phone                  varchar(255),
    tier                   varchar(255),
    CONSTRAINT pk_subscribers PRIMARY KEY (id),
    CONSTRAINT uq_subscribers_email UNIQUE (email),
    CONSTRAINT uq_subscribers_paypal_subscription_id UNIQUE (paypal_subscription_id),
    CONSTRAINT subscribers_tier_check CHECK (tier IN ('FREE', 'PAID'))
);
