# TenderBell marketing site

The public Astro website implements the approved TenderBell marketing mock as a responsive,
single-page homepage. It includes the alert-plan hero, Market Opportunity Snapshot, How it works,
Max Market Insights preview, two customer stories, Free/Pro/Max pricing, contact/FAQ content, and
the full TenderBell footer. The static Max portal concept is available at
`/max-portal-preview.html`.

The hero signup controls and contact form are deliberately **demo-only** for TP-140. They make no
API requests and tell visitors that nothing was sent. Their production workflows will be decided
and implemented separately.

## Local development

```bash
cd tenderpulse/apps/website
npm install
npm run dev             # http://localhost:4321
```

```bash
npm run build            # static build to dist/
npm run preview          # serve the built dist/ locally
npm run test              # vitest unit tests (see "What's tested" below)
```

The marketing site does not require the API or payment credentials. Its alert setup and contact
forms are intentionally non-submitting demonstrations until their production workflows are
specified.

## Routes

- `/` — the single-page TenderBell marketing experience.
- `/privacy` — the standalone privacy note linked from the footer.
- `/max-portal-preview.html` — the static Max admin portal concept preview.

## What's tested

- `npm run build` — the site builds successfully (CI-enforced).
- `npm run test` (vitest) runs homepage contract tests verifying the approved section structure,
  all three plans, demo-only form
  wording, lack of API calls from the homepage, and required public brand/preview assets.

## Deliberately out of scope for TP-140

Connecting the homepage forms, subscriber portal authentication, changing backend payment logic,
blog/CMS, and hosting/deployment configuration.
