# Orchestrator Workflow — Enhanced /loop with Automation

This document explains how the orchestrator handles task completion, auto-commenting, external review, and template log updates.

## Overview

After a task reaches `CHECKER_PASS`, the orchestrator follows this flow:

```
CHECKER_PASS
    ↓
[Wait for PR merge]
    ↓
[GitHub Actions auto-comments issue]
    ↓
[Orchestrator extracts learnings + template changes]
    ↓
[Human reviews learnings and template proposals]
    ↓
[Conditional Reviewer agent (if P0/first implementation)]
    ↓
[REVIEWER_PASS or escalate]
    ↓
[Human gate: Continue to next task?]
    ↓
[Loop or stop]
```

## Step-by-Step Details

### 1. PR Merge Confirmation

After `CHECKER_PASS`, the orchestrator waits for the PR to be merged to `main`. The human merges via GitHub.

**Automation:** GitHub Actions workflow (`.github/workflows/post-merge-comment.yml`) triggers on merge.

### 2. Auto-Comment on Issue

When the PR is merged, GitHub Actions posts a summary comment to the linked issue:

```markdown
## /loop Summary — Task Complete ✅

**PR:** #12 — feat: add ZW-ready Tender schema...
**Branch:** `tp-002-normalised-tender-schema`
**Completion:** Builder → Checker PASS → Merged

### Next Steps
1. ✅ PR merged to main
2. ⏳ Template improvement log updated (human review)
3. ⏳ Conditional reviewer (if P0/critical path)
4. ⏳ Human gate: Continue to next task?
```

**How it works:**
- Extracts issue number from PR body (`Closes #N`)
- Checks if branch matches `tp-*` pattern
- Posts a standardized summary via GitHub API

### 3. Task Specification Improvement

The orchestrator improves the TASK ITSELF for future agents, preventing hallucination and guessing.

**a) Extract spec gaps from Builder + Checker:**

What was unclear, incomplete, or missing in the original issue?
- Builder: "Had to infer that currency field was needed; AC didn't list all required fields"
- Checker: "Edge case (null deadline) wasn't mentioned in test cases; discovered during implementation"
- Gap: "Estimate was S but task took understanding of ZW context; should be S→M or add background note"

**b) Propose fixes to the task card itself:**

Update the issue description/AC/test cases to be unambiguous:

**Example — before (vague):**
```markdown
**Acceptance criteria:**
- Schema documented (fields, types, required)
- Sample ZW tender maps with no critical orphan data
- Code/entity updated if gaps found
```

**Example — after (explicit):**
```markdown
**Acceptance criteria:**
- Schema documented with table: field name, type, required/optional, ZW notes
  - Required: id (UUID), title (String), issuingAuthority (String), sourceUrl (String), 
    sourceName (String), publishedAt (Instant)
  - Optional: description, externalTenderId (String, for deduplication), currency (String, e.g. USD/ZWL), 
    deadline (Instant, can be null), sector, region, keywords, valueMin/Max
- Sample ZW tender (e.g. TR22053) maps with ALL required fields + common optional fields (currency, external ID)
- Test case: Tender with deadline=null still stores and matches
- Code/entity updated if gaps found
```

**c) Prompt human for spec fixes:**
```
Task Specification Improvements — Review & Confirm

Issue: TP-002 AC was vague about required/optional fields
Fix proposed:
  - Add explicit field table to AC (name, type, required/optional)
  - Add ZW-specific context (currency codes, external ID for e-GP)
  - Add edge case test (null deadline)
  - Update estimate from S to S (accurate, but add "requires ZW context" note)

Update task card in MVP_CHECKLIST_BOARD.md? (y/n)
```

**d) Auto-update task card + log:**
   - Update task description/AC in MVP_CHECKLIST_BOARD.md (improve clarity)
   - Mark task ✅ in checklist
   - Add row to template improvement log table

**Example log entry:**
```markdown
| 2026-08-31 | TP-002 | AC was vague on required/optional fields; edge case (null deadline) discovered in testing | - Add explicit field table (type, required/optional); add null deadline test case; estimate S accurate |
```

**Why this matters:**
- Next agent building TP-003 or TP-011 gets better spec for similar work
- Prevents Builder from hallucinating or guessing at requirements
- Accumulates institutional knowledge in the task templates
- 100% pass rate through better specs, not just better agents

### 4. Conditional External Review

If the task matches reviewer triggers (P0, first implementation, etc.), the orchestrator invokes the Reviewer agent:

**Triggers:**
```
✅ P0 priority (TP-002, TP-003, TP-004, TP-011, TP-012, TP-020, TP-010, TP-030, TP-032, TP-041)
✅ First implementations (new adapters, new APIs, new jobs)
✅ Schema/database changes
✅ Core logic (matching, notifications, aggregation)
❌ Docs-only (skip review)
❌ Simple fixes (typos, config — skip review)
```

**Reviewer checks:**
- Design fit with project patterns
- Edge case handling
- Code quality and maintainability
- Alignment with constraints
- Risk of regressions

**Reviewer output:**
```
REVIEWER_PASS
- Summary: Design is sound; tests comprehensive; no architectural concerns
- Strengths: Good separation of concerns; fixtures prevent live network
- Suggestions: Consider factory pattern for Tender creation in test helpers (minor)
- Risk: Low
- Notes: Schema update is backward-compatible; safe to proceed
```

or

```
REVIEWER_FAIL
- Summary: Currency field is hardcoded; should support configurable defaults
- Concerns:
  - Models.kt:53 currency defaults to null; USD is assumed in matching logic
- Impact: Tender matching may silently use wrong currency for ZW
- Recommendation: Make currency explicit or add validation
```

If FAIL: Orchestrator escalates; human decides whether to fix or accept risk.

### 5. Human Gate: Continue to Next Task?

After all automation is complete, orchestrator asks:

```
🎯 TP-002 Complete!

Summary:
- PR #12 merged
- Template log updated
- Reviewer: PASS
- All quality gates cleared

Next task: TP-003 (Implement first Zimbabwe tender source adapter)
- Dependency: TP-001 ✅, TP-002 ✅
- Estimate: M
- P0 priority

Continue to TP-003? (y/n)
```

**Human chooses:**
- `y` → Orchestrator runs `/loop Complete TP-003...` in same session
- `n` → Orchestrator stops; awaits direction for next task

## Automation Benefits

| Step | Manual before | Automated now | Benefit |
|------|---------------|---------------|---------|
| Issue comment | Manual copy-paste | GitHub Actions | No toil; consistent format |
| Template log | Manual edit | Orchestrator prompts + auto-updates | Closes feedback loop; prevents skipping |
| External review | Optional, manual request | Conditional, automatic for P0 | Quality gate without overhead |
| Proceed decision | Assumed or guessed | Explicit human gate | Clear handoff; prevents runaway |

## Orchestrator Implementation Notes

When implementing the orchestrator (in `/loop`), handle these steps:

1. **After CHECKER_PASS:**
   - Confirm PR is merged (ask human or check GitHub API)

2. **Extract learnings:**
   - Parse Builder summary for discoveries
   - Parse Checker summary for edge cases
   - Draft 1–2 sentence synthesis
   - Show to human; collect confirmation or edits

3. **Propose template changes:**
   - Estimate time (S/M/L): did actual = estimate?
   - Test coverage: were acceptance criteria sufficient?
   - Any AC ambiguity?
   - Suggest improvements for task template

4. **Update MVP_CHECKLIST_BOARD.md:**
   - Add row to template improvement log
   - Mark task ✅
   - Preserve existing format

5. **Trigger Reviewer (if applicable):**
   - Detect task type (P0 vs. P1, first impl vs. follow-up)
   - Invoke Reviewer agent if conditions met
   - Wait for REVIEWER_PASS or REVIEWER_FAIL
   - Escalate if FAIL

6. **Human gate:**
   - Summarize task completion
   - Show next task in sequence
   - Prompt: continue?
   - If yes: run next `/loop`; if no: stop

## Next Tasks Unlocked

- **TP-003** — Implement first Zimbabwe tender source adapter (depends on TP-002 ✅)
- All other independent P0 tasks can be parallelized (TP-010, TP-020, etc.)

---

**For details on agent roles, see:**
- [.claude/agents/builder.md](./.claude/agents/builder.md)
- [.claude/agents/checker.md](./.claude/agents/checker.md)
- [.claude/agents/reviewer.md](./.claude/agents/reviewer.md)

**For process workflow, see:**
- [CONTRIBUTING.md](../CONTRIBUTING.md)
- [CLAUDE.md](../CLAUDE.md) — "Orchestrator Workflow: Enhanced /loop"
