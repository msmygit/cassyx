#!/usr/bin/env bash
# =============================================================================
# cassyx benchmark harness (plan §11.2)
#
# Records — never assumes — the three numbers the plan calls out:
#   1. unload N rows -> CSV, native token-range engine (§5.2) vs embedded
#      DSBulk (§5.3). Target: within 1.5x of the DSBulk CLI. The winner at each
#      scale decides default engine routing (§12.3).
#   2. grid first paint on the 1000-column wide table. Target < 1s.
#   3. peak RSS during a large unload — must stream, never buffer.
#
# Results are APPENDED to bench/trend.csv (committed) so regressions show up as
# a diff. Nightly CI runs this and commits the new row.
#
# Usage: scripts/bench.sh [--rows N] [--scale N] [--no-seed]
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TREND="$ROOT/bench/trend.csv"
ROWS="${BENCH_ROWS:-1000000}"
SCALE="${BENCH_SEED_SCALE:-50}"
DO_SEED=1

while [ $# -gt 0 ]; do
  case "$1" in
    --rows)    ROWS="$2"; shift 2 ;;
    --scale)   SCALE="$2"; shift 2 ;;
    --no-seed) DO_SEED=0; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[36m[bench]\033[0m %s\n' "$*"; }
skip() { printf '\033[33m[bench] SKIP\033[0m %s\n' "$*"; }

mkdir -p "$ROOT/bench"
if [ ! -f "$TREND" ]; then
  echo "timestamp,git_sha,benchmark,variant,rows,metric,value,unit,notes" > "$TREND"
fi

SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo nogit)"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

record() { # benchmark variant rows metric value unit notes
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$TS" "$SHA" "$1" "$2" "$3" "$4" "$5" "$6" "${7//,/;}" >> "$TREND"
  log "recorded: $1/$2 $4=$5$6"
}

# --- 1. seed at benchmark volume -------------------------------------------
if [ "$DO_SEED" = 1 ]; then
  log "seeding at SEED_SCALE=$SCALE (this is intentionally large)"
  SEED_SCALE="$SCALE" make -C "$ROOT" seed || { skip "seed failed — benchmarks need a seeded cluster"; exit 1; }
fi

# --- 2. unload benchmarks (need the backend workstream) ---------------------
# Contract: backend exposes a bench profile that runs both engines headlessly:
#   mvn -q -pl cassyx-bulk -am -Pbench test -Dbench.rows=N -Dbench.out=/out
# emitting one "BENCH <variant> <metric> <value> <unit>" line per result.
if [ -f "$ROOT/backend/pom.xml" ]; then
  log "running unload benchmarks (rows=$ROWS)"
  out="$(docker compose -f "$ROOT/docker-compose.yml" --profile tools run --rm \
        -e BENCH_ROWS="$ROWS" maven \
        -q -pl cassyx-bulk -am -Pbench test -Dbench.rows="$ROWS" 2>&1)"
  echo "$out" | tail -40
  if echo "$out" | grep -q '^BENCH '; then
    echo "$out" | grep '^BENCH ' | while read -r _ variant metric value unit; do
      record unload "$variant" "$ROWS" "$metric" "$value" "$unit" "scripted"
    done
  else
    skip "backend produced no 'BENCH ' lines — the -Pbench profile is not wired yet (workstream D/E)"
  fi
else
  skip "backend/ absent — unload benchmarks (native §5.2 vs DSBulk §5.3) not run"
fi

# --- 3. grid first paint (needs frontend + Playwright) ----------------------
if [ -f "$ROOT/frontend/Dockerfile" ] && [ -f "$ROOT/e2e/package.json" ]; then
  log "measuring grid first paint on the wide table"
  if docker compose -f "$ROOT/docker-compose.yml" --profile e2e run --rm \
       -e E2E_GREP='@bench' e2e \
       bash -lc "npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund >/dev/null 2>&1; npx playwright test --grep @bench --reporter=line" \
       > /tmp/cassyx-bench-grid.log 2>&1; then
    ms="$(grep -oE 'GRID_FIRST_PAINT_MS=[0-9]+' /tmp/cassyx-bench-grid.log | tail -1 | cut -d= -f2)"
    [ -n "${ms:-}" ] && record grid_first_paint wide_1000col 200 first_paint "$ms" ms "target<1000ms" \
                     || skip "grid bench ran but emitted no GRID_FIRST_PAINT_MS"
  else
    skip "grid bench failed (frontend not ready?) — see /tmp/cassyx-bench-grid.log"
  fi
else
  skip "frontend/ or e2e/ absent — grid first-paint benchmark not run"
fi

# --- 4. memory ceiling ------------------------------------------------------
if docker compose -f "$ROOT/docker-compose.yml" ps backend --format '{{.Name}}' 2>/dev/null | grep -q .; then
  peak="$(docker stats --no-stream --format '{{.MemUsage}}' \
          "$(docker compose -f "$ROOT/docker-compose.yml" ps -q backend)" 2>/dev/null | cut -d'/' -f1 | tr -d ' ')"
  [ -n "${peak:-}" ] && record memory backend "$ROWS" rss "${peak%%[A-Za-z]*}" "${peak##*[0-9.]}" "sampled post-unload"
else
  skip "backend container not running — memory ceiling not sampled"
fi

log "trend file: $TREND"
tail -5 "$TREND"
