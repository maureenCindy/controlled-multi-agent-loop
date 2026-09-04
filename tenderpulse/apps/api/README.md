# TenderPulse

Kotlin / Spring Boot scaffold for a tender aggregation & notification platform.

## How it works

1. **Aggregation** — Continuously fetches tender notices from official sources (government e-procurement portals, public notices, agency websites) via APIs, RSS feeds, and scheduled scraping where APIs aren’t available. Every notice is parsed, normalised, and stored in a central database.

2. **Interest Profiles** — Subscribers configure a profile describing what they’re looking for: industry sector (construction, IT, healthcare, etc.), tender value range, issuing authority, geographic region, and keywords. This profile is used to match incoming tenders.

3. **Smart Notifications** — When a new tender matches a subscriber’s profile, TenderPulse delivers an alert via their preferred channel — email digest, SMS, or in-app notification — with a summary of the tender, deadline, issuing authority, and a direct link to the source.

4. **Subscription Tiers**
   - **Free** — daily digest alerts
   - **Paid** — real-time notifications, advanced filters, deadline reminders, tender history & analytics

## Stack

- Kotlin 2.x + Spring Boot 3.4
- Spring Data JPA + PostgreSQL (app) / H2 in-memory (tests only — see below)
- Validation, Mail starter (email channel stub)
- JUnit 5 + MockK for tests

## Project layout

```
src/main/kotlin/com/tenderpulse/
├── domain/          # Entities + repositories (Tender, Subscriber, InterestProfile, …)
├── aggregation/     # TenderSource interface + AggregationService
├── matching/        # MatchingService (profile ↔ tender rules)
├── notification/    # Channel senders (Email / SMS / In-App) + NotificationService
├── api/             # REST controllers
└── TenderPulseApplication.kt
```

## Run locally

The app now runs against a real PostgreSQL instance (TP-048) instead of H2 in-memory, so data
survives restarts. Start Postgres via Docker Compose first, then run the app:

```bash
# 1. Start local Postgres (from the tenderpulse/ directory, one level up from apps/api)
cd tenderpulse
docker compose up -d
docker compose ps          # wait until postgres is "healthy"

# 2. Run the app (from apps/api)
cd apps/api
./gradlew bootRun          # after generating wrapper, or use an IDE
# API: http://localhost:8080
```

The app connects using these local-dev defaults (matching `docker-compose.yml`), each overridable
via env var — see `src/main/resources/application.yml`:

| Env var       | Default       |
| ------------- | ------------- |
| `DB_HOST`     | `localhost`   |
| `DB_PORT`     | `5432`        |
| `DB_NAME`     | `tenderpulse` |
| `DB_USER`     | `tenderpulse` |
| `DB_PASSWORD` | `tenderpulse` |

Schema is still managed by Hibernate's `ddl-auto: update` (no Flyway — deferred, see #49) and is
created automatically on first boot against the empty Postgres database.

To stop Postgres: `docker compose down` (from `tenderpulse/`). Add `-v` to also delete the data
volume (irreversible — wipes all local subscriber/tender data).

Generate the Gradle wrapper if needed:

```bash
gradle wrapper --gradle-version 8.11.1
```

### Tests vs. the real datasource

`./gradlew test` does **not** require Postgres or Docker to be running. Tests use H2 in-memory via
`src/test/resources/application.yml`, which shadows the main `application.yml` on the test
classpath. This was a deliberate choice (TP-048 / #49), not an oversight:

- CI (`.github/workflows/ci.yml`) has no Postgres service configured, and adding one (or
  Testcontainers) was judged a bigger lift than this migration's scope warranted.
- The suite's only tests that boot a real Spring/JPA context are `EntityPersistenceTest`
  (`@DataJpaTest`, which always uses an embedded database regardless of datasource config) and
  `PrivacyPageTest` / `WaitlistRetirementTest` (`@SpringBootTest`, neither of which touches the
  database). Every other test mocks the repository layer, so H2 vs. Postgres makes no difference
  to what's tested.
- As part of TP-048, the full suite was also run once with the datasource pointed at the
  docker-compose Postgres service (env-var override, no source changes) to confirm no divergence,
  and the app was run against Postgres directly and exercised via a few endpoints — see the PR
  evidence for #49.

If real Postgres-backed test coverage becomes valuable later (e.g. to catch a dialect-specific
regression), revisit via Testcontainers rather than pointing the default test config at the
docker-compose service, so `./gradlew test` keeps working without Docker.

## Useful endpoints (scaffold)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/tenders` | List tenders (optional `?sector=IT`) |
| GET | `/api/v1/tenders/{id}` | Get one tender |
| POST | `/api/v1/subscribers` | Register subscriber (FREE tier by default) |
| POST | `/api/v1/subscribers/pro` | PayPal-verified Pro signup — see below (TP-042) |
| POST | `/api/v1/subscribers/{id}/profiles` | Create interest profile |
| POST | `/api/v1/admin/aggregate` | Run one aggregation cycle |

### Pro (PAID tier) signup — PayPal subscription verification (TP-042)

`POST /api/v1/subscribers/pro` accepts `{ "email": "...", "paypalSubscriptionId": "..." }` — the
subscription ID PayPal's frontend SDK returns to the `onApprove` callback after checkout. The
backend never trusts that callback directly: it calls PayPal's REST API server-to-server
(`GET /v1/billing/subscriptions/{id}`, authenticated via a cached `POST /v1/oauth2/token`
client-credentials token) and only creates/upgrades the `Subscriber` to `tier = PAID` — storing
the subscription ID on the record — if PayPal confirms the subscription is `ACTIVE` **and** its
`plan_id` matches the configured `PAYPAL_PLAN_ID`. A subscription that doesn't exist, is for a
different plan, or isn't `ACTIVE` is rejected with `400`; a failed/timed-out call to PayPal itself
returns `502` and never creates or changes a subscriber (no partial state).

Required environment variables (see `.env.example`, never committed with real values):

| Env var | Purpose | Local default |
| ------- | ------- | -------------- |
| `PAYPAL_BASE_URL` | `https://api-m.sandbox.paypal.com` (sandbox) or `https://api-m.paypal.com` (live) | sandbox URL |
| `PAYPAL_CLIENT_ID` | PayPal Developer app Client ID | *(none — must be set to call PayPal)* |
| `PAYPAL_CLIENT_SECRET` | PayPal Developer app Secret | *(none — must be set to call PayPal)* |
| `PAYPAL_PLAN_ID` | The recurring Plan ID Pro subscriptions must match | *(none — must be set)* |

Out of scope for TP-042 (tracked separately): the webhook listener for later
cancellations/failed renewals, refunds, and plan changes/downgrades after initial signup.

## Tests

```bash
./gradlew test
```

Matching rules are covered by unit tests under `src/test/kotlin/.../MatchingServiceTest.kt`.

## Next steps for a real product

- Implement real `TenderSource` adapters (e-procurement APIs, RSS, scrapers)
- Scheduled aggregation (`@Scheduled`)
- Daily digest job for FREE tier
- Auth (JWT / OAuth2)
- Flyway migrations (Postgres itself landed in TP-048; schema is still Hibernate `ddl-auto: update`)
- Real email (SES / SendGrid) and SMS (Twilio) providers
- Analytics & history endpoints for PAID tier

This scaffold is intentionally minimal so it can be used as a concrete target for the multi-agent build–check loop in the parent repository.
