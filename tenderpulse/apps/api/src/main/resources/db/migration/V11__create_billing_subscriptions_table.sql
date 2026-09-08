-- TP-121 (issue #121): billing_subscriptions per
-- tenderpulse/docs/specs/subscription-lifecycle-paypal.md §7.2 -- the append-only-by-convention
-- table that will hold PayPal billing state once a later phase in this milestone (PayPal
-- plans/checkout, webhooks) starts writing to it. No JPA entity/repository is added for this
-- table in this phase -- it is schema-only groundwork (see issue #121 scope: "PayPal
-- plan/checkout changes, webhook handling logic ... none of that is this task").
--
-- Per spec §7.2: "Retain billing history. Do not overwrite an old cancelled PayPal subscription
-- ID when a user subscribes again; create a new billing-subscription row and link it to the same
-- subscriber." -- hence subscriber_id is a plain FK, not unique: one subscriber may accumulate
-- multiple billing_subscriptions rows over their lifetime.
CREATE TABLE billing_subscriptions (
    id                          uuid NOT NULL,
    subscriber_id               uuid NOT NULL,
    provider                    varchar(255) NOT NULL,
    provider_subscription_id    varchar(255) NOT NULL,
    provider_plan_id            varchar(255) NOT NULL,
    plan                        varchar(255) NOT NULL,
    status                      varchar(255) NOT NULL,
    provider_status             varchar(255) NOT NULL,
    payer_id                    varchar(255),
    payer_email                 varchar(255),
    next_billing_time           timestamp(6) with time zone,
    paid_access_until           timestamp(6) with time zone,
    last_payment_at             timestamp(6) with time zone,
    last_payment_amount         numeric(38,2),
    currency_code               varchar(3) NOT NULL,
    cancel_requested_at         timestamp(6) with time zone,
    cancelled_at                timestamp(6) with time zone,
    created_at                  timestamp(6) with time zone NOT NULL,
    updated_at                  timestamp(6) with time zone NOT NULL,
    version                     bigint NOT NULL,
    CONSTRAINT pk_billing_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_billing_subscriptions_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id),
    CONSTRAINT uq_billing_subscriptions_provider_subscription_id UNIQUE (provider_subscription_id),
    CONSTRAINT billing_subscriptions_plan_check CHECK (plan IN ('PRO', 'MAX')),
    CONSTRAINT billing_subscriptions_status_check CHECK (status IN ('NONE', 'APPROVAL_PENDING', 'ACTIVE', 'PAYMENT_FAILED', 'SUSPENDED', 'CANCELLATION_PENDING', 'CANCELLED', 'EXPIRED'))
);
