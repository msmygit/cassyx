#!/usr/bin/env bash
# =============================================================================
# No-drift guard (plan §11.1: "`make verify` runs the per-PR subset locally and
# is identical to what CI runs").
#
# Compares:
#   A) the set of make targets invoked by the `verify` recipe in the Makefile
#   B) the set of `make <target>` steps run by .github/workflows/ci.yml
# and fails if they differ. Runs as its own (fast) CI job.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAKEFILE="$ROOT/Makefile"
CI="$ROOT/.github/workflows/ci.yml"

# A) targets invoked inside the `verify:` recipe
verify_targets="$(awk '
  /^verify:/                 { inr = 1; next }
  inr && /^[^\t ]/           { inr = 0 }
  inr && /\$\(MAKE\)/        { print $NF }
' "$MAKEFILE" | sort -u)"

# B) `make <target>` invocations in the per-PR workflow
ci_targets="$(grep -oE 'run:[[:space:]]+make [a-z0-9-]+' "$CI" | awk '{print $NF}' | sort -u)"

# Housekeeping targets a job may legitimately call for cleanup/diagnostics only.
ignore='^(down|logs|ps|config|clean)$'
ci_targets="$(echo "$ci_targets" | grep -Ev "$ignore" || true)"

echo "verify -> $(echo "$verify_targets" | tr '\n' ' ')"
echo "ci     -> $(echo "$ci_targets"     | tr '\n' ' ')"

missing_in_ci="$(comm -23 <(echo "$verify_targets") <(echo "$ci_targets") || true)"
missing_in_verify="$(comm -13 <(echo "$verify_targets") <(echo "$ci_targets") || true)"

status=0
if [ -n "$missing_in_ci" ]; then
  printf '\033[31m✗ run by `make verify` but not by CI:\033[0m %s\n' "$(echo "$missing_in_ci" | tr '\n' ' ')" >&2
  status=1
fi
if [ -n "$missing_in_verify" ]; then
  printf '\033[31m✗ run by CI but not by `make verify`:\033[0m %s\n' "$(echo "$missing_in_verify" | tr '\n' ' ')" >&2
  printf '  Add it to the verify recipe — local and CI must not drift.\n' >&2
  status=1
fi
[ "$status" = 0 ] && printf '\033[32m✓ make verify and CI run the identical job set\033[0m\n'
exit "$status"
