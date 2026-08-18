import { describe, expect, it } from 'vitest';
import {
  domainsForRegion,
  emptyConnectionForm,
  isConnectableDatabase,
  looksLikeAstraToken,
  maskSecret,
  parseContactPoints,
  redactSecrets,
  regionsFromBundles,
  validateAstra,
  validateConnection,
} from './connectionModel';
import type { AstraBundleDatacenter } from '../api/types';

const TOKEN = 'AstraCS:abcDEF123:9f8e7d6c5b4a';

describe('parseContactPoints', () => {
  it('parses comma- and space-separated host:port lists', () => {
    expect(parseContactPoints('10.0.0.1:9042, 10.0.0.2')).toEqual([
      { host: '10.0.0.1', port: 9042 },
      { host: '10.0.0.2', port: 9042 },
    ]);
  });

  it('handles IPv6-ish hosts by taking the last colon as the port separator', () => {
    expect(parseContactPoints('cassandra.internal:19042')).toEqual([
      { host: 'cassandra.internal', port: 19042 },
    ]);
  });

  it('falls back to 9042 for an unparseable port', () => {
    expect(parseContactPoints('host:abc')).toEqual([{ host: 'host', port: 9042 }]);
  });

  it('returns nothing for blank input', () => {
    expect(parseContactPoints('   ')).toEqual([]);
  });
});

describe('secret handling', () => {
  it('recognises well-formed Astra tokens only', () => {
    expect(looksLikeAstraToken(TOKEN)).toBe(true);
    expect(looksLikeAstraToken('AstraCS:only-one-part')).toBe(false);
    expect(looksLikeAstraToken('not-a-token')).toBe(false);
  });

  it('masks all but the last four characters', () => {
    const masked = maskSecret(TOKEN);
    expect(masked.endsWith('b4a')).toBe(true);
    expect(masked).not.toContain('AstraCS');
    expect(maskSecret('ab')).toBe('••••');
    expect(maskSecret('')).toBe('');
  });

  it('redacts tokens from any text that might reach the UI or a log', () => {
    expect(redactSecrets(`connect failed for ${TOKEN}`)).toBe(
      'connect failed for AstraCS:[REDACTED]',
    );
    expect(redactSecrets('Authorization: Bearer abcdefgh12345')).toContain('Bearer [REDACTED]');
    expect(redactSecrets('nothing sensitive')).toBe('nothing sensitive');
  });
});

describe('validateAstra', () => {
  const base = emptyConnectionForm().astra;

  it('requires a well-formed token', () => {
    expect(validateAstra({ ...base }).astraToken).toMatch(/required/i);
    expect(validateAstra({ ...base, astraToken: 'junk' }).astraToken).toMatch(/AstraCS/);
  });

  it('AUTO_DOWNLOAD requires a picked database', () => {
    const errors = validateAstra({ ...base, astraToken: TOKEN });
    expect(errors.databaseId).toBeDefined();
    expect(
      validateAstra({ ...base, astraToken: TOKEN, databaseId: 'db-1' }).databaseId,
    ).toBeUndefined();
  });

  it('AUTO_DOWNLOAD with a custom bundle type requires a domain', () => {
    const errors = validateAstra({
      ...base,
      astraToken: TOKEN,
      databaseId: 'db-1',
      scbType: 'custom',
    });
    expect(errors.customDomain).toBeDefined();
  });

  it('region is orthogonal to bundle type — a region alone never requires a domain', () => {
    const errors = validateAstra({
      ...base,
      astraToken: TOKEN,
      databaseId: 'db-1',
      region: 'us-east1',
      scbType: 'default',
    });
    expect(errors).toEqual({});
  });

  it('rejects any bundle type outside {default, custom}', () => {
    const errors = validateAstra({
      ...base,
      astraToken: TOKEN,
      databaseId: 'db-1',
      // The DataStax reference implementation documents a third `region` type it never implements.
      scbType: 'region' as never,
    });
    expect(errors.scbType).toMatch(/default.*custom/i);
  });

  it('UPLOAD requires a chosen file', () => {
    expect(
      validateAstra({ ...base, astraToken: TOKEN, acquisitionMode: 'UPLOAD' }).bundleFile,
    ).toBeDefined();
    expect(
      validateAstra({
        ...base,
        astraToken: TOKEN,
        acquisitionMode: 'UPLOAD',
        bundleFileName: 'scb.zip',
      }).bundleFile,
    ).toBeUndefined();
  });

  it('PATH requires a .zip path', () => {
    expect(
      validateAstra({ ...base, astraToken: TOKEN, acquisitionMode: 'PATH' }).bundlePath,
    ).toMatch(/server-side path/i);
    expect(
      validateAstra({
        ...base,
        astraToken: TOKEN,
        acquisitionMode: 'PATH',
        bundlePath: '/etc/cassyx/scb/db',
      }).bundlePath,
    ).toMatch(/\.zip/);
    expect(
      validateAstra({
        ...base,
        astraToken: TOKEN,
        acquisitionMode: 'PATH',
        bundlePath: '/etc/cassyx/scb/db.zip',
      }).bundlePath,
    ).toBeUndefined();
  });
});

describe('validateConnection', () => {
  it('defaults to AUTO_DOWNLOAD for Astra', () => {
    expect(emptyConnectionForm().astra.acquisitionMode).toBe('AUTO_DOWNLOAD');
  });

  it('requires a name in every mode', () => {
    expect(validateConnection(emptyConnectionForm()).name).toBeDefined();
  });

  it('validates the Cassandra mode', () => {
    const form = { ...emptyConnectionForm(), name: 'local' };
    expect(validateConnection(form)).toEqual({});

    const broken = {
      ...form,
      cassandra: { ...form.cassandra, contactPoints: '', localDatacenter: '' },
    };
    const errors = validateConnection(broken);
    expect(errors.contactPoints).toBeDefined();
    expect(errors.localDatacenter).toBeDefined();
  });

  it('validates the advanced mode', () => {
    const form = { ...emptyConnectionForm(), name: 'x', mode: 'ADVANCED' as const };
    expect(validateConnection(form).applicationConf).toBeDefined();
    expect(
      validateConnection({ ...form, advanced: { applicationConf: 'datastax-java-driver {}' } }),
    ).toEqual({});
  });

  it('delegates to validateAstra in Astra mode', () => {
    const form = { ...emptyConnectionForm(), name: 'astra', mode: 'ASTRA' as const };
    expect(validateConnection(form).astraToken).toBeDefined();
  });
});

describe('bundle option projections', () => {
  const bundles: AstraBundleDatacenter[] = [
    {
      region: 'us-east1',
      downloadURL: 'https://astra.example/scb-us-east1.zip',
      customDomainBundles: [
        { domain: 'db.example.com', downloadURL: 'https://astra.example/d.zip' },
      ],
    },
    {
      region: 'eu-west-1',
      downloadURL: 'https://astra.example/scb-eu.zip',
      customDomainBundles: [],
    },
  ];

  it('lists regions', () => {
    expect(regionsFromBundles(bundles)).toEqual(['us-east1', 'eu-west-1']);
  });

  it('matches the region case-insensitively', () => {
    expect(domainsForRegion(bundles, 'US-EAST1')).toEqual(['db.example.com']);
    expect(domainsForRegion(bundles, 'eu-west-1')).toEqual([]);
  });

  it('falls back to the first entry when no region is requested', () => {
    expect(domainsForRegion(bundles, '')).toEqual(['db.example.com']);
    expect(domainsForRegion([], '')).toEqual([]);
  });

  it('knows which database statuses are connectable', () => {
    expect(isConnectableDatabase('ACTIVE')).toBe(true);
    expect(isConnectableDatabase('HIBERNATED')).toBe(false);
  });
});
