#!/usr/bin/env bash
# =============================================================================
# `make contract` — the API contract gate (plan §2.3, CI job `contract` in §11.1).
#
# openapi/cassyx-api.yaml is THE coordination artifact: eight Phase 1
# workstreams implement against it and the frontend generates its typed client
# from it, so a broken spec breaks every agent at once. This is the cheapest,
# fastest signal that they are all still building against the same API, which is
# why it runs first in both `make verify` and CI.
#
# Checks, in order of cost:
#   1. openapi version is exactly 3.1.1 (§2.3: not 3.0.x, not 3.2.0)
#   2. every referenced #/components/schemas/* is actually defined
#      (reported by name — redocly reports this as N near-identical errors that
#      never name the missing schema)
#   3. redocly lint: ZERO errors AND ZERO warnings
#   4. the frontend's `npm run gen:api` succeeds and produces output
#   5. drift check: live backend responses validated against the schema
#      (wired; activates once the backend serves traffic)
#
# Runs entirely in containers — no local Node required.
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Optional arg: validate a different spec (used by the gate's own self-test).
SPEC_REL="${1:-openapi/cassyx-api.yaml}"
SPEC="$ROOT/$SPEC_REL"
NODE_IMAGE="$( . "$ROOT/.env" 2>/dev/null; echo "${NODE_IMAGE:-node:22-bookworm}" )"
EXPECTED_VERSION="3.1.1"

RED='\033[31m'; GRN='\033[32m'; YEL='\033[33m'; CYA='\033[36m'; OFF='\033[0m'
step() { printf "${CYA}▸${OFF} %s\n" "$*"; }
ok()   { printf "${GRN}✓${OFF} %s\n" "$*"; }
bad()  { printf "${RED}✗ %s${OFF}\n" "$*" >&2; STATUS=1; }
skip() { printf "${YEL}!${OFF} %s\n" "$*"; }

STATUS=0

if [ ! -f "$SPEC" ]; then
  printf "${RED}✗ %s does not exist.${OFF}\n" "$SPEC_REL" >&2
  printf "  It is Phase 0 deliverable #1 and every other workstream depends on it (§10).\n" >&2
  exit 1
fi

# --- 1. version pin ---------------------------------------------------------
step "openapi version must be exactly $EXPECTED_VERSION (§2.3)"
actual_version="$(grep -m1 -E '^openapi:' "$SPEC" | sed -E 's/^openapi:[[:space:]]*"?([0-9.]+)"?.*/\1/')"
if [ "$actual_version" = "$EXPECTED_VERSION" ]; then
  ok "openapi: $actual_version"
else
  bad "openapi: '${actual_version:-<missing>}' — must be $EXPECTED_VERSION."
  printf "  3.0.x lacks the JSON Schema alignment we rely on; 3.2.0 outruns the codegen\n" >&2
  printf "  tooling this spec is a build dependency for. See plan §2.3.\n" >&2
fi

# --- 2. every $ref resolves -------------------------------------------------
step "every \$ref must resolve to a defined component (§2.3)"
docker run --rm -v "$ROOT:/repo" -w /repo "$NODE_IMAGE" bash -lc '
  set -e
  mkdir -p /tmp/oa && cd /tmp/oa
  npm i --no-save --no-audit --no-fund --silent js-yaml@4 >/dev/null 2>&1
  cd /repo
  NODE_PATH=/tmp/oa/node_modules node -e '\''
    const fs = require("fs");
    const yaml = require("js-yaml");
    const text = fs.readFileSync("'"$SPEC_REL"'", "utf8");
    let doc;
    try { doc = yaml.load(text); }
    catch (e) { console.error("YAML is not parseable: " + e.message); process.exit(2); }

    const defined = {};
    for (const kind of ["schemas","responses","parameters","requestBodies","headers","securitySchemes"]) {
      defined[kind] = new Set(Object.keys((doc.components && doc.components[kind]) || {}));
    }

    // Collect every local $ref, with the path where it was used.
    const used = new Map();   // "kind/name" -> [locations]
    (function walk(node, path) {
      if (!node || typeof node !== "object") return;
      for (const [k, v] of Object.entries(node)) {
        const here = path + "/" + k;
        if (k === "$ref" && typeof v === "string" && v.startsWith("#/components/")) {
          const [, , kind, name] = v.split("/");
          const key = kind + "/" + name;
          if (!used.has(key)) used.set(key, []);
          used.get(key).push(path);
        } else walk(v, here);
      }
    })(doc, "");

    const missing = [];
    let total = 0;
    for (const [key, locations] of used) {
      total++;
      const [kind, name] = key.split("/");
      if (!defined[kind] || !defined[kind].has(name)) missing.push({ kind, name, locations });
    }

    const definedCount = Object.values(defined).reduce((a, s) => a + s.size, 0);
    console.log(`  referenced: ${total} distinct components · defined: ${definedCount}`);

    if (missing.length) {
      console.error(`\n  ${missing.length} referenced component(s) are NOT defined:\n`);
      for (const m of missing.slice(0, 60)) {
        console.error(`    #/components/${m.kind}/${m.name}`);
        console.error(`        first used at: ${m.locations[0]}`);
      }
      if (missing.length > 60) console.error(`    ... and ${missing.length - 60} more`);
      console.error("\n  Define them under components, or remove the reference. Writing paths");
      console.error("  without their schemas lints red AND silently breaks `gen:api` (§2.3).\n");
      process.exit(1);
    }

    // Unused schemas are not an error, but they are usually a rename left half-done.
    const usedSchemas = new Set([...used.keys()].filter(k => k.startsWith("schemas/")).map(k => k.split("/")[1]));
    const orphans = [...defined.schemas].filter(n => !usedSchemas.has(n));
    if (orphans.length) console.log(`  note: ${orphans.length} defined schema(s) are never referenced (${orphans.slice(0,8).join(", ")}${orphans.length>8?", ...":""})`);
  '\''
' && ok "all \$refs resolve" || bad "unresolved \$refs (see above)"

# --- 3. redocly lint: zero errors AND zero warnings -------------------------
step "redocly lint — zero errors AND zero warnings (§2.3)"
lint_out="$(docker run --rm -v "$ROOT:/repo" -w /repo "$NODE_IMAGE" \
             npx --yes @redocly/cli@latest lint "$SPEC_REL" 2>&1)"
lint_rc=$?
echo "$lint_out" | tail -40

warn_count="$(echo "$lint_out" | grep -oE '[0-9]+ warnings?' | tail -1 | grep -oE '[0-9]+' || true)"
err_count="$(echo  "$lint_out" | grep -oE '[0-9]+ errors?'   | tail -1 | grep -oE '[0-9]+' || true)"

if [ "$lint_rc" -ne 0 ] || [ "${err_count:-0}" != "0" ]; then
  bad "redocly reported ${err_count:-?} error(s)."
elif [ "${warn_count:-0}" != "0" ]; then
  bad "redocly reported ${warn_count} warning(s). The gate is zero warnings too (§2.3) — fix them; do not add an ignore file or downgrade the severity."
else
  ok "0 errors, 0 warnings"
fi

# --- 4. the frontend client generator must succeed --------------------------
step "frontend 'npm run gen:api' must succeed and produce output (§2.3)"
if [ ! -f "$ROOT/frontend/package.json" ]; then
  skip "frontend/ has not landed yet — gen:api not run"
elif ! grep -q '"gen:api"' "$ROOT/frontend/package.json"; then
  bad "frontend/package.json defines no 'gen:api' script."
  printf "  The spec is a build dependency for the frontend client (§2.3); the generator\n" >&2
  printf "  must be runnable as: npm run gen:api\n" >&2
else
  before="$(find "$ROOT/frontend/src" -newer "$SPEC" -type f 2>/dev/null | wc -l | tr -d ' ')"
  if docker run --rm -v "$ROOT:/repo" -w /repo/frontend "$NODE_IMAGE" \
       bash -lc "npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund >/dev/null 2>&1; npm run gen:api"; then
    generated="$(git -C "$ROOT" status --porcelain -- frontend 2>/dev/null | wc -l | tr -d ' ')"
    produced="$(find "$ROOT/frontend" -path "$ROOT/frontend/node_modules" -prune -o -newermt '-5 minutes' -type f -print 2>/dev/null | wc -l | tr -d ' ')"
    if [ "${produced:-0}" -gt 0 ] || [ "${generated:-0}" -gt 0 ]; then
      ok "gen:api produced output"
    else
      bad "gen:api exited 0 but produced no files — the generator is not wired to the spec."
    fi
  else
    bad "gen:api failed — the spec does not generate a usable client."
  fi
fi

# --- 5. drift check: live responses vs the schema ---------------------------
# §2.3: "Backend endpoints that drift from the spec are a defect even when they
# work — the contract job catches drift by validating live responses against the
# schema." Wired now, active as soon as the backend serves traffic.
step "drift check — live backend responses validated against the schema"
DRIFT_BASE_URL="${DRIFT_BASE_URL:-http://localhost:$( . "$ROOT/.env" 2>/dev/null; echo "${CASSYX_API_PORT:-8081}")}"
if curl -fsS --max-time 3 "$DRIFT_BASE_URL/api/health" >/dev/null 2>&1; then
  docker run --rm --network host -v "$ROOT:/repo" -w /repo "$NODE_IMAGE" bash -lc "
    set -e
    npx --yes @redocly/cli@latest bundle '$SPEC_REL' -o /tmp/spec.json >/dev/null
    mkdir -p /tmp/drift && cd /tmp/drift
    npm i --no-save --no-audit --no-fund --silent ajv@8 ajv-formats@3
    # Run the script FROM /tmp/drift rather than pointing NODE_PATH at it: NODE_PATH is
    # honoured only by CommonJS require(), never by ESM import, so the previous form
    # installed ajv and then failed to resolve it.
    cp /repo/scripts/openapi-drift.mjs /tmp/drift/openapi-drift.mjs
    node /tmp/drift/openapi-drift.mjs /tmp/spec.json '$DRIFT_BASE_URL'
  " && ok "live responses match the schema" || bad "live responses drift from the schema"
else
  skip "backend not reachable at $DRIFT_BASE_URL — drift check inactive (run 'make up' first)"
fi

echo
if [ "$STATUS" = 0 ]; then
  printf "${GRN}✓ contract gate passed${OFF}\n"
else
  printf "${RED}✗ contract gate FAILED — the spec is the coordination artifact; a red spec blocks every workstream (§2.3).${OFF}\n" >&2
fi
exit "$STATUS"
