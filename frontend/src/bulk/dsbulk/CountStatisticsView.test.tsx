import { describe, expect, it } from 'vitest';
import { screen, within } from '@testing-library/react';
import { renderWithProviders } from '../../test/render';
import { CountStatisticsView, type TableStatistics } from './CountStatisticsView';

const STATISTICS: TableStatistics = {
  identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
  totalRows: 10_000_000,
  partitionCount: 250_000,
  computedAt: '2026-08-17T11:02:33Z',
  jobId: '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44',
  durationMillis: 21_400,
  perReplica: [
    { endpoint: '127.0.0.1:9042', datacenter: 'datacenter1', rows: 3_333_334 },
    { endpoint: '127.0.0.2:9042', rows: 3_333_333 },
  ],
  perTokenRange: [
    {
      start: '-9223372036854775808',
      end: '-3074457345618258603',
      rows: 3_333_333,
      replicas: ['127.0.0.1:9042'],
    },
    { start: '-3074457345618258603', end: '3074457345618258602', rows: 3_333_333 },
  ],
  largestPartitions: [
    { partitionKey: 'user_id=1a2b3c4d', rows: 412_339, sizeBytes: 89_123_456 },
    { partitionKey: 'user_id=deadbeef', rows: 12 },
  ],
};

describe('CountStatisticsView', () => {
  it('invites a count job when there is nothing to show', () => {
    renderWithProviders(<CountStatisticsView statistics={undefined} />);
    expect(screen.getByTestId('statistics-empty')).toBeInTheDocument();
  });

  it('shows a loading state while the count job runs', () => {
    renderWithProviders(<CountStatisticsView statistics={undefined} loading />);
    expect(screen.getByTestId('statistics-loading')).toBeInTheDocument();
  });

  it('summarises the totals', () => {
    renderWithProviders(<CountStatisticsView statistics={STATISTICS} />);
    expect(screen.getByTestId('total-rows')).toHaveTextContent('10 000 000');
    expect(screen.getByTestId('partition-count')).toHaveTextContent('250 000');
    expect(screen.getByTestId('duration')).toHaveTextContent('21 400 ms');
    expect(screen.getByTestId('computed-at')).toHaveTextContent('2026-08-17T11:02:33Z');
  });

  it('lists rows per replica, tolerating a missing datacenter', () => {
    renderWithProviders(<CountStatisticsView statistics={STATISTICS} />);
    const table = screen.getByTestId('per-replica');
    expect(within(table).getByText('datacenter1')).toBeInTheDocument();
    expect(within(table).getByText('—')).toBeInTheDocument();
    expect(within(table).getByText('3 333 334')).toBeInTheDocument();
  });

  it('renders token boundaries verbatim — they exceed the JS safe-integer range', () => {
    renderWithProviders(<CountStatisticsView statistics={STATISTICS} />);
    const table = screen.getByTestId('per-token-range');
    // Number('-9223372036854775808') would render -9223372036854776000.
    expect(within(table).getByText('-9223372036854775808')).toBeInTheDocument();
    expect(within(table).getAllByText('-3074457345618258603')).toHaveLength(2);
    expect(table.textContent).not.toContain('9223372036854776000');
  });

  it('lists the top-N largest partitions and their sizes', () => {
    renderWithProviders(<CountStatisticsView statistics={STATISTICS} />);
    const table = screen.getByTestId('largest-partitions');
    expect(within(table).getByText('user_id=1a2b3c4d')).toBeInTheDocument();
    expect(within(table).getByText('89 123 456')).toBeInTheDocument();
    expect(within(table).getByText('—')).toBeInTheDocument();
  });

  it('explains which statistics mode is missing rather than showing an empty table', () => {
    renderWithProviders(
      <CountStatisticsView
        statistics={{
          identity: { kind: 'TABLE', keyspace: 'demo', table: 'users' },
          totalRows: 42,
          computedAt: '2026-08-17T11:02:33Z',
          jobId: '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44',
        }}
      />,
    );

    expect(screen.queryByTestId('per-replica')).not.toBeInTheDocument();
    expect(screen.getAllByText(/enable the/)).toHaveLength(3);
    expect(screen.getByText('hosts')).toBeInTheDocument();
    expect(screen.getByText('ranges')).toBeInTheDocument();
    expect(screen.getByText('biggest-partitions')).toBeInTheDocument();
    expect(screen.queryByTestId('partition-count')).not.toBeInTheDocument();
  });

  it('hides the partitions tile when the server reports no total — it does not invent one', () => {
    // DSBulk has no total-partitions figure. The field used to carry the size of the top-N list, so
    // the tile read "10" on every table that had ever been counted.
    renderWithProviders(
      <CountStatisticsView statistics={{ ...STATISTICS, partitionCount: null }} />,
    );
    expect(screen.queryByTestId('partition-count')).not.toBeInTheDocument();
    expect(screen.getByTestId('total-rows')).toHaveTextContent('10 000 000');
  });

  it('says a section was capped instead of silently showing a short list', () => {
    renderWithProviders(
      <CountStatisticsView
        statistics={{
          ...STATISTICS,
          perTokenRangeTruncated: true,
          perTokenRangeReported: 3072,
          perReplicaTruncated: false,
          perReplicaReported: 2,
        }}
      />,
    );

    expect(screen.getByTestId('per-token-range-truncated')).toHaveTextContent('3072');
    expect(screen.queryByTestId('per-replica-truncated')).not.toBeInTheDocument();
  });
});
