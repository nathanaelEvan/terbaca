# Agent Rules — Terbaca

These rules apply to every task you (the executing agent) run from `docs/PRD.md` §8.
`docs/PRD.md` is the source of truth. These rules add to PRD §0; if they ever conflict, PRD §0 wins.

You are an autonomous execution agent. Complete each task end-to-end with no unnecessary interruptions — but within the PR/review process defined in PRD §0.2.

---

## 1. Mandatory task checklist

- Before taking any action on a task, write an explicit, numbered checklist covering everything the task needs. Derive it from the task line in PRD §8 **and** every PRD section it touches (cite them, e.g. "§4.1, F-OPEN-3").
- Every checklist always ends with these closing items:
  1. Tests that actually exercise the new code (see §6)
  2. `./gradlew test`, `./gradlew detekt`, `./gradlew lintDebug`, `./gradlew assembleDebug` — all green locally
  3. ADR written, if the task says "ADR required"
  4. Self-review of the full diff (`git diff main...HEAD`)
  5. Branch pushed, PR opened with the complete template (PRD §0.3)
  6. CI green on the latest commit
- Status markers: `[ ]` pending, `[>]` in progress, `[x]` done, `[!]` blocked, `[~]` done but **not verified** (say why).
- Show the updated checklist every time you report progress.
- An item is `[x]` only with evidence: the command you ran and its result, or the file/output that proves it. "It compiles" or "it should work" is not evidence that the feature works.

## 2. No standalone progress stops

- Never end your turn just to summarize progress, announce your next step, or ask for passive approval ("shall I continue?").
- If you want to post a status note, put it in the same response as your next tool call.
- While any checklist item is open and not blocked, every turn must make progress with a tool call.

## 3. Background jobs and monitoring

- Gradle builds, CI runs, emulator/device runs and other long jobs must be tracked until they finish. Never assume they succeeded.
- CI: watch with `gh pr checks <pr> --watch` (or `gh run watch <run-id>`). On failure, read the logs (`gh run view <run-id> --log-failed`), fix the cause, push a **new commit**, and watch again.
- If a job hangs or runs far longer than expected, find the bottleneck (logs, daemon status, network) and retry or work around it. Do not silently end the turn.

## 4. Loop until the task is done — and what "done" means here

A task is **done** when all of these are true:
- every checklist item is `[x]`, or `[~]` with the reason written in both the PR and your report;
- the branch is pushed, following `<type>/<task-id>-<slug>`;
- the PR is open, the template is fully filled in, and the diff is under ~1000 changed lines excluding generated/vendored files (otherwise split it into `a/b/c` PRs);
- CI is green on the latest commit.

Then **merge or stop and report** (PRD §0.2):
- You may squash-merge your own PR (`gh pr merge <pr> --squash`) once the PRD §0.2 merge conditions hold: CI green on the latest commit, local gates green, template complete with anything unverified stated, and no unresolved review comments or "changes requested" reviews.
- If the owner asked to review first, or the PR has open review threads, stop and report "PR open, awaiting review" instead.
- Mark a task done in the milestone list only after its PR is merged.
- Do not start the next task on top of an unmerged branch. Start it from an up-to-date `main` after the merge, or from `main` (not the open PR) if the owner explicitly allows parallel work.

The only valid reasons to stop **before** a task is done:
- a. The task needs credentials, secrets, a paid service, a new Android permission, or a dependency whose license is not permissive or not clear (PRD §0.2).
- b. The next step is destructive or hard to undo: force-push, rewriting history, deleting branches/files you did not create, pushing to `main`, changing repo settings.
- c. You believe a PRD requirement is wrong. Open a PR that edits `docs/PRD.md` with your reasoning, then stop work on the affected task (PRD §0.2).

For anything else (build errors, failing tests, lint, CI failures, missing tools you can install), fix it yourself and keep going.

## 5. Review rounds

When the owner relays review comments:
1. Turn every comment into a checklist item.
2. Address each one with **new commits**. Never force-push after review has started.
3. Reply to each comment with what changed and the commit SHA (`gh pr comment` or a reply on the review thread).
4. Re-run the local gates, wait for CI to go green, then report with the updated checklist.

If you disagree with a comment, reply with your reasoning instead of silently ignoring it.

## 6. Verification standards

- **Native/JNI code must be executed by a test.** Exception-mapping unit tests or checking APK packaging do not prove that open/render/close works. Add instrumented tests (`src/androidTest`) using small PDF fixtures (valid, password-protected, corrupt, empty). If you cannot run them (no device/emulator), mark the item `[~]` and say so plainly in the PR and your report.
- Be clear about the difference between "verified" (ran it and saw the result) and "implemented" (written, not run). Never describe the second as the first.
- **Third-party binaries/code:** record the source URL, version, and SHA-256 of the downloaded artifact, and ship the required license notices (e.g. `LICENSE`/`NOTICE` for PDFium and its bundled third-party libraries).
- **ADRs:**
  - Use repo-relative links (`../PRD.md#41-engine`), never local `file:///` paths.
  - Status stays `proposed` until the reviewer approves.
  - List only people who were actually involved as deciders or consulted.

## 7. Final report format

At the end of every task or review round, report:
1. The final checklist with statuses
2. The PR link and the CI run link with its result
3. What was **not** verified, and why
4. Open risks and follow-ups
5. Current status: e.g. "M1-02 — PR #2 open, awaiting review"
