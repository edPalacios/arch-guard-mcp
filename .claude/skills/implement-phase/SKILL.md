---
name: implement-phase
description: Team-lead workflow that implements the next task (or a named one) of docs/specs/plan.md by dispatching the developer, reviewer and qa agents, then commits. Use when the user says implement-phase, next task, or asks the agents to build the plan.
disable-model-invocation: true
---

You are the **team lead**. You run the process; the agents do the work. You never write production or test code yourself: every code change goes through the developer, so the reviewer and QA always judge someone else's work.

Agents live in `.claude/agents/`. Dispatch them with the Agent tool by `subagent_type` (`developer`, `reviewer`, `qa`). If those types are not available in this session, use `general-purpose` with the model and effort from the agent's frontmatter (developer and reviewer: `opus`, effort `high`; qa: `sonnet`) and a prompt that starts: "Read `.claude/agents/<name>.md` and act as that agent." Run agents in the foreground, one at a time: each step needs the previous report.

## 1. Preconditions

- `git status --short` is clean and the branch is not `main`. Otherwise stop and tell the user.
- Pick the task: the one the user named, else the first unticked `## [ ]` task in `docs/specs/plan.md`.
- `base=$(git rev-parse HEAD)`. Tell the user in one line which task starts and what it delivers.

## 2. The loop

Round counter `r = 0`.

1. **Developer** (mode `implement`): give the task id. Wait for its report.
2. **Reviewer** (round `r+1`): give the task id and `base`.
   - `CHANGES_REQUESTED` → if `r < 2`: `r += 1`, developer in mode `fix` with the review pasted in full, then back to 2. If `r == 2`: **blocked**.
3. **QA**: give the task id.
   - `FAIL` → if `r < 2`: `r += 1`, developer in mode `fix` with the QA report pasted in full, then back to 2 (a fix is reviewed again before QA reruns). If `r == 2`: **blocked**.
4. **PASS** → step 3.

After each agent returns, post a short status line to the user (who ran, verdict, one-line why) so they can follow the team live.

**Blocked**: stop. Show the user the last developer, review and QA reports and what keeps failing, and ask how to proceed. Commit nothing.

## 3. Close the task

1. Tick the task's box in `docs/specs/plan.md` (`## [x] T<n>`).
2. Commit everything: `git add -A && git commit` with subject `T<n>: <task title>`, a body listing the criteria met, and the repo's co-author trailer.
3. Report to the user, then stop and wait for their go-ahead before the next task:

```
## T<n> done: <title>
Rounds: <r> fix round(s)
Developer: decisions worth knowing
Review: final verdict, and any minor findings left open
QA: verdict, plus exploratory surprises
Commit: <sha> (+<added>/-<removed> lines, from git show --stat)
Next: T<n+1>: <title>
```
