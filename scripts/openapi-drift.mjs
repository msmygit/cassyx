/**
 * Contract drift check (plan §2.3 / the `contract` CI job in §11.1).
 *
 *   node openapi-drift.mjs <bundled-spec.json> <base-url>
 *
 * Calls every safe, parameter-free GET operation in the spec against a live
 * backend and validates the response body against the declared response schema.
 * "Backend endpoints that drift from the spec are a defect even when they work."
 *
 * Invoked by scripts/check-openapi.sh, which only runs it when the backend is
 * actually reachable — so it is inert until the API workstream serves traffic.
 * As operations gain parameters, add fixtures to SAFE_OVERRIDES rather than
 * loosening the validation.
 */
import { readFileSync } from 'node:fs';
import Ajv from 'ajv';
import addFormats from 'ajv-formats';

const [, , specPath, baseUrl] = process.argv;
if (!specPath || !baseUrl) {
  console.error('usage: openapi-drift.mjs <bundled-spec.json> <base-url>');
  process.exit(2);
}

const spec = JSON.parse(readFileSync(specPath, 'utf8'));
const ajv = new Ajv({ strict: false, allErrors: true, validateFormats: true });
addFormats(ajv);

/** Path templates we can call with fixed values, for endpoints that take params. */
const SAFE_OVERRIDES = {
  // '/api/connections/{id}': { id: '00000000-0000-0000-0000-000000000000' },
};

const results = { checked: 0, skipped: 0, failed: 0 };
const failures = [];

for (const [path, item] of Object.entries(spec.paths ?? {})) {
  const op = item.get;
  if (!op) continue;

  const params = [...(item.parameters ?? []), ...(op.parameters ?? [])];
  const required = params.filter((p) => p.required);
  let url = path;

  if (required.length) {
    const override = SAFE_OVERRIDES[path];
    if (!override) { results.skipped++; continue; }
    for (const [k, v] of Object.entries(override)) url = url.replace(`{${k}}`, v);
  }
  if (url.includes('{')) { results.skipped++; continue; }

  const ok = op.responses?.['200'] ?? op.responses?.['default'];
  const schema = ok?.content?.['application/json']?.schema;
  if (!schema) { results.skipped++; continue; }

  let res, body;
  try {
    res = await fetch(new URL(url, baseUrl), { headers: { accept: 'application/json' } });
    body = await res.json();
  } catch (e) {
    results.skipped++;               // unreachable / non-JSON: not a drift signal
    continue;
  }

  // A status the spec never declares is itself drift.
  if (!op.responses?.[String(res.status)] && !op.responses?.default) {
    failures.push(`${url} -> ${res.status}, which the spec does not declare`);
    results.failed++;
    continue;
  }
  if (res.status !== 200) { results.skipped++; continue; }

  // Response schemas are almost always `$ref: '#/components/schemas/X'`, and those
  // pointers are relative to the DOCUMENT root. Compiling the bare sub-schema gives ajv
  // no root to resolve against, so every ref-bearing response fails with
  // "can't resolve reference #/components/schemas/... from id #". Carrying `components`
  // into the compiled root makes the same pointers resolve.
  const validate = ajv.compile({ ...schema, components: spec.components });
  results.checked++;
  if (!validate(body)) {
    results.failed++;
    failures.push(`${url}\n    ` + (validate.errors ?? [])
      .map((e) => `${e.instancePath || '/'} ${e.message}`).join('\n    '));
  }
}

console.log(`  drift: ${results.checked} checked · ${results.skipped} skipped · ${results.failed} failed`);
if (failures.length) {
  console.error('\n  Live responses drift from the contract:\n');
  for (const f of failures) console.error(`    ${f}`);
  console.error('\n  Contract first: change the spec, then implement (§2.3).\n');
  process.exit(1);
}
