# Sample Task for First Experiments

Use this (or adapt it) as your first `/loop` run.

## Task

Create a tiny Node.js (or Python) module that exposes a `healthCheck()` function returning `{ status: "ok", timestamp: <ISO string> }` and a corresponding unit test that asserts:

- the returned object has `status === "ok"`
- `timestamp` is a valid ISO-8601 string
- calling the function twice produces different timestamps (or at least does not throw)

## Suggested acceptance criteria

- Function is exported cleanly
- Unit test passes with the project's normal test runner
- No unused dependencies introduced
- Code is minimal and readable

## How to run it

From the project root in Claude Code:

```
/loop Implement the healthCheck function and unit test described in examples/sample-task.md
```

Watch how many cycles it takes, whether the Checker catches real failures, and how the stop rules behave.

## After the run

- Inspect the final diff
- Note the number of cycles and any escalations
- Adjust `max_cycles` or agent prompts if the behavior was too aggressive or too conservative
