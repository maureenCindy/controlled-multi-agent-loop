# Contributing to TenderPulse / controlled-multi-agent-loop

Thanks for helping build TenderPulse. This doc is the shared contract for **humans and agents**.

---

## Principles

1. **Issues are the source of truth** for scope (acceptance criteria + test cases).  
2. **Code reaches `main` only via pull request** (see exception below).  
3. **CI must be green** before merge.  
4. **Evidence before review** — show how acceptance criteria are met.  
5. **One focused PR per issue** where practical (`Closes #N`).

---

## Issue → branch → PR → merge

```
Issue (open, clear AC)
    → branch from main
    → implement + tests
    → evidence in PR body
    → open PR (link issue)
    → CI + review / comments
    → merge → issue closes
```

### 0. Local dev setup (Postgres)

`tenderpulse/apps/api` runs against a real PostgreSQL instance (not H2 — TP-048). Before
`bootRun`-ing the app locally, start Postgres via Docker Compose:

```bash
cd tenderpulse
docker compose up -d
docker compose ps   # wait for "healthy"
```

See [tenderpulse/apps/api/README.md](tenderpulse/apps/api/README.md) for connection defaults and
env var overrides. **Tests do not need Postgres/Docker** — `./gradlew test` runs against H2
in-memory (see the same README, "Tests vs. the real datasource," for why that's a deliberate
choice).

### 1. Pick an issue

- Prefer **P0 / M1** items first.  
- Read the issue body **and** [tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md](tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md) if linked.  
- Do not expand into Phase 2 (application helper, full apply workspace) unless the issue says so.

### 2. Branch naming

| Type | Pattern | Example |
|------|---------|---------|
| Feature / task | `tp-<id>-short-slug` | `tp-003-praz-egp-adapter` |
| Fix | `fix/tp-<id>-…` or `fix/short-slug` | `fix/tp-011-keyword-match` |
| Docs | `docs/short-slug` | `docs/aggregation-policy` |

Branch from latest `main`.

### 3. Implement

- Stay inside **Scope in / out** on the issue.  
- Meet every **acceptance criterion**.  
- Add or update **tests** listed on the issue (or equivalent coverage).  
- Follow [tenderpulse/docs/specs/aggregation-policy.md](tenderpulse/docs/specs/aggregation-policy.md) and [tenderpulse/docs/specs/zw-tender-sources.md](tenderpulse/docs/specs/zw-tender-sources.md) for aggregation work.  
- **MVP scrape source:** PRAZ e-GP only (`egp.praz.org.zw`).  

### 4. Before you open the PR

- [ ] Branch is up to date with `main`  
- [ ] Tests pass locally (`gradle test` or `./gradlew test` in `tenderpulse/apps/api/`)  
- [ ] Lint/format clean when configured  
- [ ] No secrets committed  
- [ ] PR body drafted with **evidence** (see template)  
- [ ] Issue linked with `Closes #N` or `Fixes #N`  

### 5. Open the PR

- Use the [pull request template](.github/pull_request_template.md).  
- Request review (human and/or checker agent).  
- Address review comments on the **PR**, not in a side channel only.  

### 6. Merge and close

- Merge when **CI is green** and review is satisfied.  
- Prefer **squash merge** for tidy history on small tasks.  
- Issue closes automatically if the PR body contains `Closes #N`.
- If several PRs have been landing in quick succession while this one sat open, confirm it's genuinely rebased onto current `main` (not just conflict-free) before treating it as merge-ready — see the Verification Standards note in `CLAUDE.md`.

---

## Exception: docs / research only

Pure documentation or research (e.g. TP-001 source inventory) **may** land as a direct commit to `main` after explicit review in discussion, **or** via a small docs PR.

**All application code, config that changes runtime behaviour, and tests → PR required.**

---

## Agents (`/loop` and similar)

When an agent works an issue:

1. Treat the **issue acceptance criteria + test cases** as the checker bar.  
2. Do **not** push straight to `main` for code.  
3. Open or update a **PR** with evidence.  
4. Respect stop rules in `CLAUDE.md` (max cycles, same failure twice, human escalation).  
5. Do not invent contacts for outreach lists or invent legal conclusions.  
6. **Never start a Builder/Checker loop against an issue with placeholder acceptance criteria** (e.g. "to be filled once scoped/when scoped"). Finalize concrete AC and test cases first — a decision may be needed from the human before that's possible, in which case ask rather than guess.
7. **Follow the Verification Standards in `CLAUDE.md`** — in particular: prove security/behavior claims empirically rather than by plausible reasoning, and prove new regression tests actually regress (temporarily revert the fix, confirm the test fails, restore it) before trusting them.
8. **Cross-link every follow-up issue with the PR/review that raised it, both ways**: the new issue's body links back to the originating PR, and a comment on that PR links to the new issue. This is how the audit trail stays navigable — a follow-up with no link back to its origin is as good as lost.
9. **Before creating a new tracked task**, do a quick check that its intended ID isn't already used elsewhere (search open/closed issues and PR titles) — task-ID collisions have happened in this repo and are confusing to untangle after the fact.
10. **Before finalizing AC/test cases for a new task**, check the [Cross-cutting invariants checklist](#cross-cutting-invariants-checklist) below for any category the task touches, and fold applicable items into that task's AC/test cases explicitly. This is cheaper than the alternative: both existing entries in that checklist were originally caught only at Reviewer stage, after Checker had already passed the task clean against AC that never asked for them.

Suggested prompt:

```text
/loop Complete TP-XXX as specified in issue #N and tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md.
Respect Scope in/out. Meet every acceptance criterion. Add/run the listed tests.
Open a PR (do not push to main). Include evidence in the PR body. Closes #N.
Do not expand into Phase 2 features.
```

---

## Cross-cutting invariants checklist

Some lessons from `tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md`'s "Template improvement log" don't just apply to the one task that surfaced them — they apply to every future task in the same category. When 2+ log entries share a category, promote the pattern here (human sign-off required, same as any other process change in this file). Task scoping checks this list per rule 10 above; Reviewer treats it as a standing backstop per its own instructions, independent of whether scoping caught it.

**Notification / email dispatch** — any task that adds a new code path sending an email or queuing a digest entry:
- [ ] Include a test case for a subscriber who is eligible by history (previously matched/notified) but is *currently* opted out (`emailOptOut = true`) or deactivated (`active = false`) — confirm they receive nothing. This is the standing consent guarantee established in TP-041; a new dispatch path must re-check current status, not just historical eligibility. *(Source: TP-056/#56 — `ReminderService` shipped without this check, caught only at Reviewer stage.)*

**Schema / column changes** — any task that adds or modifies a column with a `NOT NULL` constraint:
- [ ] State in Assumptions/AC how migration safety against an already-populated table is verified — a DB-level default (e.g. `@ColumnDefault`) or an explicit backfill step. This app uses `ddl-auto: update`, not Flyway (#61), and the test suite runs against H2 only (#54), so this class of bug is invisible to both the schema-evolution mechanism and CI — it only surfaces by booting against a real, populated Postgres instance. *(Source: TP-058/#58 — adding `InterestProfile.name` as required broke boot against a populated table, caught only at Reviewer stage; the second such gap this project has hit.)*

---

## CI

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

| Trigger | Paths |
|---------|--------|
| Push to `main` | `tenderpulse/apps/api/**`, workflow file |
| Pull request → `main` | same |

| Job | What it runs |
|-----|----------------|
| **Build & test** | JDK 21 (Temurin), Gradle 8.11.1, `build -x test`, then `test` |

- Test result XML is uploaded as an artifact.  
- On failure, HTML test reports are uploaded.  
- **Do not merge** a PR that changes `tenderpulse/apps/api/` if CI is red.

Optional later: ktlint, branch protection requiring the `CI` status check.

---

## Code style (Kotlin / Spring)

- Prefer clear names over cleverness.  
- Keep adapters isolated (e.g. e-GP parsing in one class).  
- No live network in unit tests — use fixtures.  
- Config over hard-coding (cron, user-agent, delays).  

---

## Security & compliance

- No credentials or API keys in the repo.  
- Scrape only **public** e-GP list data; polite rate limits; summary + official link only.  
- See aggregation and source docs before changing fetch behaviour.  

---

## Questions

Open an issue or comment on the relevant task. Product decisions (sources, schedule, tiers) belong in docs + issues, not only in chat.
