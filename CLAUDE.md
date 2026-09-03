# Project Rules — Controlled Multi-Agent Loop

This repository is set up for **local experimentation** with a Builder + Checker agent loop that includes explicit cost, governance, parallelism, and human-in-the-loop controls.

It also hosts the **TenderPulse** Kotlin MVP. Product and process docs under `docs/` and `CONTRIBUTING.md` apply to all TenderPulse work.

## Contribution workflow (mandatory)

**Always follow [CONTRIBUTING.md](CONTRIBUTING.md).** Do not wait for the user to paste it into the prompt.

Non-negotiable process rules:

1. **Issues are the source of truth** for scope (acceptance criteria + test cases). Also read `tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md` when linked.
2. **Application code reaches `main` only via pull request** — never push app code, tests, or runtime config straight to `main`.
3. **Branch naming** as in CONTRIBUTING (e.g. `tp-002-normalised-tender-schema`).
4. **PR body** must use the project PR template: summary, acceptance criteria checklist, **test evidence**, scope in/out, and `Closes #N` / `Fixes #N`.
5. **CI** (`.github/workflows/ci.yml`) must be treated as required for `tenderpulse/apps/api/` changes — do not consider the task done if build/tests are red.
6. **Do not expand into Phase 2** (tender registration, application checklist, apply templates) unless the issue explicitly says so.
7. Pure docs/research may land on `main` only after explicit human review; prefer a docs PR when practical.

Suggested minimal task prompt (process is already required by this file):

```text
/loop Complete issue #N (TP-xxx). Meet AC and tests. Open a PR; Closes #N.
```

## TenderPulse product constraints (mandatory for product tasks)

- **MVP scrape source:** PRAZ e-GP only — `https://egp.praz.org.zw/` (see `tenderpulse/docs/specs/zw-tender-sources.md`).
- **Aggregation / notify:** follow `tenderpulse/docs/specs/aggregation-policy.md` — shared 3×/day fetch; Free = daily digest; Paid = on-match after each run.
- Public summary fields + **official link only**; polite rate limits; no full bid-document hosting.
- No live network in unit tests — use fixtures.

## Agent Roles

- **Builder** (`.claude/agents/builder.md`): Implements and fixes code. Never judges its own work.
- **Checker** (`.claude/agents/checker.md`): Independently verifies. Never writes production code.
- **Reviewer** (`.claude/agents/reviewer.md`): External quality gate for P0/critical-path tasks. Triggered conditionally.
- **Orchestrator** (`/loop` command): Drives the cycle, enforces stop conditions, and escalates.

## Hard Stop Rules (non-negotiable)

1. **Max cycles**: Default 5. The loop must stop after this many build-check iterations unless a human explicitly overrides.
2. **Same failure twice**: If the Checker reports an essentially identical failure in two consecutive cycles, stop immediately and escalate. Do not guess further.
3. **No self-checking**: The Builder must never run the final verification. The Checker is the only authority on pass/fail.
4. **No weakening checks**: Never delete, skip, or relax tests, linters, or type checks to force a pass.
5. **Budget awareness**: Respect any token or dollar budget stated by the user. Prefer early escalation over expensive thrashing.

## Human-in-the-Loop

- Any `BUILDER_BLOCKED`, `CHECKER_BLOCKED`, max-cycles, or repeated-failure condition must escalate with a clear report.
- High-risk actions (e.g. changing CI config, deleting large amounts of code, touching secrets) should pause for human approval when possible.
- The human remains the final authority. Agents propose; humans decide.

## Cost & Token Controls

- Use the cheapest capable model that can do the job (e.g. stronger model for planning/orchestration, lighter for routine verification when appropriate).
- Keep context windows focused — clear history between major phases when it does not hurt quality.
- Track approximate spend and surface it in escalation reports.
- Prefer short, targeted tool calls over broad exploration.

## Parallelism Guidelines

- Independent tasks may run in separate git worktrees to avoid conflicts.
- Start with 1–2 concurrent agents for experimentation. Scale only after measuring cost and coordination overhead.
- Never let two agents edit the same files concurrently without isolation.

## Governance & Audit

- All agent definitions and stop rules live in this repository and should be reviewed like any other code.
- Prefer deterministic verification (tests, linters) over pure LLM judgment.
- Log key decisions and failure signatures so runs are inspectable after the fact.
- Do not grant unrestricted network access or secrets to experimental agents.

## How to Run

```bash
# From the project root (after installing Claude Code)
claude

# Then inside the session:
/loop Complete issue #2 (TP-002). Meet AC and tests. Open a PR; Closes #2.
```

Process, CI, and product constraints above apply automatically via this file and CONTRIBUTING.md.

## Orchestrator Workflow: Enhanced /loop

After `CHECKER_PASS`, the orchestrator performs these steps automatically before gating to the next task:

1. **PR Merge**: Wait for/confirm PR is merged to `main`
2. **Auto-comment Issue**: Post `/loop` summary to the issue (via GitHub Actions)
3. **Improve Task Specification**:
   - Extract gaps from task spec that caused confusion or hallucination
   - Examples: "AC didn't list all required fields", "test cases missed null deadline edge case", "estimate was wrong"
   - Propose fixes to the task card itself (in MVP_CHECKLIST_BOARD.md):
     - Better AC wording, explicit field lists, schema tables
     - More comprehensive test cases (edge cases)
     - Corrected estimates
   - Prompt human: review/edit proposed spec improvements
   - Auto-update `tenderpulse/docs/specs/MVP_CHECKLIST_BOARD.md` task card + template improvement log
4. **Conditional External Review** (if P0 or first implementation):
   - Invoke Reviewer agent to independently verify
   - Reviewer reports: `REVIEWER_PASS` or `REVIEWER_FAIL`
   - If FAIL: escalate; if PASS: proceed
5. **Human Gate**: "Continue to next task? (y/n)"
6. **Loop or Stop**: If yes → run next `/loop`; if no → stop and await direction

**Conditional Reviewer Triggers**:
- ✅ P0 priority tasks (TP-002, TP-003, TP-004, TP-011, TP-012)
- ✅ First implementations (new adapters, new APIs)
- ✅ Schema/database changes
- ✅ Core logic changes (matching, notifications, aggregation)
- ❌ Docs-only changes
- ❌ Simple fixes (typos, config tweaks)

## Success Criteria for a Run

A run is considered successful only when:

- The Checker reports `CHECKER_PASS`
- All project tests and static checks pass (and CI is green for `tenderpulse/apps/api/` changes)
- CONTRIBUTING.md workflow was followed for code (branch + PR + evidence + `Closes #N`)
- No stop rule was violated
- Template improvement log updated (if not docs-only)
- If triggered: Reviewer reports `REVIEWER_PASS`
- A human has reviewed the final PR/diff and approved proceeding (or stopping)

## Experimentation Notes

This setup is intentionally conservative. The goal is reliable learning and controlled iteration, not maximum autonomy. Tighten or relax individual rules only after you have measured behavior on real tasks.
