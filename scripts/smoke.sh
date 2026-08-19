#!/usr/bin/env bash
# =============================================================================
# Fast integration smoke.
#
# Why this exists as its own gate rather than being left to `make e2e`:
# Phase 0 shipped two defects that every unit test passed and that broke the
# product on first boot —
#
#   1. `AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE` — H2 2.3 rejects that
#      combination, so Flyway could not open a connection and the app died
#      before serving anything.
#   2. The Ed25519 verifier was built eagerly from the shipped placeholder
#      public key, threw in the constructor, and took the whole context down.
#
# Both were invisible to `mvn verify` and instant on `make up`. Playwright would
# eventually have caught them, but as an opaque browser timeout at the end of a
# long run. This asserts the same thing in seconds, with an error that names the
# actual problem.
#
# Assumes `make up` has already run (the make target sequences that).
# =============================================================================
set -euo pipefail

API="${CASSYX_SMOKE_API:-http://localhost:${CASSYX_API_PORT:-8081}}"
WEB="${CASSYX_SMOKE_WEB:-http://localhost:${CASSYX_WEB_PORT:-8080}}"

RED=$'\033[31m'; GREEN=$'\033[32m'; CYAN=$'\033[36m'; OFF=$'\033[0m'
fails=0

say()  { printf "%s▸%s %s\n" "$CYAN" "$OFF" "$1"; }
pass() { printf "%s✓%s %s\n" "$GREEN" "$OFF" "$1"; }
fail() { printf "%s✗%s %s\n" "$RED" "$OFF" "$1"; fails=$((fails + 1)); }

# check_json <label> <url> <jq-ish field> — asserts HTTP 200 and a non-empty field.
check_field() {
  local label="$1" url="$2" field="$3" body code
  code="$(curl -s -o /tmp/smoke-body -w '%{http_code}' "$url" || echo 000)"
  body="$(cat /tmp/smoke-body 2>/dev/null || true)"
  if [ "$code" != "200" ]; then
    fail "$label — expected HTTP 200, got $code"
    [ -n "$body" ] && printf "    body: %s\n" "$(printf '%s' "$body" | head -c 300)"
    return
  fi
  if ! printf '%s' "$body" | grep -q "\"$field\""; then
    fail "$label — 200 but response is missing required field '$field'"
    printf "    body: %s\n" "$(printf '%s' "$body" | head -c 300)"
    return
  fi
  pass "$label"
}

say "backend: GET /api/health (contract requires status AND version)"
check_field "/api/health returns status"  "$API/api/health" "status"
check_field "/api/health returns version" "$API/api/health" "version"

# Release-only, opt-in: assert the running image reports the version the tag
# promises. The tag/pom guard (scripts/release-version.sh) proves the SOURCE
# agreed before the build; this proves the BUILT ARTEFACT agrees after it, which
# is the claim that actually reaches customers. They are different failures: a
# stale build cache or a botched jar copy passes the first and fails this one.
# Unset in normal `make smoke` runs, so dev behaviour is unchanged.
if [ -n "${CASSYX_SMOKE_EXPECT_VERSION:-}" ]; then
  say "release: /api/health reports version ${CASSYX_SMOKE_EXPECT_VERSION}"
  reported="$(curl -fsS "$API/api/health" 2>/dev/null \
    | sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
  if [ "$reported" = "$CASSYX_SMOKE_EXPECT_VERSION" ]; then
    pass "reported version is $reported"
  else
    fail "version mismatch - tag says '$CASSYX_SMOKE_EXPECT_VERSION', image reports '${reported:-<none>}'"
    printf "    Nothing in the build injects the reactor version into the running app:\n"
    printf "    HealthController and LicenseController both read \${cassyx.version:0.1.0-SNAPSHOT},\n"
    printf "    that property is defined nowhere, and the jar manifest carries no\n"
    printf "    Implementation-Version, so the default always wins. §9.5 scope is derived\n"
    printf "    from this string, so it currently parses to major 0 (treated as unscoped).\n"
    printf "    Fix in backend/pom.xml: enable resource filtering and set\n"
    printf "    'cassyx.version: @project.version@' in application.yml, or bind the\n"
    printf "    spring-boot-maven-plugin build-info goal.\n"
  fi
fi

# The UI calls this before rendering anything at all. A 404 here is
# indistinguishable to the user from the entire product being down.
say "backend: GET /api/license (first call the UI makes; ungated per §9.1)"
check_field "/api/license returns state" "$API/api/license" "state"

say "frontend: SPA is served and proxies /api"
code="$(curl -s -o /dev/null -w '%{http_code}' "$WEB/" || echo 000)"
[ "$code" = "200" ] && pass "SPA responds 200" || fail "SPA — expected 200, got $code"

if curl -s "$WEB/" | grep -qi '<title>[^<]*cassyx'; then
  pass "document title contains cassyx"
else
  fail "document title does not contain cassyx"
fi

# Proxying matters independently of the backend being up: nginx must route /api
# and must NOT buffer (SSE job progress depends on it).
check_field "proxied /api/health via the web port" "$WEB/api/health" "status"

printf "\n"
if [ "$fails" -gt 0 ]; then
  printf "%s✗ smoke failed: %d check(s). The stack starts but does not serve correctly.%s\n\n" \
    "$RED" "$fails" "$OFF"
  printf "Container status:\n"
  docker ps -a --filter "name=cassyx" --format '  {{.Names}}: {{.Status}}' || true
  printf "\nLast backend logs:\n"
  docker logs --tail 40 cassyx-backend 2>&1 | sed 's/^/  /' || true
  exit 1
fi

printf "%s✓ smoke passed — the stack boots and serves.%s\n\n" "$GREEN" "$OFF"
