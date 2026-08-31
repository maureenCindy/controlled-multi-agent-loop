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
- Spring Data JPA + H2 (dev) / PostgreSQL (prod-ready driver included)
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

```bash
cd tenderpulse
./gradlew bootRun          # after generating wrapper, or use an IDE
# API: http://localhost:8080
# H2 console: http://localhost:8080/h2-console
```

Generate the Gradle wrapper if needed:

```bash
gradle wrapper --gradle-version 8.11.1
```

## Useful endpoints (scaffold)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/tenders` | List tenders (optional `?sector=IT`) |
| GET | `/api/v1/tenders/{id}` | Get one tender |
| POST | `/api/v1/subscribers` | Register subscriber |
| POST | `/api/v1/subscribers/{id}/profiles` | Create interest profile |
| POST | `/api/v1/admin/aggregate` | Run one aggregation cycle |

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
- PostgreSQL + Flyway migrations
- Real email (SES / SendGrid) and SMS (Twilio) providers
- Analytics & history endpoints for PAID tier

This scaffold is intentionally minimal so it can be used as a concrete target for the multi-agent build–check loop in the parent repository.
