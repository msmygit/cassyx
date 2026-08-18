/**
 * CQL language support for CodeMirror 6.
 *
 * `@codemirror/lang-sql` is dialect-driven, so rather than pretending CQL is standard SQL we define
 * the dialect: CQL keywords (`ALLOW FILTERING`, `USING TTL`, `IF NOT EXISTS`, `ANN OF`, `BATCH`),
 * CQL types (`timeuuid`, `varint`, `counter`, `frozen`, `vector`) and CQL's comment syntax — which
 * includes `--` *and* `//`.
 *
 * Autocomplete is schema-driven: `keyspace.table` → its column names, so completion is real rather
 * than a fixed word list.
 */
import { SQLDialect, sql } from '@codemirror/lang-sql';
import { EditorView } from '@codemirror/view';
import type { Extension } from '@codemirror/state';

/** Reserved and non-reserved CQL keywords, upper-cased by convention. */
export const CQL_KEYWORDS = [
  'add allow alter and ann apply as asc authorize batch begin by called cast clustering',
  'columnfamily compact contains count create custom default delete desc describe distinct drop',
  'entries exists filtering finalfunc from frozen full function grant group if in index infinity',
  'initcond input insert into is json key keys keyspace language like limit list logged',
  'materialized mbean modify nan nologin norecursive nosuperuser not null of on options or order',
  'partition password per permission permissions primary rename replace restrict returns revoke',
  'role roles select set sfunc static storage stype superuser table to token trigger truncate ttl',
  'tuple type unlogged unset update use user users using values view where with writetime',
].join(' ');

/** CQL data types, including the ones plan §6 makes first-class. */
export const CQL_TYPES = [
  'ascii bigint blob boolean counter date decimal double duration float inet int list map',
  'set smallint text time timestamp timeuuid tinyint tuple uuid varchar varint vector',
].join(' ');

export const CQL_BUILTINS = [
  'count min max sum avg token ttl writetime now uuid dateof unixtimestampof todate',
  'totimestamp tounixtimestamp similarity_cosine similarity_dot_product similarity_euclidean',
].join(' ');

/**
 * The CQL dialect.
 *
 * `doubleDollarQuotedStrings` matters: `$$ … $$` is how UDF bodies are written, and without it the
 * editor mis-highlights every semicolon inside one — the same class of bug the server-side lexer
 * exists to avoid.
 */
export const CQL = SQLDialect.define({
  keywords: CQL_KEYWORDS,
  types: CQL_TYPES,
  builtin: CQL_BUILTINS,
  backslashEscapes: false,
  doubleDollarQuotedStrings: true,
  hashComments: false,
  slashComments: true,
  identifierQuotes: '"',
});

/** `keyspace.table` → column names, in the shape `@codemirror/lang-sql` wants for completion. */
export type CqlCompletionSchema = Record<string, string[]>;

export interface CqlEditorOptions {
  schema?: CqlCompletionSchema;
  /** Fully-qualified default table, so bare column names complete too. */
  defaultTable?: string;
  /** Keyspace of the active session, used to resolve unqualified table names. */
  defaultSchema?: string;
}

export function cqlExtensions(options: CqlEditorOptions = {}): Extension[] {
  return [
    sql({
      dialect: CQL,
      upperCaseKeywords: true,
      schema: options.schema,
      defaultTable: options.defaultTable,
      defaultSchema: options.defaultSchema,
    }),
    EditorView.lineWrapping,
  ];
}

/**
 * Builds the completion schema from a schema tree payload.
 *
 * Keys are fully qualified (`demo.users`) because an unqualified table name is ambiguous across
 * keyspaces — which is precisely the confusion behind the prior art's `system_auth.users` bug.
 */
export function buildCompletionSchema(
  tables: { keyspace: string; table: string; columns: string[] }[],
): CqlCompletionSchema {
  const schema: CqlCompletionSchema = {};
  for (const entry of tables) {
    schema[`${entry.keyspace}.${entry.table}`] = entry.columns;
  }
  return schema;
}
