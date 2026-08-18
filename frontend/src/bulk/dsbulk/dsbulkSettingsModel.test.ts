import { describe, expect, it } from 'vitest';
import {
  applyOverride,
  buildLoadJobRequest,
  buildMappingString,
  clearOverride,
  displayValue,
  flattenSettings,
  isAuto,
  isConcurrencyExpression,
  isErrorThreshold,
  isIntegerString,
  isNumberString,
  normalisePath,
  parseMap,
  parseMappingString,
  resolveSettings,
  serialiseMap,
  stripSecrets,
  unflattenSettings,
  validateFlatSettings,
  validateLoadDraft,
  validateSettingValue,
  type DerivedSetting,
  type DsbulkSettings,
  type LoadJobDraft,
} from './dsbulkSettingsModel';
import { findSetting } from './dsbulkSettingsCatalog';

const NESTED: DsbulkSettings = {
  connector: { name: 'csv', csv: { delimiter: ';', header: true, maxRecords: -1 } },
  schema: { keyspace: 'demo', table: 'users', splits: '8C' },
  batch: { mode: 'PARTITION_KEY', maxBatchStatements: 32 },
  codec: { nullStrings: ['NULL', 'N/A'] },
  driver: { basic: { requestConsistency: 'LOCAL_QUORUM' } },
  extra: { 'dsbulk.connector.csv.ignoreLeadingWhitespaces': 'true' },
};

describe('flattenSettings', () => {
  it('flattens the nested contract document to DSBulk paths', () => {
    expect(flattenSettings(NESTED)).toEqual({
      'connector.name': 'csv',
      'connector.csv.delimiter': ';',
      'connector.csv.header': 'true',
      'connector.csv.maxRecords': '-1',
      'schema.keyspace': 'demo',
      'schema.table': 'users',
      'schema.splits': '8C',
      'batch.mode': 'PARTITION_KEY',
      'batch.maxBatchStatements': '32',
      'codec.nullStrings': 'NULL, N/A',
      'driver.basic.requestConsistency': 'LOCAL_QUORUM',
      extra: 'dsbulk.connector.csv.ignoreLeadingWhitespaces=true',
    });
  });

  it('tolerates an absent document', () => {
    expect(flattenSettings(undefined)).toEqual({});
    expect(flattenSettings(null)).toEqual({});
  });

  it('serialises map options rather than recursing into their opaque keys', () => {
    const flat = flattenSettings({
      connector: { json: { parserFeatures: { ALLOW_COMMENTS: true } } },
    });
    expect(flat['connector.json.parserFeatures']).toBe('ALLOW_COMMENTS=true');
  });
});

describe('unflattenSettings', () => {
  it('round-trips the nested document', () => {
    expect(unflattenSettings(flattenSettings(NESTED))).toEqual(NESTED);
  });

  it('coerces each value back to its contract type', () => {
    const settings = unflattenSettings({
      'batch.maxBatchStatements': '64',
      'connector.csv.header': 'false',
      'codec.nullStrings': 'NULL, N/A',
      'connector.json.parserFeatures': 'ALLOW_COMMENTS=true',
      'schema.splits': '16C',
    });
    expect(settings.batch?.maxBatchStatements).toBe(64);
    expect(settings.connector?.csv?.header).toBe(false);
    expect(settings.codec?.nullStrings).toEqual(['NULL', 'N/A']);
    expect(settings.connector?.json?.parserFeatures).toEqual({ ALLOW_COMMENTS: true });
    expect(settings.schema?.splits).toBe('16C');
  });

  it('drops cleared fields so the derived default applies instead of an empty string', () => {
    expect(unflattenSettings({ 'schema.keyspace': '', 'schema.table': 'users' })).toEqual({
      schema: { table: 'users' },
    });
  });

  it('normalises a value reported under DSBulk’s own path', () => {
    const settings = unflattenSettings({ 'driver.basic.request.consistency': 'LOCAL_ONE' });
    expect(settings.driver?.basic?.requestConsistency).toBe('LOCAL_ONE');
  });

  it('keeps unmodelled paths as strings so a new DSBulk option still works', () => {
    const settings = unflattenSettings({ 'connector.csv.brandNewOption': 'yes' });
    expect(settings.connector?.csv).toEqual({ brandNewOption: 'yes' });
  });

  it('drops empty entries from a list — the comma form cannot express an empty element', () => {
    expect(unflattenSettings({ 'codec.nullStrings': 'NULL, , N/A' }).codec?.nullStrings).toEqual([
      'NULL',
      'N/A',
    ]);
  });

  it('keeps a non-numeric value for a numeric field instead of writing NaN', () => {
    const settings = unflattenSettings({ 'batch.maxBatchStatements': 'lots' });
    expect(settings.batch?.maxBatchStatements).toBe('lots');
  });
});

describe('map helpers', () => {
  it('serialises and parses k=v lists', () => {
    expect(serialiseMap({ a: 1, b: 'x' })).toBe('a=1, b=x');
    expect(parseMap('a=1,  b = x ,, junk, =bad')).toEqual({ a: '1', b: 'x' });
    expect(parseMap('A=true, B=false', 'boolean')).toEqual({ A: true, B: false });
  });
});

describe('resolveSettings', () => {
  const derived: DerivedSetting[] = [
    {
      path: 'batch.maxBatchStatements',
      value: '32',
      auto: true,
      upstreamDefault: '32',
      rationale: '32 statements per batch; 1 for counter tables.',
      group: 'batch',
    },
    { path: 'schema.splits', value: '192', auto: true, rationale: 'nodes × cores × 8' },
    { path: 'schema.keyspace', value: 'demo', auto: false },
  ];

  it('marks derived values as auto and carries the rationale for the tooltip', () => {
    const resolved = resolveSettings(derived);
    expect(resolved['batch.maxBatchStatements']?.auto).toBe(true);
    expect(resolved['batch.maxBatchStatements']?.rationale).toMatch(/counter tables/);
    expect(resolved['schema.keyspace']?.auto).toBe(false);
  });

  it('fills the upstream default and docs link from the catalog when the server omits them', () => {
    const resolved = resolveSettings(derived);
    expect(resolved['schema.splits']?.upstreamDefault).toBe(
      findSetting('schema.splits')?.upstreamDefault,
    );
    expect(resolved['schema.splits']?.docsUrl).toBe(findSetting('schema.splits')?.docsUrl);
  });

  it('flips auto to false when the user overrides, keeping the derived value for reset', () => {
    const resolved = resolveSettings(derived, { 'batch.maxBatchStatements': '64' });
    const setting = resolved['batch.maxBatchStatements'];
    expect(setting?.value).toBe('64');
    expect(setting?.auto).toBe(false);
    expect(setting?.autoValue).toBe('32');
    expect(setting?.rationale).toMatch(/counter tables/);
  });

  it('accepts an override keyed by DSBulk’s own path', () => {
    const resolved = resolveSettings(
      [{ path: 'driver.basic.request.consistency', value: 'LOCAL_ONE', auto: true }],
      { 'driver.basic.requestConsistency': 'QUORUM' },
    );
    expect(Object.keys(resolved)).toEqual(['driver.basic.requestConsistency']);
    expect(resolved['driver.basic.requestConsistency']?.value).toBe('QUORUM');
  });

  it('includes override-only paths the server never derived', () => {
    const resolved = resolveSettings([], { 'codec.locale': 'fr_FR' });
    expect(resolved['codec.locale']).toMatchObject({ value: 'fr_FR', auto: false });
  });

  it('exposes display and auto lookups by either path form', () => {
    const resolved = resolveSettings(derived);
    expect(displayValue(resolved, 'schema.splits')).toBe('192');
    expect(displayValue(resolved, 'codec.locale')).toBe('');
    expect(isAuto(resolved, 'schema.splits')).toBe(true);
    expect(isAuto(resolved, 'schema.keyspace')).toBe(false);
  });

  it('defaults to empty inputs', () => {
    expect(resolveSettings()).toEqual({});
  });
});

describe('overrides', () => {
  it('sets, normalises and clears', () => {
    const first = applyOverride({}, 'batch.maxBatchStatements', '64');
    expect(first).toEqual({ 'batch.maxBatchStatements': '64' });

    const normalised = applyOverride({}, 'driver.basic.request.consistency', 'ALL');
    expect(normalised).toEqual({ 'driver.basic.requestConsistency': 'ALL' });

    // Clearing a field is an explicit override, not a delete — otherwise a derived value would
    // reappear under the cursor as soon as the field was emptied.
    expect(applyOverride(first, 'batch.maxBatchStatements', '')).toEqual({
      'batch.maxBatchStatements': '',
    });
    expect(clearOverride(first, 'batch.maxBatchStatements')).toEqual({});
    // Inputs are never mutated.
    expect(first).toEqual({ 'batch.maxBatchStatements': '64' });
  });

  it('normalises a path even when it is unknown', () => {
    expect(normalisePath('some.new.option')).toBe('some.new.option');
  });
});

describe('stripSecrets', () => {
  it('removes write-only credentials and keeps everything else', () => {
    expect(
      stripSecrets({
        's3.region': 'eu-west-1',
        's3.accessKeyId': 'AKIA…',
        's3.secretAccessKey': 'super-secret',
        's3.sessionToken': 'token',
      }),
    ).toEqual({ 's3.region': 'eu-west-1' });
  });
});

describe('validation', () => {
  it('accepts DSBulk’s NC multiplier forms for split and concurrency settings', () => {
    for (const value of ['8C', '0.5C', '16', '-1', 'AUTO']) {
      expect(isConcurrencyExpression(value)).toBe(true);
    }
    for (const value of ['C8', '8x', 'many', '8 C']) {
      expect(isConcurrencyExpression(value)).toBe(false);
    }
    expect(validateSettingValue(findSetting('schema.splits'), '0.5C')).toBeUndefined();
    expect(validateSettingValue(findSetting('schema.splits'), 'lots')).toMatch(/NC multiplier/);
    expect(
      validateSettingValue(findSetting('connector.csv.maxConcurrentFiles'), '4C'),
    ).toBeUndefined();
  });

  it('accepts maxErrors as an absolute count or a percentage', () => {
    expect(isErrorThreshold('100')).toBe(true);
    expect(isErrorThreshold('1%')).toBe(true);
    expect(isErrorThreshold('0.5%')).toBe(true);
    expect(isErrorThreshold('%')).toBe(false);
    expect(isErrorThreshold('1 percent')).toBe(false);
    expect(validateSettingValue(findSetting('log.maxErrors'), '1%')).toBeUndefined();
    expect(validateSettingValue(findSetting('log.maxErrors'), 'some')).toMatch(/percentage/);
  });

  it('validates numbers, booleans, enums, enum lists and maps', () => {
    expect(isIntegerString('-12')).toBe(true);
    expect(isIntegerString('1.5')).toBe(false);
    expect(isNumberString('1.5')).toBe(true);

    expect(validateSettingValue(findSetting('batch.maxBatchStatements'), '32')).toBeUndefined();
    expect(validateSettingValue(findSetting('batch.maxBatchStatements'), 'many')).toBe(
      'Expected a number.',
    );
    expect(validateSettingValue(findSetting('connector.csv.header'), 'true')).toBeUndefined();
    expect(validateSettingValue(findSetting('connector.csv.header'), 'yes')).toMatch(/true or/);
    expect(validateSettingValue(findSetting('batch.mode'), 'PARTITION_KEY')).toBeUndefined();
    expect(validateSettingValue(findSetting('batch.mode'), 'NOPE')).toMatch(/Expected one of/);
    expect(validateSettingValue(findSetting('stats.modes'), 'global, ranges')).toBeUndefined();
    expect(validateSettingValue(findSetting('stats.modes'), 'global, nope')).toMatch(/nope/);
    expect(validateSettingValue(findSetting('log.checkpoint'), 'a=b, c=d')).toBeUndefined();
    expect(validateSettingValue(findSetting('log.checkpoint'), 'nonsense')).toMatch(/key=value/);
  });

  it('treats an empty value and an unknown setting as valid', () => {
    expect(validateSettingValue(findSetting('batch.maxBatchStatements'), '  ')).toBeUndefined();
    expect(validateSettingValue(undefined, 'anything')).toBeUndefined();
    expect(validateSettingValue(findSetting('codec.locale'), 'fr_FR')).toBeUndefined();
  });

  it('validates a whole override set and keys errors by normalised path', () => {
    expect(
      validateFlatSettings({
        'schema.splits': 'nope',
        'batch.maxBatchStatements': '32',
        'driver.basic.request.consistency': 'BOGUS',
      }),
    ).toEqual({
      'schema.splits': expect.stringMatching(/NC multiplier/) as unknown as string,
      'driver.basic.requestConsistency': expect.stringMatching(
        /Expected one of/,
      ) as unknown as string,
    });
  });
});

describe('mapping', () => {
  it('builds DSBulk’s a=b, c=d mapping string and skips incomplete rows', () => {
    expect(
      buildMappingString([
        { field: 'user_id', column: 'user_id' },
        { field: ' mail ', column: ' email ' },
        { field: '', column: 'ignored' },
        { field: 'ignored', column: '' },
      ]),
    ).toBe('user_id=user_id, mail=email');
    expect(buildMappingString([])).toBe('');
  });

  it('parses a mapping string back into rows', () => {
    expect(parseMappingString('0=user_id, 1=email ,, broken, =x, y=')).toEqual([
      { field: '0', column: 'user_id' },
      { field: '1', column: 'email' },
    ]);
  });
});

describe('load job draft', () => {
  const draft: LoadJobDraft = {
    name: ' Load users ',
    keyspace: ' demo ',
    table: ' users ',
    source: { uploadId: 'up_1', format: 'CSV', compression: 'AUTO' },
    mapping: ' user_id=user_id ',
    dryRun: true,
    overrides: { 'batch.maxBatchStatements': '64' },
  };

  it('rejects a draft with no target and no source', () => {
    expect(
      validateLoadDraft({
        ...draft,
        keyspace: '',
        table: '',
        source: { format: 'CSV', compression: 'AUTO' },
      }),
    ).toEqual({
      keyspace: 'Keyspace is required.',
      table: 'Table is required.',
      source: 'Choose an uploaded file, a server path or an S3 URI.',
    });
  });

  it('accepts a path or an S3 URI as the source', () => {
    expect(
      validateLoadDraft({ ...draft, source: { path: '/data/users.csv', compression: 'AUTO' } }),
    ).toEqual({});
    expect(
      validateLoadDraft({ ...draft, source: { s3Uri: 's3://b/users.csv', compression: 'AUTO' } }),
    ).toEqual({});
  });

  it('surfaces invalid DSBulk overrides alongside the field errors', () => {
    expect(validateLoadDraft({ ...draft, overrides: { 'schema.splits': 'nope' } })).toEqual({
      'schema.splits': expect.stringMatching(/NC multiplier/) as unknown as string,
    });
  });

  it('builds the contract request, trimming and nesting the overrides', () => {
    expect(buildLoadJobRequest(draft)).toEqual({
      name: 'Load users',
      keyspace: 'demo',
      table: 'users',
      source: { uploadId: 'up_1', format: 'CSV', compression: 'AUTO' },
      mapping: 'user_id=user_id',
      dryRun: true,
      dsbulkSettings: { batch: { maxBatchStatements: 64 } },
    });
  });

  it('omits the optional fields when they are blank', () => {
    const request = buildLoadJobRequest({ ...draft, name: '  ', mapping: '  ' });
    expect(request.name).toBeUndefined();
    expect(request.mapping).toBeUndefined();
    expect(request.templateId).toBeUndefined();
  });

  it('passes a template id through', () => {
    expect(buildLoadJobRequest({ ...draft, templateId: 'tpl-1' }).templateId).toBe('tpl-1');
  });
});
