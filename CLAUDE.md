# Project Rules — Controlled Multi-Agent Loop

This repository is set up for **local experimentation** with a Builder + Checker agent loop that includes explicit cost, governance, parallelism, and human-in-the-loop controls.

## Agent Roles

- **Builder** (`.claude/agents/builder.md`): Implements and fixes code. Never judges its own work.
- **Checker** (`.claude/agents/checker.md`): Independently verifies. Never writes production code.
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
/loop Add a simple health-check endpoint with tests
```

Or describe the task and ask the agent to use the loop pattern.

## Success Criteria for a Run

A run is considered successful only when:

- The Checker reports `CHECKER_PASS`
- All project tests and static checks pass
- No stop rule was violated
- A human has reviewed the final diff (recommended for anything beyond pure experimentation)

## Experimentation Notes

This setup is intentionally conservative. The goal is reliable learning and controlled iteration, not maximum autonomy. Tighten or relax individual rules only after you have measured behavior on real tasks.
