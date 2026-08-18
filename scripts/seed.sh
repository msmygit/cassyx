#!/usr/bin/env bash
# =============================================================================
# cassyx seed loader (plan §2.2)
#
# Runs inside the Cassandra image (cqlsh + python3 already present), so it needs
# nothing installed on the host. Applies scripts/seed.cql, then generates the
# parts that cannot be hand-written:
#   * wide_grid          SEED_WIDE_COLUMNS-column table for the grid benchmark
#   * doc_embeddings     SEED_VECTOR_DIM-dimensional vectors behind an SAI index
#   * sensor_readings    the deliberately skewed 'HOT' partition (§5.2)
#   * app_events         evenly distributed rows
#
# Every volume is scaled by SEED_SCALE, so `make bench` re-seeds at benchmark
# size with the same script:  make seed SEED_SCALE=50
# =============================================================================
set -euo pipefail

HOST="${CASSANDRA_HOST:-cassandra}"
PORT="${CASSANDRA_PORT:-9042}"
USER="${CASSANDRA_USER:-cassandra}"
PASS="${CASSANDRA_PASSWORD:-cassandra}"
KS="${SEED_KEYSPACE:-cassyx_demo}"

SCALE="${SEED_SCALE:-1}"
EVENTS=$(( ${SEED_EVENTS:-2000} * SCALE ))
SKEW_ROWS=$(( ${SEED_SKEW_ROWS:-20000} * SCALE ))
VECTOR_ROWS=$(( ${SEED_VECTOR_ROWS:-200} * SCALE ))
VECTOR_DIM="${SEED_VECTOR_DIM:-1536}"
WIDE_COLUMNS="${SEED_WIDE_COLUMNS:-1000}"
WIDE_ROWS=$(( ${SEED_WIDE_ROWS:-200} * SCALE ))

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$(mktemp -d)"
trap 'rm -rf "$GEN_DIR"' EXIT

cql() { cqlsh "$HOST" "$PORT" -u "$USER" -p "$PASS" "$@"; }

log() { printf '\033[36m[seed]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[seed] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Wait for a REAL readiness signal — the same check the compose healthcheck
#    uses. Never a sleep.
# ---------------------------------------------------------------------------
log "waiting for Cassandra at ${HOST}:${PORT} (cqlsh -e 'describe keyspaces')"
for i in $(seq 1 90); do
  if cql -e 'describe keyspaces' >/dev/null 2>&1; then
    log "Cassandra is up (after ${i} attempt(s))"
    break
  fi
  [ "$i" -eq 90 ] && die "Cassandra did not become ready in time at ${HOST}:${PORT}"
  sleep 2
done

VERSION="$(cql -e "SELECT release_version FROM system.local" 2>/dev/null | sed -n '4p' | tr -d ' ')"
log "Cassandra release_version = ${VERSION:-unknown}"
case "${VERSION:-0}" in
  5.*|6.*) : ;;
  *) log "WARNING: vector<float,N> and SAI require Cassandra 5.x — detected '${VERSION}'. Seeding will likely fail." ;;
esac

# ---------------------------------------------------------------------------
# 2. Base schema + representative rows.
# ---------------------------------------------------------------------------
[ -f "${SCRIPT_DIR}/seed.cql" ] || die "missing ${SCRIPT_DIR}/seed.cql"
log "applying seed.cql (schema + representative rows)"
cql -f "${SCRIPT_DIR}/seed.cql"

# ---------------------------------------------------------------------------
# 3. Generated CQL: wide table, vectors, skew, events.
# ---------------------------------------------------------------------------
log "generating data (scale=${SCALE}: ${EVENTS} events, ${SKEW_ROWS} skew rows, ${VECTOR_ROWS} vectors@${VECTOR_DIM}d, ${WIDE_ROWS}x${WIDE_COLUMNS} wide)"

KS="$KS" VECTOR_DIM="$VECTOR_DIM" VECTOR_ROWS="$VECTOR_ROWS" \
WIDE_COLUMNS="$WIDE_COLUMNS" WIDE_ROWS="$WIDE_ROWS" \
EVENTS="$EVENTS" SKEW_ROWS="$SKEW_ROWS" GEN_DIR="$GEN_DIR" \
python3 - <<'PY'
import os, random, uuid, datetime

ks          = os.environ["KS"]
dim         = int(os.environ["VECTOR_DIM"])
vec_rows    = int(os.environ["VECTOR_ROWS"])
wide_cols   = int(os.environ["WIDE_COLUMNS"])
wide_rows   = int(os.environ["WIDE_ROWS"])
events      = int(os.environ["EVENTS"])
skew_rows   = int(os.environ["SKEW_ROWS"])
gen         = os.environ["GEN_DIR"]

random.seed(20260817)  # deterministic seed data

# --- wide ~1000-column table (grid benchmark, §11.2) ------------------------
with open(f"{gen}/10-wide.cql", "w") as f:
    cols = [f"c{i:04d}" for i in range(1, wide_cols + 1)]
    # A deliberate type mix so the grid renderer is exercised across the width.
    def coltype(i):
        return ["text", "int", "double", "boolean", "timestamp"][i % 5]
    f.write(f"USE {ks};\n")
    f.write("CREATE TABLE IF NOT EXISTS wide_grid (\n  id uuid PRIMARY KEY")
    for i, c in enumerate(cols):
        f.write(f",\n  {c} {coltype(i)}")
    f.write("\n) WITH comment = 'Wide-table fixture for the grid first-paint benchmark "
            f"({wide_cols} columns).';\n")
    # Re-seeding must replace, not accumulate.
    f.write("TRUNCATE wide_grid;\n")
    base = datetime.datetime(2026, 1, 1)
    for r in range(wide_rows):
        vals = []
        for i in range(wide_cols):
            t = coltype(i)
            if t == "text":       vals.append(f"'r{r}c{i}-{random.randint(0,10**6)}'")
            elif t == "int":      vals.append(str(random.randint(-10**6, 10**6)))
            elif t == "double":   vals.append(f"{random.random()*1000:.6f}")
            elif t == "boolean":  vals.append("true" if random.random() < .5 else "false")
            else:                 vals.append("'" + (base + datetime.timedelta(seconds=r*60+i)).strftime("%Y-%m-%dT%H:%M:%SZ") + "'")
        f.write(f"INSERT INTO wide_grid (id, {', '.join(cols)}) VALUES "
                f"({uuid.uuid4()}, {', '.join(vals)});\n")

# --- vectors + SAI (§6) -----------------------------------------------------
with open(f"{gen}/20-vectors.cql", "w") as f:
    f.write(f"USE {ks};\n")
    if dim != 1536:
        # seed.cql pins 1536; honour an override by recreating the table+index.
        f.write("DROP TABLE IF EXISTS doc_embeddings;\n")
        f.write(f"""CREATE TABLE doc_embeddings (
  doc_id uuid, chunk_no int, title text, body text, category text,
  embedding vector<float, {dim}>, meta map<text,text>, updated_at timestamp,
  PRIMARY KEY ((doc_id), chunk_no)) WITH CLUSTERING ORDER BY (chunk_no ASC);
CREATE INDEX doc_embeddings_ann ON doc_embeddings (embedding)
  USING 'StorageAttachedIndex' WITH OPTIONS = {{'similarity_function': 'cosine'}};
CREATE INDEX doc_embeddings_category_sai ON doc_embeddings (category)
  USING 'StorageAttachedIndex';\n""")
    f.write("TRUNCATE doc_embeddings;\n")
    cats = ["handbook", "runbook", "api-reference", "blog", "changelog"]
    for r in range(vec_rows):
        # Cluster vectors around a few centroids so ANN returns meaningful
        # neighbours instead of uniform noise.
        centroid = r % 8
        v = [f"{(random.gauss(centroid / 8.0, 0.15)):.5f}" for _ in range(dim)]
        did = uuid.uuid4()
        for chunk in range(2):
            f.write(
                "INSERT INTO doc_embeddings (doc_id, chunk_no, title, body, category, "
                "embedding, meta, updated_at) VALUES "
                f"({did}, {chunk}, 'Document {r} chunk {chunk}', "
                f"'Seeded body text for document {r}, chunk {chunk}.', "
                f"'{cats[r % len(cats)]}', [{', '.join(v)}], "
                f"{{'centroid': '{centroid}'}}, '2026-01-0{(r % 9) + 1}T00:00:00Z');\n")

# --- deliberately skewed partition (§5.2) -----------------------------------
with open(f"{gen}/30-skew.cql", "w") as f:
    f.write(f"USE {ks};\n")
    f.write("TRUNCATE sensor_readings;\n")
    ts = datetime.datetime(2026, 1, 1)
    # ~99% of rows land in ONE partition. Equal-token splits over this table
    # are wildly unequal in wall time — the work-stealing test case.
    for i in range(skew_rows):
        if i % 200 == 0:
            f.write("BEGIN UNLOGGED BATCH\n")
        f.write("  INSERT INTO sensor_readings (sensor_id, reading_ts, value, quality, meta) "
                f"VALUES ('HOT', '{(ts + datetime.timedelta(seconds=i)).strftime('%Y-%m-%dT%H:%M:%SZ')}', "
                f"{random.random()*100:.4f}, 'good', {{'i': '{i}'}});\n")
        if i % 200 == 199 or i == skew_rows - 1:
            f.write("APPLY BATCH;\n")
    # the cold tail: many partitions, a few rows each
    cold = max(50, skew_rows // 100)
    for p in range(cold):
        f.write("INSERT INTO sensor_readings (sensor_id, reading_ts, value, quality, meta) "
                f"VALUES ('cold-{p:06d}', '{(ts + datetime.timedelta(seconds=p)).strftime('%Y-%m-%dT%H:%M:%SZ')}', "
                f"{random.random()*100:.4f}, 'good', {{'cold': 'true'}});\n")

# --- evenly distributed events ---------------------------------------------
with open(f"{gen}/40-events.cql", "w") as f:
    f.write(f"USE {ks};\n")
    f.write("TRUNCATE app_events;\n")
    types = ["login", "logout", "query", "export", "error"]
    tenants = ["acme", "globex", "initech", "umbrella"]
    buckets = max(1, events // 500)
    for t in tenants:
        for b in range(buckets):
            f.write("INSERT INTO app_events (tenant_id, bucket, bucket_desc) VALUES "
                    f"('{t}', {b}, 'bucket {b} for {t}');\n")
    for i in range(events):
        t = tenants[i % len(tenants)]
        b = i % buckets
        f.write("INSERT INTO app_events (tenant_id, bucket, event_id, event_type, payload, size_bytes, raw) "
                f"VALUES ('{t}', {b}, now(), '{types[i % len(types)]}', "
                f"{{'seq': '{i}', 'ua': 'seed'}}, {random.randint(64, 65536)}, "
                f"textAsBlob('payload-{i}'));\n")
PY

# ---------------------------------------------------------------------------
# 4. Apply generated files in order.
# ---------------------------------------------------------------------------
for f in "$GEN_DIR"/*.cql; do
  log "applying $(basename "$f") ($(wc -l < "$f" | tr -d ' ') statements-ish)"
  cql -f "$f"
done

# ---------------------------------------------------------------------------
# 5. Summary — proves the hard paths actually landed.
# ---------------------------------------------------------------------------
log "seed complete. summary:"
# NOTE: cqlsh -e must not be given a trailing newline after the final ';' —
# it parses the remainder as an empty statement and fails.
SUMMARY=""
for q in \
  "SELECT count(*) AS users FROM ${KS}.users;" \
  "SELECT count(*) AS app_events FROM ${KS}.app_events;" \
  "SELECT count(*) AS sensor_readings FROM ${KS}.sensor_readings;" \
  "SELECT count(*) AS hot_partition FROM ${KS}.sensor_readings WHERE sensor_id = 'HOT';" \
  "SELECT count(*) AS doc_embeddings FROM ${KS}.doc_embeddings;" \
  "SELECT count(*) AS wide_grid FROM ${KS}.wide_grid;" \
  "SELECT count(*) AS page_counters FROM ${KS}.page_counters;"
do
  SUMMARY="${SUMMARY}${q}"
done
printf '%s' "$SUMMARY" > "$GEN_DIR/summary.cql"
cql -f "$GEN_DIR/summary.cql"

log "verifying ANN path (SAI on vector<float,${VECTOR_DIM}>)"
# Written to a file rather than passed with -e: the query carries a literal
# ${VECTOR_DIM}-dimensional vector twice, which is well past a comfortable
# argv/heredoc size.
ANN_VEC="[$(python3 -c "print(', '.join(['0.1']*${VECTOR_DIM}))")]"
{
  printf 'SELECT doc_id, chunk_no, title, similarity_cosine(embedding, %s) AS score ' "$ANN_VEC"
  printf 'FROM %s.doc_embeddings ORDER BY embedding ANN OF %s LIMIT 3;\n' "$KS" "$ANN_VEC"
} > "$GEN_DIR/ann-check.cql"
cql -f "$GEN_DIR/ann-check.cql"

log "done."
