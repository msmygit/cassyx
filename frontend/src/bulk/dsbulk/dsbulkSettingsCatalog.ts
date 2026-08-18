/**
 * The DSBulk settings catalog (plan §5.3).
 *
 * One entry per setting modelled by `DsbulkSettings` in `openapi/cassyx-api.yaml`, across every
 * group. This table — not the components — is the single source of truth for labels, widget kind,
 * upstream defaults (rendered as placeholder text, never as a pre-filled value), help text and the
 * link into the upstream reference documentation.
 *
 * `path` is the path *within the `DsbulkSettings` document*, which is also what `DerivedSetting.path`
 * uses. Where DSBulk's own configuration path differs from the contract's camelCase field name
 * (`driver.basic.requestConsistency` ⇄ `driver.basic.request.consistency`) the real one is carried
 * in `dsbulkPath`; that is what the docs anchor and the generated HOCON use.
 *
 * Deliberately data, not code: adding a DSBulk option is one row here and the whole Advanced
 * accordion, validation and command preview pick it up.
 */

export type DsbulkSettingGroup =
  | 'connector'
  | 'schema'
  | 'batch'
  | 'codec'
  | 'engine'
  | 'executor'
  | 'log'
  | 'monitoring'
  | 'driver'
  | 's3'
  | 'stats';

export type DsbulkSettingKind = 'string' | 'number' | 'boolean' | 'enum' | 'stringList' | 'map';

/** Extra validation shapes DSBulk accepts beyond the plain kinds. */
export type DsbulkSettingFormat =
  | 'plain'
  /** Integer or an `NC` multiplier — `8C`, `0.5C`, or `AUTO`. */
  | 'concurrency'
  /** Absolute count or a percentage such as `1%`. */
  | 'errorThreshold';

export interface DsbulkSettingDef {
  /** Path within the `DsbulkSettings` document, e.g. `batch.maxBatchStatements`. */
  readonly path: string;
  /** DSBulk's own configuration path when it differs from `path`. */
  readonly dsbulkPath?: string;
  readonly group: DsbulkSettingGroup;
  readonly label: string;
  readonly kind: DsbulkSettingKind;
  readonly enumValues?: readonly string[];
  /** Value type of a `map` setting's entries. */
  readonly mapValue?: 'string' | 'boolean';
  /** DSBulk's default. Rendered as PLACEHOLDER text — never as a value. */
  readonly upstreamDefault: string;
  readonly docsUrl: string;
  readonly help: string;
  readonly format?: DsbulkSettingFormat;
  /** Surfaced on the Simple tab. */
  readonly simple?: true;
  /** Write-only credential: never rendered back, never placed in a URL. */
  readonly secret?: true;
}

export const DSBULK_SETTING_GROUPS: readonly DsbulkSettingGroup[] = [
  'connector',
  'schema',
  'batch',
  'codec',
  'engine',
  'executor',
  'log',
  'monitoring',
  'driver',
  's3',
  'stats',
];

export const DSBULK_GROUP_LABELS: Record<DsbulkSettingGroup, string> = {
  connector: 'Connector (CSV / JSON)',
  schema: 'Schema & mapping',
  batch: 'Batching',
  codec: 'Codecs & formats',
  engine: 'Engine',
  executor: 'Executor & throughput',
  log: 'Logging & error thresholds',
  monitoring: 'Monitoring',
  driver: 'Driver',
  s3: 'S3',
  stats: 'Statistics (count)',
};

export const DSBULK_DOCS_BASE = 'https://docs.datastax.com/en/dsbulk/docs/reference/settings.html';

export function dsbulkDocsUrl(dsbulkPath: string): string {
  return `${DSBULK_DOCS_BASE}#${dsbulkPath}`;
}

/** The consistency levels the contract models (`ConsistencyLevel`). */
const CONSISTENCY_LEVELS = [
  'ANY',
  'ONE',
  'TWO',
  'THREE',
  'QUORUM',
  'ALL',
  'LOCAL_ONE',
  'LOCAL_QUORUM',
  'EACH_QUORUM',
] as const;

const SERIAL_CONSISTENCY_LEVELS = ['SERIAL', 'LOCAL_SERIAL'] as const;

/**
 * The modes DSBulk 1.11 actually implements. `partitions` IS the "N biggest partitions" report,
 * sized by `stats.numPartitions` — there is no separate `biggest-partitions` workflow upstream.
 */
export const DSBULK_STATS_MODES = ['global', 'ranges', 'hosts', 'partitions'] as const;

/**
 * Contract-only spelling. `BulkStatsMode` in the OpenAPI offers `biggest-partitions`; the backend
 * folds it into `partitions`. Accepted so a saved template still validates, but labelled as an
 * alias so nobody expects a fifth report.
 */
export const DSBULK_STATS_MODE_ALIASES = ['biggest-partitions'] as const;

type Row = Omit<DsbulkSettingDef, 'docsUrl'> & { docsUrl?: string };

function def(row: Row): DsbulkSettingDef {
  return { ...row, docsUrl: row.docsUrl ?? dsbulkDocsUrl(row.dsbulkPath ?? row.path) };
}

/* ------------------------------------------------------------------------------- connector */

const CONNECTOR: DsbulkSettingDef[] = [
  def({
    path: 'connector.name',
    group: 'connector',
    label: 'Connector',
    kind: 'enum',
    enumValues: ['csv', 'json'],
    upstreamDefault: 'csv',
    help: 'Which DSBulk connector reads or writes the files.',
    simple: true,
  }),

  /* connector.csv.* */
  def({
    path: 'connector.csv.url',
    group: 'connector',
    label: 'CSV URL',
    kind: 'string',
    // `-` is DSBulk's literal default: stdin on load, stdout on unload.
    upstreamDefault: '-',
    help: 'Source or target URL. Normally derived from the job’s source or sink.',
  }),
  def({
    path: 'connector.csv.urlfile',
    group: 'connector',
    label: 'CSV URL file',
    kind: 'string',
    upstreamDefault: '',
    help: 'File containing one source URL per line. Mutually exclusive with the URL.',
  }),
  def({
    path: 'connector.csv.fileNamePattern',
    group: 'connector',
    label: 'File name pattern',
    kind: 'string',
    upstreamDefault: '**/*.csv',
    help: 'Glob used when reading a directory of CSV files.',
  }),
  def({
    path: 'connector.csv.fileNameFormat',
    group: 'connector',
    label: 'File name format',
    kind: 'string',
    // Verified against dsbulk-reference.conf 1.11.x — NOT `output-%0,6d.csv` as the contract example
    // has it; the comma form is not a valid java.util.Formatter width.
    upstreamDefault: 'output-%06d.csv',
    help: 'Printf-style name for each file written on unload.',
  }),
  def({
    path: 'connector.csv.recursive',
    group: 'connector',
    label: 'Recursive',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Descend into sub-directories when reading a directory.',
  }),
  def({
    path: 'connector.csv.header',
    group: 'connector',
    label: 'Header row',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'First record holds field names. Without it, map by zero-based position instead.',
    simple: true,
  }),
  def({
    path: 'connector.csv.delimiter',
    group: 'connector',
    label: 'Delimiter',
    kind: 'string',
    upstreamDefault: ',',
    help: 'Field separator. Use \\t for tab-separated files.',
    simple: true,
  }),
  def({
    path: 'connector.csv.quote',
    group: 'connector',
    label: 'Quote character',
    kind: 'string',
    upstreamDefault: '"',
    help: 'Character enclosing values that contain the delimiter or a newline.',
  }),
  def({
    path: 'connector.csv.escape',
    group: 'connector',
    label: 'Escape character',
    kind: 'string',
    upstreamDefault: '\\',
    help: 'Escapes a quote character inside a quoted value.',
  }),
  def({
    path: 'connector.csv.comment',
    group: 'connector',
    label: 'Comment character',
    kind: 'string',
    // A single space, which is what disables comment handling upstream.
    upstreamDefault: ' ',
    help: 'Lines starting with this character are skipped. A single space disables comments.',
  }),
  def({
    path: 'connector.csv.newline',
    group: 'connector',
    label: 'Newline',
    kind: 'string',
    upstreamDefault: 'auto',
    help: 'Line separator written on unload. `auto` uses the platform default.',
  }),
  def({
    path: 'connector.csv.encoding',
    group: 'connector',
    label: 'Encoding',
    kind: 'string',
    upstreamDefault: 'UTF-8',
    help: 'Character set of the files.',
  }),
  def({
    path: 'connector.csv.skipRecords',
    group: 'connector',
    label: 'Skip records',
    kind: 'number',
    upstreamDefault: '0',
    help: 'Records skipped at the start of each file, after the header.',
  }),
  def({
    path: 'connector.csv.maxRecords',
    group: 'connector',
    label: 'Max records',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Records read per file. -1 for unlimited.',
  }),
  def({
    path: 'connector.csv.maxConcurrentFiles',
    group: 'connector',
    label: 'Max concurrent files',
    kind: 'string',
    format: 'concurrency',
    upstreamDefault: 'AUTO',
    help: 'Files read or written in parallel. Accepts a number or an NC multiplier (0.5C). Auto-derived to the split count for unload.',
  }),
  def({
    path: 'connector.csv.maxCharsPerColumn',
    group: 'connector',
    label: 'Max chars per column',
    kind: 'number',
    upstreamDefault: '4096',
    help: 'Parser buffer per field. Raise it for very large text or blob columns.',
  }),
  def({
    path: 'connector.csv.maxColumns',
    group: 'connector',
    label: 'Max columns',
    kind: 'number',
    upstreamDefault: '512',
    help: 'Maximum number of fields per record.',
  }),
  def({
    path: 'connector.csv.ignoreLeadingWhitespaces',
    group: 'connector',
    label: 'Ignore leading whitespace',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Trim whitespace at the start of each unquoted value.',
  }),
  def({
    path: 'connector.csv.ignoreTrailingWhitespaces',
    group: 'connector',
    label: 'Ignore trailing whitespace',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Trim whitespace at the end of each unquoted value.',
  }),
  def({
    path: 'connector.csv.nullValue',
    group: 'connector',
    label: 'Null value',
    kind: 'string',
    upstreamDefault: 'AUTO',
    help: 'Token written for a null on unload, and recognised as null on load. `AUTO` writes an empty field on unload and reads an empty field as null on load.',
  }),
  def({
    path: 'connector.csv.emptyValue',
    group: 'connector',
    label: 'Empty value',
    kind: 'string',
    upstreamDefault: 'AUTO',
    help: 'Token representing an empty (not null) string. `AUTO` writes `""` on unload and reads `""` as the empty string on load.',
  }),
  def({
    path: 'connector.csv.normalizeLineEndingsInQuotes',
    group: 'connector',
    label: 'Normalize line endings in quotes',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Rewrite CRLF inside quoted values to LF.',
  }),
  def({
    path: 'connector.csv.compression',
    group: 'connector',
    label: 'Compression',
    kind: 'enum',
    enumValues: ['none', 'gzip', 'bzip2', 'lz4', 'snappy', 'zstd', 'xz', 'deflate'],
    upstreamDefault: 'none',
    help: 'Compression applied to the files themselves.',
  }),

  /* connector.json.* */
  def({
    path: 'connector.json.url',
    group: 'connector',
    label: 'JSON URL',
    kind: 'string',
    // `-` is DSBulk's literal default: stdin on load, stdout on unload.
    upstreamDefault: '-',
    help: 'Source or target URL for the JSON connector.',
  }),
  def({
    path: 'connector.json.urlfile',
    group: 'connector',
    label: 'JSON URL file',
    kind: 'string',
    upstreamDefault: '',
    help: 'File containing one source URL per line.',
  }),
  def({
    path: 'connector.json.fileNamePattern',
    group: 'connector',
    label: 'JSON file name pattern',
    kind: 'string',
    upstreamDefault: '**/*.json',
    help: 'Glob used when reading a directory of JSON files.',
  }),
  def({
    path: 'connector.json.fileNameFormat',
    group: 'connector',
    label: 'JSON file name format',
    kind: 'string',
    upstreamDefault: 'output-%06d.json',
    help: 'Printf-style name for each file written on unload.',
  }),
  def({
    path: 'connector.json.mode',
    group: 'connector',
    label: 'Document mode',
    kind: 'enum',
    enumValues: ['MULTI_DOCUMENT', 'SINGLE_DOCUMENT'],
    upstreamDefault: 'MULTI_DOCUMENT',
    help: 'MULTI_DOCUMENT is one JSON object per line (JSONL); SINGLE_DOCUMENT is one array per file.',
  }),
  def({
    path: 'connector.json.recursive',
    group: 'connector',
    label: 'JSON recursive',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Descend into sub-directories when reading a directory.',
  }),
  def({
    path: 'connector.json.encoding',
    group: 'connector',
    label: 'JSON encoding',
    kind: 'string',
    upstreamDefault: 'UTF-8',
    help: 'Character set of the files.',
  }),
  def({
    path: 'connector.json.skipRecords',
    group: 'connector',
    label: 'JSON skip records',
    kind: 'number',
    upstreamDefault: '0',
    help: 'Records skipped at the start of each file.',
  }),
  def({
    path: 'connector.json.maxRecords',
    group: 'connector',
    label: 'JSON max records',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Records read per file. -1 for unlimited.',
  }),
  def({
    path: 'connector.json.maxConcurrentFiles',
    group: 'connector',
    label: 'JSON max concurrent files',
    kind: 'string',
    format: 'concurrency',
    upstreamDefault: 'AUTO',
    help: 'Files read or written in parallel. Number or NC multiplier.',
  }),
  def({
    path: 'connector.json.parserFeatures',
    group: 'connector',
    label: 'Parser features',
    kind: 'map',
    mapValue: 'boolean',
    upstreamDefault: '',
    help: 'Jackson JsonParser features, as `FEATURE=true` pairs.',
  }),
  def({
    path: 'connector.json.generatorFeatures',
    group: 'connector',
    label: 'Generator features',
    kind: 'map',
    mapValue: 'boolean',
    upstreamDefault: '',
    help: 'Jackson JsonGenerator features, as `FEATURE=true` pairs.',
  }),
  def({
    path: 'connector.json.serializationFeatures',
    group: 'connector',
    label: 'Serialization features',
    kind: 'map',
    mapValue: 'boolean',
    upstreamDefault: '',
    help: 'Jackson SerializationFeature toggles, as `FEATURE=true` pairs.',
  }),
  def({
    path: 'connector.json.deserializationFeatures',
    group: 'connector',
    label: 'Deserialization features',
    kind: 'map',
    mapValue: 'boolean',
    // The only Jackson feature DSBulk overrides by default — it keeps `decimal` columns exact.
    upstreamDefault: 'USE_BIG_DECIMAL_FOR_FLOATS=true',
    help: 'Jackson DeserializationFeature toggles, as `FEATURE=true` pairs. Overriding this drops DSBulk’s own `USE_BIG_DECIMAL_FOR_FLOATS=true`, which is what keeps `decimal` values exact.',
  }),
  def({
    path: 'connector.json.prettyPrint',
    group: 'connector',
    label: 'Pretty print',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Indent generated JSON. Costs throughput and file size.',
  }),
  def({
    path: 'connector.json.compression',
    group: 'connector',
    label: 'JSON compression',
    kind: 'enum',
    enumValues: ['none', 'gzip', 'bzip2', 'lz4', 'snappy', 'zstd', 'xz', 'deflate'],
    upstreamDefault: 'none',
    help: 'Compression applied to the files themselves.',
  }),
];

/* ---------------------------------------------------------------------------------- schema */

const SCHEMA: DsbulkSettingDef[] = [
  def({
    path: 'schema.keyspace',
    group: 'schema',
    label: 'Keyspace',
    kind: 'string',
    upstreamDefault: '',
    help: 'Target keyspace. Mutually exclusive with a custom query.',
    simple: true,
  }),
  def({
    path: 'schema.table',
    group: 'schema',
    label: 'Table',
    kind: 'string',
    upstreamDefault: '',
    help: 'Target table. Mutually exclusive with a custom query.',
    simple: true,
  }),
  def({
    path: 'schema.query',
    group: 'schema',
    label: 'Query',
    kind: 'string',
    upstreamDefault: '',
    help: 'Custom CQL statement, used instead of keyspace and table.',
  }),
  def({
    path: 'schema.mapping',
    group: 'schema',
    label: 'Mapping',
    kind: 'string',
    upstreamDefault: '',
    help: 'Field-to-column mapping, e.g. `user_id=user_id, email=email` or `0=user_id, 1=email`.',
    simple: true,
  }),
  def({
    path: 'schema.nullToUnset',
    group: 'schema',
    label: 'Null to unset',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'Write nothing rather than a tombstone for null fields.',
  }),
  def({
    path: 'schema.allowExtraFields',
    group: 'schema',
    label: 'Allow extra fields',
    kind: 'boolean',
    // Upstream default is `true` — unmapped fields are IGNORED unless you turn this off.
    upstreamDefault: 'true',
    help: 'Tolerate record fields that map to no column. On by default upstream, so a typo in a header is silently ignored until you set this to false.',
  }),
  def({
    path: 'schema.allowMissingFields',
    group: 'schema',
    label: 'Allow missing fields',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Tolerate mapped columns absent from a record.',
  }),
  def({
    path: 'schema.splits',
    group: 'schema',
    label: 'Splits',
    kind: 'string',
    format: 'concurrency',
    upstreamDefault: '8C',
    help: 'Token-range split count. Number or NC multiplier. Deliberately oversplit — equal token ranges are not equal work.',
  }),
  def({
    path: 'schema.queryTimestamp',
    group: 'schema',
    label: 'Query timestamp',
    kind: 'string',
    // Unset upstream: the coordinator assigns the writetime.
    upstreamDefault: 'unset',
    help: 'ISO-8601 instant used as the writetime for every inserted row. Unset by default, in which case the coordinator assigns it.',
  }),
  def({
    path: 'schema.queryTtl',
    group: 'schema',
    label: 'Query TTL',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'TTL in seconds applied to inserted rows. -1 disables.',
  }),
  def({
    path: 'schema.preserveTimestamp',
    group: 'schema',
    label: 'Preserve timestamp',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Carry each row’s writetime through an unload/load round trip.',
  }),
  def({
    path: 'schema.preserveTtl',
    group: 'schema',
    label: 'Preserve TTL',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Carry each row’s TTL through an unload/load round trip.',
  }),
];

/* ----------------------------------------------------------------------------------- batch */

const BATCH: DsbulkSettingDef[] = [
  def({
    path: 'batch.mode',
    group: 'batch',
    label: 'Batch mode',
    kind: 'enum',
    enumValues: ['DISABLED', 'PARTITION_KEY', 'REPLICA_SET'],
    upstreamDefault: 'PARTITION_KEY',
    help: 'PARTITION_KEY for load; DISABLED when the target has no clustering key.',
    simple: true,
  }),
  def({
    path: 'batch.maxBatchStatements',
    group: 'batch',
    label: 'Max batch statements',
    kind: 'number',
    upstreamDefault: '32',
    help: 'Statements per batch. Dropped to 1 for counter tables.',
  }),
  def({
    path: 'batch.maxSizeInBytes',
    group: 'batch',
    label: 'Max batch size (bytes)',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Byte ceiling per batch. -1 disables the check.',
  }),
  def({
    path: 'batch.bufferSize',
    group: 'batch',
    label: 'Buffer size',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Statements buffered while grouping. -1 derives it from the batch size.',
  }),
];

/* ----------------------------------------------------------------------------------- codec */

const CODEC: DsbulkSettingDef[] = [
  def({
    path: 'codec.locale',
    group: 'codec',
    label: 'Locale',
    kind: 'string',
    upstreamDefault: 'en_US',
    help: 'Locale for number and date parsing.',
  }),
  def({
    path: 'codec.timeZone',
    group: 'codec',
    label: 'Time zone',
    kind: 'string',
    upstreamDefault: 'UTC',
    help: 'Time zone applied to dates and times without an explicit offset.',
  }),
  def({
    path: 'codec.booleanStrings',
    group: 'codec',
    label: 'Boolean strings',
    kind: 'stringList',
    upstreamDefault: '1:0, Y:N, T:F, YES:NO, TRUE:FALSE',
    help: 'Accepted true:false spellings, most specific first.',
  }),
  def({
    path: 'codec.booleanNumbers',
    group: 'codec',
    label: 'Boolean numbers',
    kind: 'stringList',
    upstreamDefault: '1, 0',
    help: 'Numbers meaning true and false, in that order.',
  }),
  def({
    path: 'codec.number',
    group: 'codec',
    label: 'Number format',
    kind: 'string',
    upstreamDefault: '#,###.##',
    help: 'DecimalFormat pattern for numeric values.',
  }),
  def({
    path: 'codec.formatNumbers',
    group: 'codec',
    label: 'Format numbers',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Apply the number format when writing. Off keeps full precision.',
  }),
  def({
    path: 'codec.roundingStrategy',
    group: 'codec',
    label: 'Rounding strategy',
    kind: 'enum',
    enumValues: [
      'UP',
      'DOWN',
      'CEILING',
      'FLOOR',
      'HALF_UP',
      'HALF_DOWN',
      'HALF_EVEN',
      'UNNECESSARY',
    ],
    upstreamDefault: 'UNNECESSARY',
    help: 'Rounding applied when formatting numbers.',
  }),
  def({
    path: 'codec.overflowStrategy',
    group: 'codec',
    label: 'Overflow strategy',
    kind: 'enum',
    enumValues: ['REJECT', 'TRUNCATE'],
    upstreamDefault: 'REJECT',
    help: 'What to do when a value does not fit the CQL type.',
  }),
  def({
    path: 'codec.timestamp',
    group: 'codec',
    label: 'Timestamp format',
    kind: 'string',
    upstreamDefault: 'CQL_TIMESTAMP',
    help: 'Pattern or named format for timestamp columns. Inferred from column types by default.',
  }),
  def({
    path: 'codec.date',
    group: 'codec',
    label: 'Date format',
    kind: 'string',
    upstreamDefault: 'ISO_LOCAL_DATE',
    help: 'Pattern or named format for date columns.',
  }),
  def({
    path: 'codec.time',
    group: 'codec',
    label: 'Time format',
    kind: 'string',
    upstreamDefault: 'ISO_LOCAL_TIME',
    help: 'Pattern or named format for time columns.',
  }),
  def({
    path: 'codec.unit',
    group: 'codec',
    label: 'Numeric timestamp unit',
    kind: 'enum',
    enumValues: [
      'NANOSECONDS',
      'MICROSECONDS',
      'MILLISECONDS',
      'SECONDS',
      'MINUTES',
      'HOURS',
      'DAYS',
    ],
    upstreamDefault: 'MILLISECONDS',
    help: 'Unit for timestamps expressed as numbers.',
  }),
  def({
    path: 'codec.epoch',
    group: 'codec',
    label: 'Epoch',
    kind: 'string',
    upstreamDefault: '1970-01-01T00:00:00Z',
    help: 'Origin for numeric timestamps and for completing partial dates.',
  }),
  def({
    path: 'codec.nullStrings',
    group: 'codec',
    label: 'Null strings',
    kind: 'stringList',
    // DSBulk's own default is the EMPTY list: nothing but the connector's own null token counts
    // as null. cassyx derives entries from a sniffed sample.
    upstreamDefault: '(none)',
    help: 'Tokens treated as null on load. Empty upstream; cassyx sniffs a sample of the source and derives them.',
  }),
  def({
    path: 'codec.binary',
    group: 'codec',
    label: 'Binary encoding',
    kind: 'enum',
    enumValues: ['BASE64', 'HEX'],
    upstreamDefault: 'BASE64',
    help: 'How blob values are rendered in text formats.',
  }),
  def({
    path: 'codec.uuidStrategy',
    group: 'codec',
    label: 'UUID strategy',
    kind: 'enum',
    enumValues: ['RANDOM', 'FIXED', 'MIN', 'MAX'],
    upstreamDefault: 'RANDOM',
    help: 'How a timestamp is converted into a timeuuid.',
  }),
];

/* ---------------------------------------------------------------------------------- engine */

const ENGINE: DsbulkSettingDef[] = [
  def({
    path: 'engine.dryRun',
    group: 'engine',
    label: 'Dry run',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Read, parse and map every record without writing anything.',
    simple: true,
  }),
  def({
    path: 'engine.executionId',
    group: 'engine',
    label: 'Execution ID',
    kind: 'string',
    upstreamDefault: '',
    help: 'Names the run and its log directory. Generated per job by default.',
  }),
  def({
    path: 'engine.maxConcurrentQueries',
    group: 'engine',
    label: 'Max concurrent queries',
    kind: 'string',
    format: 'concurrency',
    upstreamDefault: 'AUTO',
    help: 'In-flight queries. Auto-derived as nodes × 32 for unload, capped by client cores.',
  }),
  def({
    path: 'engine.dataSizeSamplingEnabled',
    group: 'engine',
    label: 'Data size sampling',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'Sample the source to size batches and estimate progress.',
  }),
];

/* -------------------------------------------------------------------------------- executor */

const EXECUTOR: DsbulkSettingDef[] = [
  def({
    path: 'executor.maxPerSecond',
    group: 'executor',
    label: 'Max rows per second',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Client-side throttle. -1 is unthrottled. On Astra cassyx OMITS this setting entirely rather than sending -1: DSBulk ≥1.9 applies its own cloud rate limit (3000 ops/s per coordinator) only while the setting is unset, so sending -1 would silently disable that protection.',
  }),
  def({
    path: 'executor.maxInFlight',
    group: 'executor',
    label: 'Max in flight',
    kind: 'number',
    // Upstream is unlimited (-1); 1024 is a cassyx-derived value, not a DSBulk default.
    upstreamDefault: '-1',
    help: 'Concurrent requests; -1 is unlimited upstream. cassyx derives a bound from node count × cores, capped by client cores.',
  }),
  def({
    path: 'executor.maxBytesPerSecond',
    group: 'executor',
    label: 'Max bytes per second',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Bandwidth throttle. -1 is unthrottled.',
  }),
  def({
    path: 'executor.continuousPaging.enabled',
    group: 'executor',
    label: 'Continuous paging',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'DSE-only streaming reads. Ignored on Cassandra and Astra.',
  }),
  def({
    path: 'executor.continuousPaging.pageSize',
    group: 'executor',
    label: 'Continuous paging page size',
    kind: 'number',
    upstreamDefault: '5000',
    help: 'Page size for continuous paging, in the unit below.',
  }),
  def({
    path: 'executor.continuousPaging.pageUnit',
    group: 'executor',
    label: 'Continuous paging page unit',
    kind: 'enum',
    enumValues: ['ROWS', 'BYTES'],
    upstreamDefault: 'ROWS',
    help: 'Whether the page size counts rows or bytes.',
  }),
  def({
    path: 'executor.continuousPaging.maxPages',
    group: 'executor',
    label: 'Continuous paging max pages',
    kind: 'number',
    upstreamDefault: '0',
    help: 'Pages per request. 0 is unlimited.',
  }),
  def({
    path: 'executor.continuousPaging.maxPagesPerSecond',
    group: 'executor',
    label: 'Continuous paging max pages/s',
    kind: 'number',
    upstreamDefault: '0',
    help: 'Server-side page rate limit. 0 is unlimited.',
  }),
];

/* ------------------------------------------------------------------------------------- log */

const LOG: DsbulkSettingDef[] = [
  def({
    path: 'log.directory',
    group: 'log',
    label: 'Log directory',
    kind: 'string',
    upstreamDefault: './logs',
    help: 'Per-job log directory. cassyx always sets this so progress can be tailed.',
  }),
  def({
    path: 'log.verbosity',
    group: 'log',
    label: 'Verbosity',
    // Upstream this is an enum (quiet|normal|high|max). The contract types it as 0..2 and the
    // backend translates: 0 → quiet, 1 → normal, 2 → high.
    kind: 'number',
    upstreamDefault: 'normal',
    help: 'Upstream this is an enum (quiet, normal, high, max) defaulting to `normal`. The API takes an integer and the server translates it: 0 → quiet, 1 → normal, 2 → high.',
  }),
  def({
    path: 'log.maxErrors',
    group: 'log',
    label: 'Max errors',
    kind: 'string',
    format: 'errorThreshold',
    upstreamDefault: '100',
    help: 'Absolute count, or a percentage such as 1%. The job aborts once it is exceeded.',
    simple: true,
  }),
  def({
    path: 'log.maxQueryStringLength',
    dsbulkPath: 'log.stmt.maxQueryStringLength',
    group: 'log',
    label: 'Max query string length',
    kind: 'number',
    upstreamDefault: '500',
    help: 'Truncation applied to queries in the error log.',
  }),
  def({
    path: 'log.maxBoundValueLength',
    dsbulkPath: 'log.stmt.maxBoundValueLength',
    group: 'log',
    label: 'Max bound value length',
    kind: 'number',
    upstreamDefault: '50',
    help: 'Truncation applied to bound values in the error log.',
  }),
  def({
    path: 'log.maxResultSetValueLength',
    dsbulkPath: 'log.row.maxResultSetValueLength',
    group: 'log',
    label: 'Max result set value length',
    kind: 'number',
    upstreamDefault: '50',
    help: 'Truncation applied to result-set values in the error log.',
  }),
  def({
    path: 'log.checkpoint',
    group: 'log',
    label: 'Checkpoint',
    kind: 'map',
    mapValue: 'string',
    upstreamDefault: '',
    help: 'Checkpoint/resume options, as `key=value` pairs.',
  }),
  def({
    path: 'log.ansiMode',
    group: 'log',
    label: 'ANSI mode',
    kind: 'enum',
    enumValues: ['normal', 'force', 'disabled'],
    upstreamDefault: 'normal',
    help: 'Colour output in the DSBulk console log.',
  }),
];

/* ------------------------------------------------------------------------------ monitoring */

const MONITORING: DsbulkSettingDef[] = [
  def({
    path: 'monitoring.reportRate',
    group: 'monitoring',
    label: 'Report rate',
    kind: 'string',
    upstreamDefault: '5 seconds',
    help: 'How often progress is reported.',
  }),
  def({
    path: 'monitoring.rateUnit',
    group: 'monitoring',
    label: 'Rate unit',
    kind: 'enum',
    enumValues: ['NANOSECONDS', 'MICROSECONDS', 'MILLISECONDS', 'SECONDS', 'MINUTES', 'HOURS'],
    upstreamDefault: 'SECONDS',
    help: 'Unit throughput is expressed in.',
  }),
  def({
    path: 'monitoring.durationUnit',
    group: 'monitoring',
    label: 'Duration unit',
    kind: 'enum',
    enumValues: ['NANOSECONDS', 'MICROSECONDS', 'MILLISECONDS', 'SECONDS', 'MINUTES', 'HOURS'],
    upstreamDefault: 'MILLISECONDS',
    help: 'Unit latencies are expressed in.',
  }),
  def({
    path: 'monitoring.expectedWrites',
    group: 'monitoring',
    label: 'Expected writes',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Expected write count, used for the progress percentage. -1 to disable.',
  }),
  def({
    path: 'monitoring.expectedReads',
    group: 'monitoring',
    label: 'Expected reads',
    kind: 'number',
    upstreamDefault: '-1',
    help: 'Expected read count, used for the progress percentage. -1 to disable.',
  }),
  def({
    path: 'monitoring.trackBytes',
    group: 'monitoring',
    label: 'Track bytes',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Measure throughput in bytes as well as rows.',
  }),
  def({
    path: 'monitoring.jmx',
    group: 'monitoring',
    label: 'JMX metrics',
    kind: 'boolean',
    // DSBulk enables JMX by default; cassyx derives it OFF for the embedded process.
    upstreamDefault: 'true',
    help: 'Expose metrics over JMX from the DSBulk process. On by default upstream; cassyx turns it off for the embedded runner, which nothing attaches to.',
  }),
  def({
    path: 'monitoring.csv',
    group: 'monitoring',
    label: 'CSV metrics',
    kind: 'boolean',
    upstreamDefault: 'false',
    help: 'Write periodic metrics as CSV into the log directory.',
  }),
  def({
    path: 'monitoring.console',
    group: 'monitoring',
    label: 'Console metrics',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'Print progress to the process console — this is what cassyx tails.',
  }),
];

/* ---------------------------------------------------------------------------------- driver */

const DRIVER: DsbulkSettingDef[] = [
  def({
    path: 'driver.basic.requestConsistency',
    dsbulkPath: 'driver.basic.request.consistency',
    group: 'driver',
    label: 'Request consistency',
    kind: 'enum',
    enumValues: CONSISTENCY_LEVELS,
    upstreamDefault: 'LOCAL_ONE',
    help: 'Auto-derived: LOCAL_ONE for unload, LOCAL_QUORUM for load, LOCAL_ONE for Amazon Keyspaces.',
  }),
  def({
    path: 'driver.basic.requestSerialConsistency',
    dsbulkPath: 'driver.basic.request.serial-consistency',
    group: 'driver',
    label: 'Serial consistency',
    kind: 'enum',
    enumValues: SERIAL_CONSISTENCY_LEVELS,
    upstreamDefault: 'LOCAL_SERIAL',
    help: 'Applies to the Paxos phase of lightweight transactions only.',
  }),
  def({
    path: 'driver.basic.requestTimeout',
    dsbulkPath: 'driver.basic.request.timeout',
    group: 'driver',
    label: 'Request timeout',
    kind: 'string',
    upstreamDefault: '5 minutes',
    help: 'Per-request timeout, as a duration such as `30 seconds`.',
  }),
  def({
    path: 'driver.basic.requestPageSize',
    dsbulkPath: 'driver.basic.request.page-size',
    group: 'driver',
    label: 'Page size',
    kind: 'number',
    upstreamDefault: '5000',
    help: 'Rows fetched per page.',
  }),
  def({
    path: 'driver.basic.requestDefaultIdempotence',
    dsbulkPath: 'driver.basic.request.default-idempotence',
    group: 'driver',
    label: 'Default idempotence',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'Whether statements may be retried on another node. Required for speculative execution.',
  }),
  def({
    path: 'driver.basic.sessionName',
    dsbulkPath: 'driver.basic.session-name',
    group: 'driver',
    label: 'Session name',
    kind: 'string',
    upstreamDefault: 'cassyx-bulk',
    help: 'Session name used in driver logs and metrics.',
  }),
  def({
    path: 'driver.advanced.protocolCompression',
    dsbulkPath: 'driver.advanced.protocol.compression',
    group: 'driver',
    label: 'Protocol compression',
    kind: 'enum',
    enumValues: ['none', 'lz4', 'snappy'],
    // `none` is UPSTREAM's default. `lz4` is the value cassyx derives — a different thing.
    upstreamDefault: 'none',
    help: 'Native-protocol compression. DSBulk defaults to none; cassyx derives lz4.',
  }),
  def({
    path: 'driver.advanced.connectionPoolLocalSize',
    dsbulkPath: 'driver.advanced.connection.pool.local.size',
    group: 'driver',
    label: 'Local pool size',
    kind: 'number',
    upstreamDefault: '8',
    help: 'Connections per node in the local datacenter.',
  }),
  def({
    path: 'driver.advanced.connectionPoolRemoteSize',
    dsbulkPath: 'driver.advanced.connection.pool.remote.size',
    group: 'driver',
    label: 'Remote pool size',
    kind: 'number',
    upstreamDefault: '8',
    help: 'Connections per node in remote datacenters.',
  }),
  def({
    path: 'driver.advanced.retryPolicyClass',
    dsbulkPath: 'driver.advanced.retry-policy.class',
    group: 'driver',
    label: 'Retry policy class',
    kind: 'string',
    // The contract's example names `...workflow.api.utils.MultipleRetryPolicy`, which does not
    // exist in the 1.11 distribution. This is the real class.
    upstreamDefault: 'com.datastax.oss.dsbulk.workflow.commons.policies.retry.MultipleRetryPolicy',
    help: 'Fully-qualified retry policy implementation.',
  }),
  def({
    path: 'driver.advanced.maxRetries',
    dsbulkPath: 'driver.advanced.retry-policy.max-retries',
    group: 'driver',
    label: 'Max retries',
    kind: 'number',
    upstreamDefault: '10',
    help: 'Retries per statement before the record is rejected.',
  }),
  def({
    path: 'driver.advanced.heartbeatInterval',
    dsbulkPath: 'driver.advanced.heartbeat.interval',
    group: 'driver',
    label: 'Heartbeat interval',
    kind: 'string',
    upstreamDefault: '30 seconds',
    help: 'Keep-alive interval on idle connections.',
  }),
  def({
    path: 'driver.advanced.metadataSchemaEnabled',
    dsbulkPath: 'driver.advanced.metadata.schema.enabled',
    group: 'driver',
    label: 'Schema metadata',
    kind: 'boolean',
    upstreamDefault: 'true',
    help: 'Keep schema metadata in sync. Required for token-aware routing and mapping inference.',
  }),
];

/* -------------------------------------------------------------------------------------- s3 */

/**
 * DSBulk 1.11 models exactly ONE `s3.*` setting — `s3.clientCacheSize`. Region, profile and
 * credentials are query parameters on the `s3://` URL itself
 * (`s3://bucket/key?region=eu-west-1&profile=default`), and `region` is mandatory on every S3 URL.
 * `sessionToken` and `endpoint` have no upstream equivalent at all.
 *
 * The fields stay because the contract models them and the server maps them onto the URL, but each
 * one says so in its help text — otherwise the first person to override an S3 endpoint loses an
 * afternoon to a setting that is silently ignored (Typesafe Config accepts unknown keys without
 * complaint).
 */
const S3_URL_PARAMETER_NOTE =
  'Not a DSBulk setting: the server appends it to the `s3://` URL as a query parameter.';

const S3: DsbulkSettingDef[] = [
  def({
    path: 's3.clientCacheSize',
    group: 's3',
    label: 'Client cache size',
    kind: 'number',
    upstreamDefault: '20',
    help: 'Cached S3 clients, one per distinct region/credential combination. The only real `s3.*` DSBulk setting.',
  }),
  def({
    path: 's3.region',
    group: 's3',
    label: 'Region',
    kind: 'string',
    upstreamDefault: '',
    help: `AWS region of the bucket, e.g. eu-west-1. REQUIRED for every S3 URL. ${S3_URL_PARAMETER_NOTE}`,
  }),
  def({
    path: 's3.profile',
    group: 's3',
    label: 'Profile',
    kind: 'string',
    upstreamDefault: 'default',
    help: `Named profile from the server’s AWS credentials file. ${S3_URL_PARAMETER_NOTE}`,
  }),
  def({
    path: 's3.accessKeyId',
    group: 's3',
    label: 'Access key ID',
    kind: 'string',
    upstreamDefault: '',
    help: `Write-only. Stored encrypted server-side and masked in the command preview. ${S3_URL_PARAMETER_NOTE}`,
    secret: true,
  }),
  def({
    path: 's3.secretAccessKey',
    group: 's3',
    label: 'Secret access key',
    kind: 'string',
    upstreamDefault: '',
    help: `Write-only. Never returned by the API and never rendered back into this form. ${S3_URL_PARAMETER_NOTE}`,
    secret: true,
  }),
  def({
    path: 's3.sessionToken',
    group: 's3',
    label: 'Session token',
    kind: 'string',
    upstreamDefault: '',
    help: 'Write-only, for temporary STS credentials. NOT SUPPORTED by DSBulk 1.11 — it has neither a setting nor a URL parameter for a session token, so temporary credentials must be supplied to the server’s AWS profile instead.',
    secret: true,
  }),
  def({
    path: 's3.endpoint',
    group: 's3',
    label: 'Endpoint',
    kind: 'string',
    upstreamDefault: '',
    help: 'Custom S3-compatible endpoint (MinIO, Ceph, …). NOT SUPPORTED by DSBulk 1.11 — there is no upstream endpoint override, so an S3-compatible store has to be reached another way.',
  }),
];

/* ----------------------------------------------------------------------------------- stats */

const STATS: DsbulkSettingDef[] = [
  def({
    path: 'stats.modes',
    group: 'stats',
    label: 'Statistics modes',
    kind: 'stringList',
    // The alias is accepted so a template written against the contract still validates, but it is
    // not a fifth report: the server folds `biggest-partitions` into `partitions`.
    enumValues: [...DSBULK_STATS_MODES, ...DSBULK_STATS_MODE_ALIASES],
    upstreamDefault: 'global',
    help: 'Which count reports to produce: global, ranges, hosts, partitions. `partitions` IS the largest-partitions report, sized by the setting below; `biggest-partitions` is an accepted alias for it, not a separate mode.',
    simple: true,
  }),
  def({
    path: 'stats.numPartitions',
    group: 'stats',
    label: 'Top partitions',
    kind: 'number',
    upstreamDefault: '10',
    help: 'Top-N for the `partitions` report. Has no effect unless that mode is selected.',
    simple: true,
  }),
];

/** Every modelled DSBulk setting, in group order. */
export const DSBULK_SETTINGS: readonly DsbulkSettingDef[] = [
  ...CONNECTOR,
  ...SCHEMA,
  ...BATCH,
  ...CODEC,
  ...ENGINE,
  ...EXECUTOR,
  ...LOG,
  ...MONITORING,
  ...DRIVER,
  ...S3,
  ...STATS,
];

const BY_PATH: ReadonlyMap<string, DsbulkSettingDef> = new Map(
  DSBULK_SETTINGS.flatMap((setting) => {
    const entries: [string, DsbulkSettingDef][] = [[setting.path, setting]];
    // The server may report a derived setting under DSBulk's own path.
    if (setting.dsbulkPath) entries.push([setting.dsbulkPath, setting]);
    return entries;
  }),
);

/** Look a setting up by contract path OR by its DSBulk configuration path. */
export function findSetting(path: string): DsbulkSettingDef | undefined {
  return BY_PATH.get(path);
}

export function settingsForGroup(group: DsbulkSettingGroup): DsbulkSettingDef[] {
  return DSBULK_SETTINGS.filter((setting) => setting.group === group);
}

export function simpleSettings(): DsbulkSettingDef[] {
  return DSBULK_SETTINGS.filter((setting) => setting.simple === true);
}

/** Paths that must never be echoed back to the user or logged. */
export const SECRET_SETTING_PATHS: readonly string[] = DSBULK_SETTINGS.filter(
  (setting) => setting.secret === true,
).map((setting) => setting.path);

export function isSecretPath(path: string): boolean {
  return findSetting(path)?.secret === true;
}
