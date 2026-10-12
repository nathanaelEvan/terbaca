# Agent Rules — Terbaca

These rules apply to every task you (the executing agent) run from `docs/PRD.md` §8.
`docs/PRD.md` is the source of truth. If these rules ever conflict with PRD §0, PRD §0 wins.

You work through a whole milestone on your own: task after task, merging each one when CI is green. The owner reviews **once per milestone**, plus right away for the risky tasks listed in §3.

---

## 1. Per-task loop

For each task in the milestone, in order:

1. Branch from an up-to-date `main`: `<type>/<task-id>-<slug>`.
2. Before writing code, write a short checklist of what the task needs, from the PRD §8 line and the sections it touches. Keep it for yourself; you don't need to post it.
3. Implement it, with tests that actually run the new code (§4).
4. Run the local gates: `./gradlew test detekt lintDebug assembleDebug`.
5. Open the PR with the template (PRD §0.3). Keep it short, but always fill in **"Not verified"**.
6. Watch CI: `gh pr checks <pr> --watch`. If it fails, read the logs (`gh run view <id> --log-failed`), fix it with a new commit, and watch again.
7. Once CI is green, and unless the task is risky (§3), merge it: `gh pr merge <pr> --squash`. `main` is protected, so GitHub refuses the merge while required checks are failing.
8. Pull `main` and start the next task.

Don't stop to report between normal tasks. Keep going until the milestone is finished, a risky task needs review, or you are blocked (§2).

## 2. When to stop and ask the owner

Stop only when:
- a. The task needs credentials, secrets, a paid service, a new Android permission, or a dependency whose license is not permissive or not clear.
- b. The next step is destructive or hard to undo: force-push, rewriting history, deleting branches or files you did not create, pushing directly to `main`, changing repo settings.
- c. You believe a PRD requirement is wrong. Open a PR that edits `docs/PRD.md` with your reasoning, and skip to the next task that doesn't depend on it.

Fix everything else yourself (build errors, failing tests, lint, CI failures, missing tools you can install).

## 3. Risky tasks: open the PR, don't merge, ask for review

Don't merge a PR yourself, and ask the owner for a review, when it:
- adds or changes native/C++/JNI code, or its memory or thread handling;
- touches security: file access, permissions, passwords or encryption, IPC/Binder interfaces;
- adds a new third-party dependency or binary;
- includes an ADR (an architecture decision).

While that PR waits, carry on with the next task that doesn't depend on it, branching from `main`.

## 4. Verification standards

- **Native code must be run by a test.** Instrumented tests (`src/androidTest`) with small PDF fixtures. CI runs them on an x86_64 emulator.
- Be clear about the difference between **verified** (you ran it and saw the result) and **implemented** (written, not run). Never describe the second as the first. Anything not verified goes under "Not verified" in the PR.
- **Perf measurements are tiered** (PRD R1): a quick screen (Q, ≤ 30 min on the device) for each perf task, the full ×3 suite (F) only once per milestone. Don't run F for a single task. If a Q run shows < 10 % gain, close the PR as not effective.
- **Third-party binaries/code:** record the source URL, version and SHA-256, and ship the required license notices.
- **ADRs:** use repo-relative links. Status stays `proposed` until the owner or reviewer approves it, at the latest at the milestone review. List only people who were actually involved.

## 5. Milestone review

When every task in the milestone is merged (or waiting on review under §3):
1. Post one milestone report to the owner (§6).
2. The owner has Claude review the whole milestone (`git diff <milestone-start>..main`).
3. Fix the findings in follow-up PRs (`fix/<milestone>-review-<slug>`), using the same loop as §1.
4. If you disagree with a finding, say why instead of silently ignoring it.

## 6. Report format

Keep it short. Use this format only at the end of a milestone, or when you stop under §2 or §3:
1. **Done:** tasks merged, with PR links.
2. **Waiting:** PRs open for review, and why.
3. **Not verified:** anything that wasn't run, and why.
4. **Risks / follow-ups:** a short list.

## 7. Development style (Ponytail)

**Applies to:** Gemini only (`Claude` is exempt and can bypass this).

When Gemini executes coding tasks, it must write code with the `ponytail` skill active (`full` mode):
- **The ladder:** Question need (YAGNI) → reuse existing codebase utils/patterns → stdlib first → native platform features → already-installed dependencies → one line → minimum working code.
- **Root cause over symptom:** Always trace full call paths before editing; fix where callers route through.
- **Zero bloat:** No unrequested abstractions, no single-implementation interfaces, no speculative scaffolding, deletion over addition, fewest files possible, boring over clever.
- **Never cut:** Native memory safety, bounds checks, trust-boundary validation, data-loss error handling, and runnable tests (§4).
- **Concise output:** Code first; minimal prose; no unrequested design essays.

