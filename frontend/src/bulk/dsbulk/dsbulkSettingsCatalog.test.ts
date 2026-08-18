import { describe, expect, it } from 'vitest';
import {
  DSBULK_DOCS_BASE,
  DSBULK_STATS_MODES,
  DSBULK_STATS_MODE_ALIASES,
  DSBULK_GROUP_LABELS,
  DSBULK_SETTINGS,
  DSBULK_SETTING_GROUPS,
  SECRET_SETTING_PATHS,
  dsbulkDocsUrl,
  findSetting,
  isSecretPath,
  settingsForGroup,
  simpleSettings,
} from './dsbulkSettingsCatalog';

describe('dsbulkSettingsCatalog', () => {
  it('covers every DSBulk group the contract models', () => {
    const covered = new Set(DSBULK_SETTINGS.map((setting) => setting.group));
    expect([...covered].sort()).toEqual([...DSBULK_SETTING_GROUPS].sort());
    for (const group of DSBULK_SETTING_GROUPS) {
      expect(settingsForGroup(group).length).toBeGreaterThan(0);
      expect(DSBULK_GROUP_LABELS[group]).toBeTruthy();
    }
  });

  it('models both connectors, not just CSV', () => {
    const paths = DSBULK_SETTINGS.map((setting) => setting.path);
    expect(paths).toContain('connector.csv.delimiter');
    expect(paths).toContain('connector.json.mode');
    expect(paths).toContain('connector.name');
  });

  it('has unique paths', () => {
    const paths = DSBULK_SETTINGS.map((setting) => setting.path);
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('gives every setting a label, help text and an anchored docs link', () => {
    for (const setting of DSBULK_SETTINGS) {
      expect(setting.label.length).toBeGreaterThan(0);
      expect(setting.help.length).toBeGreaterThan(0);
      expect(setting.docsUrl.startsWith(`${DSBULK_DOCS_BASE}#`)).toBe(true);
      expect(setting.docsUrl).toBe(dsbulkDocsUrl(setting.dsbulkPath ?? setting.path));
    }
  });

  it('anchors driver settings on DSBulk’s own path, not the contract field name', () => {
    const consistency = findSetting('driver.basic.requestConsistency');
    expect(consistency?.docsUrl).toBe(`${DSBULK_DOCS_BASE}#driver.basic.request.consistency`);
  });

  it('resolves a setting by contract path or by DSBulk path', () => {
    expect(findSetting('driver.basic.requestConsistency')?.path).toBe(
      'driver.basic.requestConsistency',
    );
    expect(findSetting('driver.basic.request.consistency')?.path).toBe(
      'driver.basic.requestConsistency',
    );
    expect(findSetting('not.a.setting')).toBeUndefined();
  });

  it('marks exactly the three S3 credentials as secret', () => {
    expect([...SECRET_SETTING_PATHS].sort()).toEqual([
      's3.accessKeyId',
      's3.secretAccessKey',
      's3.sessionToken',
    ]);
    expect(isSecretPath('s3.secretAccessKey')).toBe(true);
    expect(isSecretPath('s3.region')).toBe(false);
    expect(isSecretPath('nonsense')).toBe(false);
  });

  it('promotes the genuinely simple fields to the Simple tab', () => {
    const simple = simpleSettings().map((setting) => setting.path);
    for (const path of [
      'connector.name',
      'connector.csv.header',
      'connector.csv.delimiter',
      'schema.keyspace',
      'schema.table',
      'schema.mapping',
      'batch.mode',
      'stats.modes',
    ]) {
      expect(simple).toContain(path);
    }
    // Progressive disclosure only works if "simple" stays small.
    expect(simple.length).toBeLessThan(20);
  });

  it('gives enum settings their allowed values and lists an upstream default for the rest', () => {
    for (const setting of DSBULK_SETTINGS) {
      if (setting.kind === 'enum') expect(setting.enumValues?.length).toBeGreaterThan(1);
      expect(typeof setting.upstreamDefault).toBe('string');
    }
  });

  it('flags the NC-multiplier and error-threshold settings so they validate correctly', () => {
    expect(findSetting('schema.splits')?.format).toBe('concurrency');
    expect(findSetting('connector.csv.maxConcurrentFiles')?.format).toBe('concurrency');
    expect(findSetting('log.maxErrors')?.format).toBe('errorThreshold');
  });

  /**
   * These came back from a run against DSBulk 1.11.x's own `dsbulk-reference.conf` and a live
   * cluster. Several differ from the OpenAPI `example:` values, which are illustrations rather
   * than defaults — and a wrong `upstreamDefault` is a lie in a placeholder, since the field's
   * whole job is to say what happens if you leave it alone.
   */
  it('uses DSBulk’s real defaults, not the contract’s illustrative examples', () => {
    const expected: Record<string, string> = {
      'connector.csv.url': '-',
      'connector.json.url': '-',
      'connector.csv.urlfile': '',
      'connector.json.urlfile': '',
      // java.util.Formatter has no `%0,6d`; the contract example is not a usable pattern.
      'connector.csv.fileNameFormat': 'output-%06d.csv',
      'connector.json.fileNameFormat': 'output-%06d.json',
      'connector.csv.comment': ' ',
      'connector.csv.nullValue': 'AUTO',
      'connector.csv.emptyValue': 'AUTO',
      'connector.json.deserializationFeatures': 'USE_BIG_DECIMAL_FOR_FLOATS=true',
      // Unmapped fields are IGNORED by default upstream — the opposite of the contract example.
      'schema.allowExtraFields': 'true',
      'schema.splits': '8C',
      'batch.mode': 'PARTITION_KEY',
      'batch.maxBatchStatements': '32',
      'codec.booleanNumbers': '1, 0',
      // -1 upstream; 1024 is a cassyx-derived value, not a default.
      'executor.maxInFlight': '-1',
      // DSBulk enables JMX by default; cassyx derives it off.
      'monitoring.jmx': 'true',
      'monitoring.trackBytes': 'false',
      'monitoring.reportRate': '5 seconds',
      'monitoring.console': 'true',
      'driver.advanced.connectionPoolLocalSize': '8',
      'driver.advanced.connectionPoolRemoteSize': '8',
      // `lz4` is cassyx's derived value, not DSBulk's default.
      'driver.advanced.protocolCompression': 'none',
    };
    for (const [path, upstreamDefault] of Object.entries(expected)) {
      expect([path, findSetting(path)?.upstreamDefault]).toEqual([path, upstreamDefault]);
    }
  });

  it('names the retry policy class that actually ships in the 1.11 distribution', () => {
    // The contract's example (`...workflow.api.utils.MultipleRetryPolicy`) does not exist.
    expect(findSetting('driver.advanced.retryPolicyClass')?.upstreamDefault).toBe(
      'com.datastax.oss.dsbulk.workflow.commons.policies.retry.MultipleRetryPolicy',
    );
  });

  /**
   * Typesafe Config accepts unknown keys in silence, so a wrong path renders a perfectly normal
   * field that then does nothing at all. These three are nested one level deeper upstream than
   * the contract spells them.
   */
  it('anchors the log statement/row settings on their real nested DSBulk paths', () => {
    expect(findSetting('log.maxQueryStringLength')?.dsbulkPath).toBe(
      'log.stmt.maxQueryStringLength',
    );
    expect(findSetting('log.maxBoundValueLength')?.dsbulkPath).toBe('log.stmt.maxBoundValueLength');
    expect(findSetting('log.maxResultSetValueLength')?.dsbulkPath).toBe(
      'log.row.maxResultSetValueLength',
    );
    expect(findSetting('log.maxResultSetValueLength')?.docsUrl).toBe(
      `${DSBULK_DOCS_BASE}#log.row.maxResultSetValueLength`,
    );
    // Both spellings still resolve, so a server-reported path merges either way.
    expect(findSetting('log.stmt.maxQueryStringLength')?.path).toBe('log.maxQueryStringLength');
  });

  it('explains that log.verbosity is an upstream enum the API models as 0..2', () => {
    const verbosity = findSetting('log.verbosity');
    expect(verbosity?.upstreamDefault).toBe('normal');
    expect(verbosity?.help).toMatch(/0 → quiet/);
    expect(verbosity?.help).toMatch(/2 → high/);
  });

  it('warns that most s3.* fields are URL parameters or do not exist upstream', () => {
    // `s3.clientCacheSize` is the only real DSBulk s3 setting.
    expect(findSetting('s3.clientCacheSize')?.help).toMatch(/only real `s3\.\*` DSBulk setting/);
    for (const path of ['s3.region', 's3.profile', 's3.accessKeyId', 's3.secretAccessKey']) {
      expect([path, findSetting(path)?.help]).toEqual([
        path,
        expect.stringContaining('query parameter') as unknown as string,
      ]);
    }
    expect(findSetting('s3.region')?.help).toMatch(/REQUIRED/);
    for (const path of ['s3.sessionToken', 's3.endpoint']) {
      expect([path, findSetting(path)?.help]).toEqual([
        path,
        expect.stringContaining('NOT SUPPORTED by DSBulk 1.11') as unknown as string,
      ]);
    }
  });

  it('lists only the four count modes DSBulk implements, keeping the contract alias labelled', () => {
    expect([...DSBULK_STATS_MODES]).toEqual(['global', 'ranges', 'hosts', 'partitions']);
    expect([...DSBULK_STATS_MODE_ALIASES]).toEqual(['biggest-partitions']);

    const modes = findSetting('stats.modes');
    // Still accepted, so a contract-shaped template validates.
    expect(modes?.enumValues).toContain('biggest-partitions');
    expect(modes?.help).toMatch(/accepted alias/);
    expect(modes?.help).toMatch(/`partitions` IS the largest-partitions report/);
  });

  it('records why cassyx omits executor.maxPerSecond on Astra rather than sending -1', () => {
    expect(findSetting('executor.maxPerSecond')?.help).toMatch(/3000 ops\/s per coordinator/);
  });
});
