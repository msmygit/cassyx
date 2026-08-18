/**
 * Client-side filter / sort / search over a fetched page (plan §7).
 *
 * Deliberately client-side and page-scoped: it re-orders the rows you already have. It is NOT a
 * substitute for `ORDER BY`, because Cassandra can only sort within a partition, and pretending
 * otherwise would show a "sorted" result that is only sorted within one page.
 */
import { compareValues, cqlKind, renderValue } from './cqlValue';
import type { ColumnMetadata, ResultRow } from './types';

export type SortDirection = 'asc' | 'desc';

export interface SortSpec {
  column: string;
  direction: SortDirection;
}

export interface ResultViewSpec {
  /** Free-text search across every rendered cell. */
  search?: string;
  /** Per-column substring filters. */
  filters?: Record<string, string>;
  sort?: SortSpec | null;
}

function columnOf(columns: ColumnMetadata[], name: string): ColumnMetadata | undefined {
  return columns.find((column) => column.name === name);
}

export function matchesSearch(row: ResultRow, columns: ColumnMetadata[], search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  return columns.some((column) =>
    renderValue(row[column.name], column).toLowerCase().includes(needle),
  );
}

export function matchesFilters(
  row: ResultRow,
  columns: ColumnMetadata[],
  filters: Record<string, string>,
): boolean {
  return Object.entries(filters).every(([name, term]) => {
    const needle = term.trim().toLowerCase();
    if (!needle) return true;
    return renderValue(row[name], columnOf(columns, name)).toLowerCase().includes(needle);
  });
}

/** Applies search, per-column filters and sort, in that order. */
export function applyResultView(
  rows: ResultRow[],
  columns: ColumnMetadata[],
  spec: ResultViewSpec,
): ResultRow[] {
  let out = rows;
  if (spec.search && spec.search.trim()) {
    out = out.filter((row) => matchesSearch(row, columns, spec.search as string));
  }
  if (spec.filters && Object.keys(spec.filters).length > 0) {
    out = out.filter((row) => matchesFilters(row, columns, spec.filters as Record<string, string>));
  }
  if (spec.sort) {
    const { column, direction } = spec.sort;
    const kind = cqlKind(columnOf(columns, column));
    const factor = direction === 'asc' ? 1 : -1;
    out = [...out].sort((a, b) => factor * compareValues(a[column], b[column], kind));
  }
  return out;
}

/** Click-through cycle for a column header: none → asc → desc → none. */
export function nextSort(current: SortSpec | null, column: string): SortSpec | null {
  if (!current || current.column !== column) return { column, direction: 'asc' };
  if (current.direction === 'asc') return { column, direction: 'desc' };
  return null;
}

/**
 * A result set can only be edited if it projects the COMPLETE primary key (plan §7).
 *
 * The server is the authority (`checkRowEditability`), but computing it locally from the column
 * metadata avoids a round trip for the common case and keeps the grid honest offline.
 */
export function projectsFullPrimaryKey(columns: ColumnMetadata[]): boolean {
  const keyColumns = columns.filter((column) => column.primaryKeyColumn);
  return keyColumns.length > 0 && keyColumns.every((column) => column.kind !== null);
}

/** The `WHERE` map for a row, or the names of the key columns it is missing. */
export function primaryKeyOf(
  row: ResultRow,
  columns: ColumnMetadata[],
): { key: Record<string, unknown> } | { missing: string[] } {
  const keyColumns = columns.filter((column) => column.primaryKeyColumn);
  const missing = keyColumns.filter((column) => !(column.name in row)).map((column) => column.name);
  if (keyColumns.length === 0) return { missing: ['<unknown primary key>'] };
  if (missing.length > 0) return { missing };
  const key: Record<string, unknown> = {};
  keyColumns.forEach((column) => {
    key[column.name] = row[column.name];
  });
  return { key };
}

/** CSV of the current view — the grid's export path, bounded by the page the user is looking at. */
export function toCsv(rows: ResultRow[], columns: ColumnMetadata[]): string {
  const escape = (text: string) => (/[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text);
  const header = columns.map((column) => escape(column.name)).join(',');
  const body = rows.map((row) =>
    columns.map((column) => escape(renderValue(row[column.name], column))).join(','),
  );
  return [header, ...body].join('\n');
}
