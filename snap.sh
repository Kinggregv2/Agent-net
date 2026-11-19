#!/usr/bin/env bash
# For changes without a build (the bug list, "to add"): commit, push main, and a branch of its own, like build.sh does.
# usage: bash snap.sh "what changed"
cd "$(dirname "$0")"
git add -A
git diff --cached --quiet && { echo "nothing to save"; exit 0; }
git commit -q -m "$1

Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>" 2>&1 | grep -v commit-graph
git push -q origin main 2>&1 | grep -v commit-graph
B="notes-$(date '+%Y%m%d-%H%M%S')"
git branch -q "$B" && git push -q origin "$B" 2>&1 | grep -v commit-graph; echo "Saved $(git rev-parse --short HEAD), branch $B"
