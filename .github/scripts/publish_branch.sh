#!/usr/bin/env bash
# Usage: publish_branch.sh <branch> <dir> <message>
# Force-pushes the contents of <dir> as a single orphan commit to <branch>.
# Keeps generated data out of main's history and the repository small.
set -euo pipefail
BRANCH="$1"; DIR="$2"; MSG="$3"
cd "$DIR"
rm -rf .git
git init -q -b "$BRANCH"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
git commit -q -m "$MSG"
git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
for i in 1 2 3; do
  if git push -q -f origin "$BRANCH"; then exit 0; fi
  sleep $((i * 5))
done
echo "push to $BRANCH failed" >&2
exit 1
