#!/usr/bin/env bash
# =============================================================================
# Guard: no source file may be silently excluded by .gitignore.
#
# `backend/.gitignore` contained an unanchored `data/`, intended for the H2
# store. Unanchored patterns match at ANY depth, so it also matched the Java
# package `io/cassyx/api/data/` — and `DataController.java` / `DataDtos.java`
# were never committed.
#
# The failure mode is what makes this worth a gate: local builds passed (the
# files are on disk) while CI failed with "package io.cassyx.api.data does not
# exist". Nothing about the error points at .gitignore, and `git status` shows
# a clean tree, so it reads as a mysterious CI-only breakage.
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RED=$'\033[31m'; GRN=$'\033[32m'; OFF=$'\033[0m'

# Source trees only — build output (target/, dist/, node_modules/) is ignored on purpose.
# Written for bash 3.2 (macOS ships it): no `mapfile`, which silently no-ops there and
# made an earlier version of this guard report success while doing nothing.
#
# NOTE on the implementation: do NOT pass a pathspec like 'backend/*/src' here.
# git pathspec globs do not span '/', so that form matches nothing and the guard
# reports success while inspecting an empty list. Take the full ignored list and
# filter it instead.
ignored_file="$(mktemp)"
trap 'rm -f "$ignored_file"' EXIT
git status --ignored --porcelain 2>/dev/null \
  | awk '/^!! /{print substr($0,4)}' \
  | grep -E '(^|/)(backend/[^/]+/src|frontend/src|e2e/tests|scripts|openapi)/' \
  | grep -vE '/(target|node_modules|dist|coverage)/' \
  > "$ignored_file" || true

count="$(wc -l < "$ignored_file" | tr -d ' ')"
if [ "${count:-0}" -gt 0 ]; then
  printf "%s✗ source paths are excluded by .gitignore and will not reach CI:%s\n" "$RED" "$OFF" >&2
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    printf "    %s\n" "$p" >&2
    reason="$(git check-ignore -v "$p" 2>/dev/null | head -1)"
    [ -n "$reason" ] && printf "        matched by: %s\n" "$reason" >&2
  done < "$ignored_file"
  printf "\n  Anchor the pattern with a leading slash (e.g. /data/ not data/) so it cannot\n" >&2
  printf "  match a same-named directory deeper in the tree, then 'git add -f' the sources.\n" >&2
  exit 1
fi

printf "%s✓%s no source paths are gitignored\n" "$GRN" "$OFF"
