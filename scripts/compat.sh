#!/usr/bin/env bash
# =============================================================================
# Compatibility smoke (plan §7.1 / §11.3).
#
# Runs one throwaway node per target image and probes what the server ACTUALLY
# supports, then compares against the expected §7.1 capability matrix. The point
# is to assert the matrix stays true, so the UI gates features instead of
# showing them broken.
#
# This runs with no backend present — it is pure CQL probing — and additionally
# runs the backend's capability integration tests when backend/ exists.
#
# Usage:
#   scripts/compat.sh                  # the full nightly matrix
#   scripts/compat.sh cassandra:4.1    # one target
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# image|label|expect_sai|expect_vector|expect_mv|expect_udf
MATRIX=(
  "cassandra:3.11|C* 3.11|no|no|yes|yes"
  "cassandra:4.1|C* 4.1|no|no|yes|yes"
  "cassandra:5.0|C* 5.0|yes|yes|yes|yes"
  "scylladb/scylla:6.2|ScyllaDB 6.2|no|no|yes|yes"
  # DSE 6.8 needs a licensed image; enable when credentials are configured:
  # "datastax/dse-server:6.8.44|DSE 6.8|yes|no|yes|yes"
)
[ $# -gt 0 ] && MATRIX=("$1|$1|?|?|?|?")

log()  { printf '\033[36m[compat]\033[0m %s\n' "$*"; }
pass() { printf '  \033[32m✓\033[0m %-22s expected=%s actual=%s\n' "$1" "$2" "$3"; }
fail() { printf '  \033[31m✗\033[0m %-22s expected=%s actual=%s\n' "$1" "$2" "$3"; FAILED=1; }
check(){ [ "$2" = "?" ] && { printf '  \033[2m·\033[0m %-22s actual=%s (no expectation)\n' "$1" "$3"; return; }
         [ "$2" = "$3" ] && pass "$1" "$2" "$3" || fail "$1" "$2" "$3"; }

FAILED=0
RESULTS="$ROOT/bench/compat-results.txt"
mkdir -p "$ROOT/bench"
: > "$RESULTS"

for entry in "${MATRIX[@]}"; do
  IFS='|' read -r image label exp_sai exp_vec exp_mv exp_udf <<< "$entry"
  name="cassyx-compat-$(echo "$image" | tr ':/.' '---')"
  log "=== $label ($image) ==="
  docker rm -f "$name" >/dev/null 2>&1 || true
  if ! docker run -d --name "$name" -e MAX_HEAP_SIZE=1G -e HEAP_NEWSIZE=256M \
        -e CASSANDRA_CLUSTER_NAME=compat "$image" >/dev/null 2>&1; then
    log "could not start $image — skipping"; continue
  fi

  ready=0
  for _ in $(seq 1 90); do
    if docker exec "$name" cqlsh -e 'describe keyspaces' >/dev/null 2>&1; then ready=1; break; fi
    sleep 3
  done
  if [ "$ready" != 1 ]; then log "$label never became ready — skipping"; docker rm -f "$name" >/dev/null; FAILED=1; continue; fi

  ver="$(docker exec "$name" cqlsh -e "SELECT release_version FROM system.local" 2>/dev/null | sed -n '4p' | tr -d ' ')"
  log "release_version=$ver"

  q() { docker exec "$name" cqlsh -e "$1" >/dev/null 2>&1 && echo yes || echo no; }

  q "CREATE KEYSPACE IF NOT EXISTS compat WITH replication={'class':'SimpleStrategy','replication_factor':1}" >/dev/null
  q "CREATE TABLE IF NOT EXISTS compat.t (id uuid PRIMARY KEY, s text, n int)" >/dev/null

  act_sai="$(q "CREATE CUSTOM INDEX IF NOT EXISTS compat_s_sai ON compat.t (s) USING 'StorageAttachedIndex'")"
  act_vec="$(q "CREATE TABLE IF NOT EXISTS compat.v (id uuid PRIMARY KEY, e vector<float, 3>)")"
  act_mv="$(q  "CREATE MATERIALIZED VIEW IF NOT EXISTS compat.t_by_n AS SELECT * FROM compat.t WHERE id IS NOT NULL AND n IS NOT NULL PRIMARY KEY (n, id)")"
  act_udf="$(q "CREATE OR REPLACE FUNCTION compat.twice(x int) CALLED ON NULL INPUT RETURNS int LANGUAGE java AS 'return x==null?null:x*2;'")"
  act_tok="$(q "SELECT * FROM compat.t WHERE token(id) > -9223372036854775808 LIMIT 1")"

  check "SAI"                "$exp_sai" "$act_sai"
  check "vector<float,N>"    "$exp_vec" "$act_vec"
  check "materialized views" "$exp_mv"  "$act_mv"
  check "UDF/UDA"            "$exp_udf" "$act_udf"
  check "token() range scan" "?"        "$act_tok"

  printf '%s\t%s\tsai=%s\tvector=%s\tmv=%s\tudf=%s\ttoken=%s\n' \
    "$label" "$ver" "$act_sai" "$act_vec" "$act_mv" "$act_udf" "$act_tok" >> "$RESULTS"

  docker rm -f "$name" >/dev/null 2>&1 || true
done

log "results written to $RESULTS"
cat "$RESULTS"

# Backend capability-probe integration tests, when that workstream has landed.
#
# The check below is not ceremony. `mvn verify -Dgroups=compat` against a tag that nothing carries
# runs ZERO tests and then dies on cassyx-core's JaCoCo gate ("Coverage checks have not been met"),
# because zero tests produce zero coverage. That failure names JaCoCo and says nothing about the
# real problem, so the nightly has been reporting a coverage error every night for a situation that
# is actually "the §7.1 probe tests were never written".
#
# Fail loudly and accurately instead. Anything else is worse: making this green would mean a
# compatibility matrix that reports success while testing nothing at all, across five targets.
if [ ! -f "$ROOT/backend/pom.xml" ]; then
  log "backend/ absent — ClusterCapabilities probe tests skipped"
elif ! grep -rq '@Tag("compat")' "$ROOT/backend" --include='*.java' 2>/dev/null; then
  log "NO capability-probe tests exist yet (nothing carries @Tag(\"compat\"))."
  log "  The §7.1 capability matrix is unverified: this job cannot pass until those tests exist."
  log "  See docs/plan.md §7.1 and §11.3. Raw CQL probe results above are still valid."
  FAILED=1
else
  log "running backend capability tests (ClusterCapabilities probe, §7.1)"
  # Coverage is deliberately skipped: this run exists to probe a cluster, not to measure coverage,
  # and a tag-filtered subset can never satisfy a whole-module line gate.
  docker compose -f "$ROOT/docker-compose.yml" --profile tools run --rm --no-deps maven \
    -B -ntp verify -Dgroups=compat -Dcassyx.coverage.skip=true || FAILED=1
fi

exit "$FAILED"
