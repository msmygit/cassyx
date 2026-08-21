#!/usr/bin/env bash
# =============================================================================
# Preflight checks. Every failure here must produce an ACTIONABLE message —
# backend/ and frontend/ are built by other workstreams and may be absent or
# incomplete, and a missing directory must never surface as a docker stack
# trace.
#
# Usage: scripts/preflight.sh [component ...]
#   docker | env | backend | frontend | e2e
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RED='\033[31m'; YEL='\033[33m'; GRN='\033[32m'; DIM='\033[2m'; OFF='\033[0m'

fail() { printf "${RED}✗ %s${OFF}\n" "$1" >&2; shift; for l in "$@"; do printf "  %s\n" "$l" >&2; done; exit 1; }
ok()   { printf "${GRN}✓${OFF} ${DIM}%s${OFF}\n" "$1"; }
warn() { printf "${YEL}!${OFF} %s\n" "$1"; }

check_docker() {
  command -v docker >/dev/null 2>&1 || fail \
    "Docker is not installed (or not on PATH)." \
    "cassyx builds everything inside containers — Docker is the only hard requirement." \
    "Install Docker Desktop: https://docs.docker.com/get-docker/"
  docker compose version >/dev/null 2>&1 || fail \
    "'docker compose' (v2) is not available." \
    "You may have the legacy 'docker-compose' v1 binary. Upgrade to Docker Compose v2:" \
    "https://docs.docker.com/compose/install/"
  docker info >/dev/null 2>&1 || fail \
    "The Docker daemon is not reachable." \
    "Start Docker Desktop (or 'sudo systemctl start docker' on Linux) and retry." \
    "If you use a non-default context: docker context use <name>"
  ok "docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '?') + compose v2"
}

check_env() {
  if [ ! -f "$ROOT/.env" ]; then
    [ -f "$ROOT/.env.example" ] || fail \
      "Neither .env nor .env.example exists." \
      "Restore .env.example from git: git checkout -- .env.example"
    cp "$ROOT/.env.example" "$ROOT/.env"
    warn "created .env from .env.example (first run) — edit it if ports 8080/9042 are taken"
  fi
  ok ".env present"
}

check_module() { # $1=dir $2=required file $3=owning workstream
  local dir="$ROOT/$1"
  if [ ! -d "$dir" ]; then
    fail "'$1/' does not exist yet." \
      "This target needs the $3 workstream to have landed." \
      "" \
      "What you CAN run right now:" \
      "  make db          start Cassandra 5.x on its own" \
      "  make seed        apply scripts/seed.cql + generated demo data" \
      "  make cql         open a cqlsh shell against it" \
      "  make config      validate docker-compose.yml"
  fi
  if [ ! -f "$dir/$2" ]; then
    fail "'$1/' exists but '$1/$2' is missing." \
      "The $3 workstream owns this file. Expected contract:" \
      "$(module_contract "$1")" \
      "" \
      "Run 'make show-contracts' to print the full build contract."
  fi
  ok "$1/$2"
}

module_contract() {
  case "$1" in
    backend)  echo "  multi-stage Dockerfile (maven build -> JRE 21), listens :8080, GET /api/health" ;;
    frontend) echo "  multi-stage Dockerfile (node build -> nginx), listens :8080, /healthz, /api -> cassyx-api:8080" ;;
    e2e)      echo "  package.json with a 'test' script and @playwright/test installed" ;;
    *)        echo "  see docs/maintainers.md § Build contracts" ;;
  esac
}

for component in "${@:-docker env}"; do
  case "$component" in
    docker)       check_docker ;;
    env)          check_env ;;
    backend)      check_module backend  Dockerfile   "backend (Phase 0 §10.2)" ;;
    backend-src)  check_module backend  pom.xml      "backend (Phase 0 §10.2)" ;;
    frontend-src) check_module frontend package.json "frontend (Phase 0 §10.3)" ;;
    frontend)     check_module frontend Dockerfile   "frontend (Phase 0 §10.3)" ;;
    e2e)          check_module e2e      package.json "DX (this workstream)" ;;
    *) fail "unknown preflight component: $component" ;;
  esac
done
