-- Baseline migration (#61) for com.tenderpulse.domain.InterestProfile — see V1 header for the
-- methodology (empirical pg_dump of Hibernate's ddl-auto: update output, not re-derived from the
-- entity class).
--
-- name's DEFAULT 'Unnamed Profile' below matters beyond documentation: it reproduces the
-- @ColumnDefault Hibernate emitted (TP-058/#58) so this baseline stays additive-safe against an
-- already-populated interest_profiles table, matching the reasoning in Models.kt's InterestProfile.name.

CREATE TABLE interest_profiles (
    id                          uuid NOT NULL,
    active                      boolean NOT NULL,
    issuing_authority_contains  varchar(255),
    name                        varchar(255) DEFAULT 'Unnamed Profile' NOT NULL,
    region                      varchar(255),
    value_max                   numeric(38,2),
    value_min                   numeric(38,2),
    subscriber_id               uuid NOT NULL,
    CONSTRAINT pk_interest_profiles PRIMARY KEY (id),
    CONSTRAINT fk_interest_profiles_subscriber FOREIGN KEY (subscriber_id) REFERENCES subscribers (id)
);

-- @ElementCollection Set<Sector> sectors on InterestProfile.
CREATE TABLE profile_sectors (
    interest_profile_id uuid NOT NULL,
    sectors              varchar(255),
    CONSTRAINT fk_profile_sectors_interest_profile FOREIGN KEY (interest_profile_id) REFERENCES interest_profiles (id),
    CONSTRAINT profile_sectors_sectors_check CHECK (sectors IN ('CONSTRUCTION', 'IT', 'HEALTHCARE', 'EDUCATION', 'TRANSPORT', 'ENERGY', 'AGRICULTURE', 'OTHER'))
);

-- @ElementCollection Set<String> keywords on InterestProfile.
CREATE TABLE profile_keywords (
    interest_profile_id uuid NOT NULL,
    keywords             varchar(255),
    CONSTRAINT fk_profile_keywords_interest_profile FOREIGN KEY (interest_profile_id) REFERENCES interest_profiles (id)
);

-- @ElementCollection Set<NotificationChannel> preferredChannels on InterestProfile.
CREATE TABLE profile_channels (
    interest_profile_id uuid NOT NULL,
    preferred_channels   varchar(255),
    CONSTRAINT fk_profile_channels_interest_profile FOREIGN KEY (interest_profile_id) REFERENCES interest_profiles (id),
    CONSTRAINT profile_channels_preferred_channels_check CHECK (preferred_channels IN ('EMAIL', 'SMS', 'IN_APP'))
);
