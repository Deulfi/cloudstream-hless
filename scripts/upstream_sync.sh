#!/usr/bin/env bash
# Merge upstream/main into this fork.
#
# This fork deleted most of upstream's files and rewrote a few others.
# A plain merge reports "modified by them, deleted by us" conflicts for the
# deletions and resurrects files upstream newly adds there. Both are settled
# here with .hless-removed; .hless-ours keeps this fork's versions.
#
# Exit 0: merged, or already up to date.
# Exit 2: real conflict; the conflicted paths are printed, the merge is
#         aborted, and nothing is committed.
set -euo pipefail

cd "$(dirname "$0")/.."

git remote add upstream https://github.com/krishnasinghrao/cloudstream-macos 2>/dev/null || true
git fetch upstream main

if git merge-base --is-ancestor upstream/main HEAD; then
  echo "up to date"
  exit 0
fi

git merge --no-commit --no-ff upstream/main || true

while IFS= read -r p; do
  [ -n "$p" ] || continue
  git rm -r -q --cached --ignore-unmatch -- "$p"
  rm -rf -- "$p"
done < .hless-removed

while IFS= read -r p; do
  [ -n "$p" ] || continue
  git checkout --ours -- "$p" 2>/dev/null || true
  git add -- "$p"
done < .hless-ours

conflicts="$(git diff --name-only --diff-filter=U)"
if [ -n "$conflicts" ]; then
  printf '%s\n' "$conflicts"
  git merge --abort
  exit 2
fi

git commit -q -m "Merge upstream $(git rev-parse --short upstream/main)"
