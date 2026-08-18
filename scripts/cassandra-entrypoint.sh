#!/usr/bin/env bash
# =============================================================================
# Dev-cluster entrypoint wrapper.
#
# Cassandra 5.0 ships with materialized views (and a few other features cassyx
# has to be able to browse and manage) DISABLED in cassandra.yaml. The official
# image's entrypoint does not expose them as environment variables, so we patch
# the config in place before handing over to the stock entrypoint.
#
# This affects the *local dev cluster only* — it is not a product behaviour.
# Anything toggled here should also be capability-probed at connect time (§7.1),
# because a user's cluster may well have it off.
# =============================================================================
set -euo pipefail

CONF="${CASSANDRA_CONF:-/etc/cassandra}/cassandra.yaml"

# Only ever rewrite keys that ALREADY exist in this version's cassandra.yaml.
# Appending an unknown property makes Cassandra refuse to start
# ("Invalid yaml. Please remove properties [...]"), and the set of valid
# properties differs across 3.11 / 4.x / 5.x.
set_yaml() { # key value
  local key="$1" val="$2"
  [ -w "$CONF" ] || return 0
  if grep -qE "^[#[:space:]]*${key}[[:space:]]*:" "$CONF"; then
    sed -i -E "s|^[#[:space:]]*${key}[[:space:]]*:.*|${key}: ${val}|" "$CONF"
    echo "[entrypoint] ${key}: ${val}"
  fi
}

if [ -f "$CONF" ]; then
  # §4 — the schema browser must have a materialized view to show.
  set_yaml materialized_views_enabled "${CASSANDRA_MV_ENABLED:-true}"
  # (SAI needs no toggle on 5.x — it is always available.)
  # §4 — legacy 2i / SASI need to be visible in the index editor too.
  set_yaml sasi_indexes_enabled "${CASSANDRA_SASI_ENABLED:-true}"
  # §5.1 — user-defined functions (UDF/UDA editors).
  set_yaml user_defined_functions_enabled "${CASSANDRA_UDF_ENABLED:-true}"
fi

exec docker-entrypoint.sh "$@"
