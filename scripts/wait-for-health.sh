#!/usr/bin/env bash
# =============================================================================
# Wait until a compose service reports healthy. Polls the container's real
# healthcheck state — never a sleep. Prints the last log lines on failure so
# the reason is visible without a second command.
#
# Usage: scripts/wait-for-health.sh <service> [timeout-seconds]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE="${1:?usage: wait-for-health.sh <service> [timeout]}"
TIMEOUT="${2:-300}"
# Defaults to the dev stack. The release pipeline waits on the SAME services in
# docker-compose.release.yml, so the file is a parameter rather than a second
# copy of this script: CASSYX_COMPOSE_FILE=docker-compose.release.yml.
COMPOSE=(docker compose -f "${CASSYX_COMPOSE_FILE:-$ROOT/docker-compose.yml}")

printf '\033[36m[wait]\033[0m %s: waiting up to %ss for health...\n' "$SERVICE" "$TIMEOUT"

deadline=$(( $(date +%s) + TIMEOUT ))
last=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  cid="$("${COMPOSE[@]}" ps -q "$SERVICE" 2>/dev/null || true)"
  if [ -n "$cid" ]; then
    state="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo unknown)"
    health="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none)"
    if [ "$health" = "healthy" ]; then
      printf '\033[32m[wait]\033[0m %s is healthy\n' "$SERVICE"; exit 0
    fi
    if [ "$health" = "none" ] && [ "$state" = "running" ]; then
      printf '\033[33m[wait]\033[0m %s has no healthcheck; treating "running" as ready\n' "$SERVICE"; exit 0
    fi
    if [ "$state" = "exited" ] || [ "$state" = "dead" ]; then
      printf '\033[31m[wait]\033[0m %s exited. Last logs:\n' "$SERVICE" >&2
      "${COMPOSE[@]}" logs --tail=60 "$SERVICE" >&2 || true
      exit 1
    fi
    if [ "$health:$state" != "$last" ]; then
      printf '  %s: state=%s health=%s\n' "$SERVICE" "$state" "$health"
      last="$health:$state"
    fi
  fi
  sleep 3
done

printf '\033[31m[wait]\033[0m timed out after %ss waiting for %s. Last logs:\n' "$TIMEOUT" "$SERVICE" >&2
"${COMPOSE[@]}" logs --tail=80 "$SERVICE" >&2 || true
exit 1
