# 4a. Automatic upstream merges

Read [README.md](README.md) first. Depends on: nothing. Independent of
phases 2 and 3.

## Goal

Upstream keeps fixing extension compatibility in `engine/` and `library/`.
A weekly job merges those fixes, rebuilds the image, and only asks for a
human when a merge really conflicts.

## Why merges are not trivial

This fork deleted most of upstream's files (the macOS client, scripts,
docs). Whenever upstream changes one of them, git reports a "modified by
them, deleted by us" conflict, and files upstream newly adds there would
come back. Both cases must be resolved by deleting again, automatically.
Real conflicts can only occur in the few upstream files this fork edits.

## Step 1: remove an avoidable conflict

`settings.gradle.kts` was changed in the fork (`rootProject.name`). Restore
upstream's line so the file is identical to upstream:

```kotlin
rootProject.name = "CloudStream-macOS"
```

## Step 2: two list files in the repository root

`.hless-removed`: paths this fork does not carry, one per line, as git
pathspecs. Generate the first version from history instead of typing it:

```bash
git diff --name-only --diff-filter=D 79293df 91a51fa | sed 's|/.*||' | sort -u
```

That prints the top-level names deleted by the strip commit. For top-level
directories that were only partly deleted (`docs`, `scripts`, `.github`),
list the deleted children instead of the directory. Expected content:

```
client
Icons
Makefile
run.sh
AI-POLICY.md
CHANGELOG.md
CONTRIBUTING.md
SECURITY.md
.github/FUNDING.yml
.github/ISSUE_TEMPLATE
.github/dependabot.yml
.github/pull_request_template.md
docs/Architecture.md
docs/Context.md
docs/Design.md
docs/Maintenance.md
docs/README.md
docs/Remaining-Work.md
docs/Rules.md
docs/qa-ui-checklist.md
docs/archive
docs/help
docs/screenshots
```

Plus every file under `scripts/` and `docs/adr/` that the strip commit
deleted (take them from the full output of the `git diff` above, without
the `sed`).

`.hless-ours`: upstream files this fork rewrote and always keeps its own
version of:

```
README.md
.github/workflows/ci.yml
```

## Step 3: the merge script

`scripts/upstream_sync.sh`, bash, `set -euo pipefail`. Exit status: 0 merged
or nothing to do, 2 real conflict (prints the conflicted paths).

1. `git remote add upstream https://github.com/krishnasinghrao/cloudstream-macos || true`
   and `git fetch upstream main`.
2. If `git merge-base --is-ancestor upstream/main HEAD`: print
   "up to date", exit 0.
3. `git merge --no-commit --no-ff upstream/main || true`
4. For every line `p` of `.hless-removed`:
   `git rm -r -q --cached --ignore-unmatch -- "$p"` and `rm -rf -- "$p"`.
   This settles both the "deleted by us" conflicts and newly added files.
5. For every line `p` of `.hless-ours`:
   `git checkout --ours -- "$p" 2>/dev/null || true` then `git add -- "$p"`.
6. Any new top-level entry that upstream added and this fork has never had
   is kept. That is deliberate: it will show up in the diff for a human to
   judge.
7. `conflicts=$(git diff --name-only --diff-filter=U)`. If not empty: print
   them, `git merge --abort`, exit 2.
8. `git commit -q -m "Merge upstream $(git rev-parse --short upstream/main)"`.

Test it locally before automating: run it in a clone, then
`./gradlew :engine:jar`.

## Step 4: the workflow

`.github/workflows/upstream.yml`:

- Triggers: `schedule` with cron `17 4 * * 1` (Mondays), and
  `workflow_dispatch`.
- Permissions: `contents: write`, `issues: write`, `actions: write`.
- Steps:
  1. `actions/checkout` with `fetch-depth: 0`.
  2. Set git user to `github-actions[bot]`.
  3. JDK 17 and Gradle setup, copied from `ci.yml`.
  4. Run `scripts/upstream_sync.sh`, capturing its exit status and output.
  5. Status 0 and `HEAD` changed:
     `./gradlew :engine:test :engine:jar --console=plain`. If that passes,
     `git push origin HEAD:main`, then `gh workflow run ci.yml --ref main`.
     The explicit run is needed: a push made with the workflow's own token
     does not start other workflows, so the image would not be rebuilt.
  6. Status 2, or the build in step 5 failed: open an issue titled
     `Upstream merge needs attention` containing the script's output or the
     failing task, unless an open issue with that title exists
     (`gh issue list --search`). Do not push anything.
- `gh` needs `GH_TOKEN: ${{ github.token }}` in the step's environment.

## When the issue appears

A person or a model resolves it locally:

```bash
git fetch upstream
```

```bash
git merge upstream/main
```

then deletes what `.hless-removed` lists, resolves the remaining conflicts
(they can only be in the upstream files recorded under "Local changes to
upstream files" in the README, or in `engine/build.gradle.kts`), runs the
tests, and pushes.

## Done when

- A manual run of the workflow on an up-to-date fork ends green and prints
  "up to date".
- After upstream gains a commit, a run merges it, pushes, and the `ci`
  workflow builds a new image.
- A forced conflict (edit an upstream file on a test branch) produces the
  issue and no push.
