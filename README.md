# Controlled Multi-Agent Build–Check Loop

Local experimentation scaffold for a **Builder + Checker** agent team that keeps iterating until the work actually passes — with explicit controls for cost, governance, parallelism, escalation, and human-in-the-loop.

Inspired by practical multi-agent patterns used in modern AI coding workflows, adapted for safe local use.

## Why this exists

A one-shot agent often hands you incomplete or broken work.  
A looping team that feeds failures back to a dedicated builder, verified by an independent checker, closes that gap.  
Hard stop rules prevent infinite token burn and force human attention when the team is stuck.

## Project Structure

```
.
├── .claude/
│   ├── agents/
│   │   ├── builder.md      # Implements / fixes code
│   │   └── checker.md      # Independently verifies (never writes production code)
│   └── commands/
│       └── loop.md         # Orchestrator that drives the cycle
├── CLAUDE.md               # Global rules: stop conditions, governance, cost, HITL
├── examples/
│   └── sample-task.md      # Example task you can try
├── .gitignore
└── README.md
```

## Quick Start

### Prerequisites

- [Claude Code](https://claude.ai/code) installed and authenticated
- A real project (or a small sample) with a runnable test suite

### 1. Clone / copy this scaffold into your target repo (or start here)

```bash
# If you are already inside a repo you want to instrument:
cp -r /path/to/this/scaffold/.claude .
cp /path/to/this/scaffold/CLAUDE.md .
```

Or work directly inside this repository for pure experiments.

### 2. Open Claude Code in the project root

```bash
claude
```

### 3. Run the loop

```
/loop Add a /health endpoint that returns {"status":"ok"} and include a unit test
```

The orchestrator will:

1. Hand the task to the Builder
2. Hand the result to the Checker
3. On failure → feed the precise failure back to the Builder
4. Repeat until PASS, max cycles, or repeated identical failure
5. Escalate clearly when human input is required

## Built-in Controls

| Control | How it is enforced |
|---------|--------------------|
| **Max cycles** | Default 5 (configurable in `CLAUDE.md` / loop) |
| **Same failure twice** | Signature comparison → immediate stop + escalate |
| **No self-checking** | Builder never runs the final verification |
| **No weakening tests** | Explicit rule in both agents + CLAUDE.md |
| **Cost awareness** | Model tiering guidance + budget respect + early stop |
| **Human-in-the-loop** | Forced escalation on blocked / stuck / budget states |
| **Parallelism** | Prefer git worktrees; start small |
| **Governance** | All rules live in git and are reviewable |

## Recommended Experimentation Path

1. Start with a tiny, well-tested task (see `examples/sample-task.md`).
2. Observe number of cycles, failure signatures, and token usage.
3. Only then increase max cycles or introduce parallel worktrees.
4. Measure before relaxing any stop rule.

## Customizing

- Edit `.claude/agents/builder.md` and `checker.md` to match your stack (test command, language, style).
- Adjust `max_cycles` and other thresholds in `CLAUDE.md` or the loop command.
- Add more specialist agents later (security reviewer, docs writer, etc.) once the basic loop is reliable.
- For stronger multi-session parallelism and dashboards, consider external orchestrators (Orca, Emdash, amux, etc.) on top of this foundation.

## Safety Notes

This is an **experimentation scaffold**.  
Do not point it at production secrets, unrestricted cloud credentials, or customer data without additional isolation and review.  
Always keep a human in the final approval path for anything that will be shipped.

## License

MIT — use, modify, and share freely.
