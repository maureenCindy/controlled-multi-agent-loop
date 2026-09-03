# Privacy / legal note (TenderPulse ZW MVP)

**Status:** Basic MVP-stage note, agreed 2026-09-03
**Applies to:** TP-020 (waitlist), TP-041, all notification/alert flows

> **This is a basic MVP-stage note, not reviewed by counsel — replace with real legal review
> before handling real user data at scale.** It documents the current consent/fair-use stance
> in plain English so contributors and agents have a shared, honest baseline. It is not a
> finalised legal document and must not be presented as one.

---

## What we collect

| Source | Fields |
|--------|--------|
| Waitlist signup (`POST /api/v1/waitlist`) | email, sector (optional), province (optional), company (optional) |
| Subscriber registration (`POST /api/v1/subscribers`) | email, phone (optional), tier |
| Interest profile (`POST /api/v1/subscribers/{id}/profiles`) | sectors, value range, issuing-authority filter, region, keywords, preferred channels |

We do not collect anything beyond what these forms/endpoints request, and we do not scrape or
buy contact lists — see `CONTRIBUTING.md` ("Agents ... do not invent contacts for outreach
lists").

## What it's used for

- Sending tender-match alerts (email now; SMS/in-app are scaffolded but not yet wired to a real
  provider).
- Free-tier daily digest, paid-tier real-time alerts — see `tenderpulse/docs/specs/aggregation-policy.md`.
- Occasional build/launch updates for waitlist signups (surfaced via the X handle on the landing
  page, not a separate mailing list).

## No sharing with third parties

Subscriber, interest-profile, and waitlist data is not sold or shared with third parties.
Outbound alerts link to the *official* PRAZ e-GP tender listing rather than mirroring or
reselling the underlying bid document — consistent with the MVP scrape-source constraint in
`tenderpulse/docs/specs/zw-tender-sources.md` (public summary fields + official link only).

## Consent guarantee: emails only go to people who signed up

This is enforced as a code guarantee, not just a policy statement:

- `NotificationService.notifyMatchingSubscribers()` (`tenderpulse/apps/api/src/main/kotlin/com/tenderpulse/notification/NotificationService.kt`)
  only ever iterates `InterestProfileRepository.findAllActiveWithSubscriber()`
  (`tenderpulse/apps/api/src/main/kotlin/com/tenderpulse/domain/Repositories.kt`), a JPA query that
  `JOIN FETCH`es the `subscriber` relation. `InterestProfile.subscriber` is a non-null
  `@ManyToOne` (`tenderpulse/apps/api/src/main/kotlin/com/tenderpulse/domain/Models.kt`), so it can only
  ever point at a row in the `subscribers` table.
- The **only** place a `Subscriber` row is created is `SubscriberController.register()`
  (`POST /api/v1/subscribers`, `tenderpulse/apps/api/src/main/kotlin/com/tenderpulse/api/ApiControllers.kt`)
  — i.e. an explicit self-registration.
- `WaitlistEntry` (pre-launch waitlist, `POST /api/v1/waitlist`) is a **separate table** with no
  notification wiring at all: nothing in `NotificationService`, `AggregationService`, or any
  scheduled job reads from `WaitlistEntryRepository`. Converting a waitlist entry into a real
  subscriber + profile is tracked separately (TP-021, not yet implemented) and will still require
  going through `SubscriberController.register()`.

So there is currently no code path that emails, SMS's, or otherwise notifies an address that
wasn't submitted through an explicit signup endpoint.

## Alerts attribute and link to the official source

`EmailNotificationSender` (same file as above) builds alert content via `buildAlertBody(tender)`,
which always includes the tender's `issuingAuthority` (attribution) and `sourceUrl` (official
PRAZ e-GP link) alongside the title and deadline. See `NotificationServiceTest.kt` /
`AlertContentTest` for coverage.

## Your choices

- Waitlist/subscriber removal: reach out via the X handle on the landing page
  (`@tenderpulse_zw`); we'll delete the record on request. There is no self-service delete
  endpoint yet — this is a manual process appropriate for the current (near-zero) signup volume.

## What this note is not

- Not a GDPR/POPIA compliance program.
- Not a cookie-consent mechanism (the landing page currently sets no cookies).
- Not legal advice, and not reviewed by a lawyer. Before onboarding real paying subscribers at
  scale, this note should be replaced with a proper privacy policy reviewed by counsel familiar
  with Zimbabwean and any other applicable data-protection law.
