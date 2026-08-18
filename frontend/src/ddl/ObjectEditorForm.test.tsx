import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { ObjectEditorForm } from './ObjectEditorForm';
import type { DdlAction, DdlObjectType } from '../schema/schemaTypes';

function Harness({
  objectType,
  action,
  initial = {},
  onValue,
}: {
  objectType: DdlObjectType;
  action: DdlAction;
  initial?: Record<string, unknown>;
  onValue?: (value: Record<string, unknown>) => void;
}) {
  const [value, setValue] = useState<Record<string, unknown>>(initial);
  return (
    <ObjectEditorForm
      objectType={objectType}
      action={action}
      value={value}
      onChange={(next) => {
        setValue(next);
        onValue?.(next);
      }}
    />
  );
}

describe('ObjectEditorForm', () => {
  it('edits a keyspace with a per-DC replication factor picker', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness objectType="KEYSPACE" action="CREATE" onValue={(value) => (latest = value)} />,
    );

    await user.type(screen.getByLabelText('Name'), 'demo');
    await user.selectOptions(
      screen.getByLabelText('Replication strategy'),
      'NetworkTopologyStrategy',
    );
    await user.type(screen.getByLabelText('Per-datacenter replication'), 'dc1:3, dc2:2');

    expect(latest.name).toBe('demo');
    expect(latest.replication).toEqual({
      strategy: 'NetworkTopologyStrategy',
      datacenters: { dc1: 3, dc2: 2 },
    });
  });

  it('keeps SimpleStrategy on a single replication factor', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness
        objectType="KEYSPACE"
        action="CREATE"
        initial={{ replication: { strategy: 'SimpleStrategy', replicationFactor: 1 } }}
        onValue={(value) => (latest = value)}
      />,
    );

    await user.clear(screen.getByLabelText('Replication factor'));
    await user.type(screen.getByLabelText('Replication factor'), '3');
    expect(latest.replication).toEqual({ strategy: 'SimpleStrategy', replicationFactor: 3 });
  });

  it(
    'builds a table column list with static columns and a composite primary key',
    { timeout: 20000 },
    async () => {
      const user = userEvent.setup();
      let latest: Record<string, unknown> = {};
      renderWithProviders(
        <Harness objectType="TABLE" action="CREATE" onValue={(value) => (latest = value)} />,
      );

      await user.click(screen.getByLabelText('Add Columns'));
      await user.type(screen.getByLabelText('Columns 1 name'), 'tenant');
      await user.type(screen.getByLabelText('Columns 1 type'), 'text');
      await user.click(screen.getByLabelText('Columns 1 static'));

      await user.type(screen.getByLabelText('Partition key'), 'tenant, day');
      await user.type(screen.getByLabelText('Clustering key'), 'created_at DESC');

      expect(latest.columns).toEqual([{ name: 'tenant', type: 'text', static: true }]);
      expect(latest.primaryKey).toEqual({
        partitionKey: ['tenant', 'day'],
        clusteringKey: [{ column: 'created_at', order: 'DESC' }],
      });
    },
  );

  it('removes a column row', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness
        objectType="TABLE"
        action="CREATE"
        initial={{ columns: [{ name: 'a', type: 'text' }] }}
        onValue={(value) => (latest = value)}
      />,
    );

    await user.click(screen.getByLabelText('Remove Columns 1'));
    expect(latest.columns).toEqual([]);
  });

  it('warns inline when a column type is a vector', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness objectType="COLUMN" action="CREATE" />);

    await user.type(screen.getByLabelText('CQL type'), 'vector<float, 1536>');
    expect(await screen.findByText(/require Cassandra 5\.x/)).toBeInTheDocument();
  });

  it('toggles permissions as chips', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness objectType="PERMISSION" action="GRANT" onValue={(value) => (latest = value)} />,
    );

    await user.click(screen.getByText('SELECT'));
    await user.click(screen.getByText('MODIFY'));
    expect(latest.permissions).toEqual(['SELECT', 'MODIFY']);

    await user.click(screen.getByText('SELECT'));
    expect(latest.permissions).toEqual(['MODIFY']);
  });

  it('offers rename and retype fields when altering a column', () => {
    renderWithProviders(<Harness objectType="COLUMN" action="ALTER" />);
    expect(screen.getByLabelText('New name')).toBeInTheDocument();
    expect(screen.getByLabelText('New CQL type')).toBeInTheDocument();
    expect(screen.queryByLabelText('Static column')).not.toBeInTheDocument();
  });

  it('edits numeric and boolean table options', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness objectType="TABLE" action="CREATE" onValue={(value) => (latest = value)} />,
    );

    await user.type(screen.getByLabelText('gc_grace_seconds'), '3600');
    await user.click(screen.getByLabelText('IF NOT EXISTS'));

    expect(latest.gcGraceSeconds).toBe(3600);
    expect(latest.ifNotExists).toBe(true);
  });

  it('takes comma-separated tags for aggregate argument types', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness objectType="AGGREGATE" action="CREATE" onValue={(value) => (latest = value)} />,
    );

    await user.type(screen.getByLabelText('Argument types'), 'double, int');
    expect(latest.argumentTypes).toEqual(['double', 'int']);
  });

  it('renders the UDF editor with a body and null-handling selector', () => {
    renderWithProviders(<Harness objectType="FUNCTION" action="CREATE" />);
    expect(screen.getByLabelText('Body')).toBeInTheDocument();
    expect(screen.getByLabelText('Null handling')).toBeInTheDocument();
  });

  it('renders the materialized-view editor with a base table and WHERE clause', () => {
    renderWithProviders(<Harness objectType="MATERIALIZED_VIEW" action="CREATE" />);
    expect(screen.getByLabelText('Base table')).toBeInTheDocument();
    expect(screen.getByLabelText('WHERE clause')).toBeInTheDocument();
    expect(screen.getByLabelText('Selected columns')).toBeInTheDocument();
  });

  it('renders the index editor with a kind selector', () => {
    renderWithProviders(<Harness objectType="INDEX" action="CREATE" />);
    expect(screen.getByLabelText('Index kind')).toBeInTheDocument();
    expect(screen.getByLabelText('Target')).toBeInTheDocument();
  });

  it('renders the UDT editor field rows', async () => {
    const user = userEvent.setup();
    let latest: Record<string, unknown> = {};
    renderWithProviders(
      <Harness objectType="TYPE" action="CREATE" onValue={(value) => (latest = value)} />,
    );

    await user.click(screen.getByLabelText('Add Fields'));
    await user.type(screen.getByLabelText('Fields 1 name'), 'street');
    await user.type(screen.getByLabelText('Fields 1 type'), 'text');
    expect(latest.fields).toEqual([{ name: 'street', type: 'text' }]);
  });

  it('renders the role editor including the write-only password note', () => {
    renderWithProviders(<Harness objectType="ROLE" action="CREATE" />);
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByText(/redacted from the result/)).toBeInTheDocument();
  });
});
