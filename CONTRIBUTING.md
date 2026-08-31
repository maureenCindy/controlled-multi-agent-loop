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

### 1. Pick an issue

- Prefer **P0 / M1** items first.  
- Read the issue body **and** [docs/MVP_CHECKLIST_BOARD.md](docs/MVP_CHECKLIST_BOARD.md) if linked.  
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
- Follow [docs/aggregation-policy.md](docs/aggregation-policy.md) and [docs/zw-tender-sources.md](docs/zw-tender-sources.md) for aggregation work.  
- **MVP scrape source:** PRAZ e-GP only (`egp.praz.org.zw`).  

### 4. Before you open the PR

- [ ] Branch is up to date with `main`  
- [ ] Tests pass locally (`gradle test` or `./gradlew test` in `tenderpulse/`)  
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

Suggested prompt:

```text
/loop Complete TP-XXX as specified in issue #N and docs/MVP_CHECKLIST_BOARD.md.
Respect Scope in/out. Meet every acceptance criterion. Add/run the listed tests.
Open a PR (do not push to main). Include evidence in the PR body. Closes #N.
Do not expand into Phase 2 features.
```

---

## CI

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)

| Trigger | Paths |
|---------|--------|
| Push to `main` | `tenderpulse/**`, workflow file |
| Pull request → `main` | same |

| Job | What it runs |
|-----|----------------|
| **Build & test** | JDK 21 (Temurin), Gradle 8.11.1, `build -x test`, then `test` |

- Test result XML is uploaded as an artifact.  
- On failure, HTML test reports are uploaded.  
- **Do not merge** a PR that changes `tenderpulse/` if CI is red.

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
