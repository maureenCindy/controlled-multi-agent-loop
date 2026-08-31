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

### 3. Template Improvement Log Update

The orchestrator (you) will:

**a) Extract learnings from Builder + Checker output:**
   - Builder: What did they discover during implementation? (gaps, surprises)
   - Checker: What edge cases or gaps did verification reveal?
   - Synthesize into 1–2 sentence learning

**Example:**
- Builder found: "Currency field was needed for ZW tenders; external tender ID for deduplication"
- Checker found: "Deadline is truly optional; matching logic unaffected when null"
- **Learning:** "Tender schema needs currency tracking and external ID for ZW; deadline is optional but safely nullable"

**b) Propose template changes:**
   - Estimate accuracy: Did task take longer/shorter than S/M/L?
   - Test adequacy: Were acceptance criteria and test cases sufficient?
   - AC clarity: Were any criteria ambiguous?
   - Task template improvements for future similar tasks

**Example proposals:**
- ✅ "Estimate was accurate (S = 1 cycle)"
- ✅ "Test cases were comprehensive; caught null deadline edge case"
- 💡 "Future schema tasks: add 'currency' to checklist"

**c) Prompt human for review:**
```
Template Improvement Log — Review & Confirm

Proposed learning:
  "Tender schema needs currency + external ID for ZW; deadline is optional"

Proposed template changes:
  - Estimate: S (accurate)
  - Add checklist item: "Currency field required for multi-region tenders"

Accept and update? (y/n)
```

**d) Auto-update `docs/MVP_CHECKLIST_BOARD.md`:**
   - Add row to template improvement log table
   - Mark task ✅ in checklist
   - Update estimate/test notes if changed

**Example log entry:**
```markdown
| 2026-08-31 | TP-002 | Tender schema needs currency + external ID for ZW; deadline is optional | - Estimate S accurate; add currency to schema template for multi-region tasks |
```

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
