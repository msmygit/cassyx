#!/usr/bin/env node
/**
 * Generate `src/api/schema.d.ts` from `openapi/cassyx-api.yaml` (plan §2.3).
 *
 * Why this is a script and not a one-line `openapi-typescript` npm script:
 *
 *  1. **It must fail loudly.** A malformed or incomplete spec (unresolved `$ref`s, no
 *     `components.schemas`) can make a generator emit a technically-valid but empty module. That
 *     typechecks as `any` everywhere and hides the breakage — strictly worse than a hard failure.
 *     We generate to a temp file, assert the output is substantive, and only then replace the
 *     committed file.
 *  2. **The spec is a build dependency owned by another workstream.** While it is being authored
 *     it may be absent or red. `--allow-missing` lets the normal build keep the hand-written
 *     placeholder; CI's `contract` job runs WITHOUT that flag, so a red spec breaks the build.
 *
 * Usage:
 *   node scripts/gen-api.mjs                  # fail if the spec is missing or unusable
 *   node scripts/gen-api.mjs --allow-missing  # skip (exit 0) if the spec file does not exist
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const frontendRoot = resolve(here, '..');
const specPath = resolve(frontendRoot, '../openapi/cassyx-api.yaml');
const outPath = resolve(frontendRoot, 'src/api/schema.d.ts');
const allowMissing = process.argv.includes('--allow-missing');

/** Minimum number of `components.schemas` members a real cassyx spec must produce. */
const MIN_SCHEMAS = 10;
/** Minimum number of `paths` members. */
const MIN_PATHS = 5;

function fail(message) {
  console.error(`\n[gen:api] FAILED — ${message}\n`);
  process.exit(1);
}

if (!existsSync(specPath)) {
  const message = `OpenAPI spec not found at ${specPath}`;
  if (allowMissing) {
    console.warn(
      `[gen:api] ${message}. Keeping the hand-written placeholder in src/api/schema.d.ts.`,
    );
    process.exit(0);
  }
  fail(`${message}. Phase 0 workstream 1 owns this file.`);
}

const spec = readFileSync(specPath, 'utf8');

// Plan §2.3 pins OpenAPI 3.1.1. Warn (do not fail) on a different 3.1.x patch; fail on 3.0/3.2,
// where openapi-typescript's behaviour differs materially.
const versionMatch = /^openapi:\s*["']?(\d+\.\d+\.\d+)["']?\s*$/m.exec(spec);
const version = versionMatch?.[1];
if (!version) {
  fail('could not read the `openapi:` version field from the spec.');
}
if (!version.startsWith('3.1.')) {
  fail(
    `spec declares OpenAPI ${version}, but the project pins 3.1.1 (plan §2.3). ` +
      '3.2.0 tooling support in openapi-typescript still lags; 3.0.x loses JSON Schema alignment.',
  );
}
if (version !== '3.1.1') {
  console.warn(`[gen:api] note: spec declares ${version}; the pinned version is 3.1.1.`);
}

// Cheap unresolved-$ref check. This is the failure mode called out in plan §2.3: paths written
// before their schemas exist lint red and silently produce a useless client.
const referenced = new Set(
  [...spec.matchAll(/\$ref:\s*["']?#\/components\/schemas\/([A-Za-z0-9_.-]+)["']?/g)].map(
    (match) => match[1],
  ),
);
const schemasSection = /\n {2}schemas:\n([\s\S]*?)(?=\n {2}[a-zA-Z]|\n[a-zA-Z]|$)/.exec(spec);
if (referenced.size > 0 && schemasSection) {
  const defined = new Set(
    [...schemasSection[1].matchAll(/^ {4}([A-Za-z0-9_.-]+):\s*$/gm)].map((match) => match[1]),
  );
  const missing = [...referenced].filter((name) => !defined.has(name)).sort();
  if (missing.length > 0) {
    fail(
      `${missing.length} schema(s) are $ref'd but never defined under components.schemas:\n` +
        `  ${missing.join(', ')}\n` +
        'Every $ref must resolve (plan §2.3).',
    );
  }
}

const tempDir = mkdtempSync(join(tmpdir(), 'cassyx-gen-api-'));
const tempOut = join(tempDir, 'schema.d.ts');

try {
  execFileSync(
    process.execPath,
    [resolve(frontendRoot, 'node_modules/openapi-typescript/bin/cli.js'), specPath, '-o', tempOut],
    { stdio: 'inherit', cwd: frontendRoot },
  );
} catch (error) {
  rmSync(tempDir, { recursive: true, force: true });
  fail(`openapi-typescript exited with an error: ${error.message}`);
}

if (!existsSync(tempOut)) {
  rmSync(tempDir, { recursive: true, force: true });
  fail('openapi-typescript produced no output file.');
}

const generated = readFileSync(tempOut, 'utf8');
rmSync(tempDir, { recursive: true, force: true });

// Guard against a silently-empty generation (see the header comment).
const pathCount = (generated.match(/^\s{4}"\/api\//gm) ?? []).length;
const schemasBlock = /export interface components \{[\s\S]*?schemas: \{([\s\S]*?)\n {4}\};/.exec(
  generated,
);
const schemaCount = schemasBlock ? (schemasBlock[1].match(/^ {8}[A-Za-z_"]/gm) ?? []).length : 0;

if (!generated.includes('export interface paths')) {
  fail('generated output has no `paths` interface — the spec is not a usable OpenAPI document.');
}
if (pathCount < MIN_PATHS) {
  fail(`generated output declares only ${pathCount} path(s); expected at least ${MIN_PATHS}.`);
}
if (schemaCount < MIN_SCHEMAS) {
  fail(
    `generated output declares only ${schemaCount} component schema(s); expected at least ` +
      `${MIN_SCHEMAS}. This is the "silently empty generation" case — everything would typecheck ` +
      'as `any` and hide the breakage.',
  );
}

const header = `/**
 * GENERATED FILE — DO NOT EDIT.
 *
 * Produced by \`npm run gen:api\` from openapi/cassyx-api.yaml (OpenAPI ${version}).
 * Re-run that script after any spec change; never hand-edit this file.
 *
 * Stable domain aliases for the app shell live in src/api/types.ts.
 */

`;

writeFileSync(outPath, header + generated, 'utf8');
console.log(
  `[gen:api] wrote src/api/schema.d.ts — ${pathCount} paths, ${schemaCount} component schemas.`,
);
