import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import { AnnQueryBuilderForm } from './AnnQueryBuilderForm';
import { CapabilityGate } from './CapabilityGate';
import { SaiIndexList } from './SaiIndexList';
import { VectorInspector } from './VectorInspector';
import { VectorSparkline } from './VectorSparkline';
import type { ClusterCapabilities, SaiIndex, VectorColumn } from './types';

const VECTOR_1536 = Array.from({ length: 1536 }, (_, i) => Math.sin(i / 40));

const COLUMN: VectorColumn = {
  identity: { kind: 'COLUMN', keyspace: 'demo', table: 'doc_embeddings', column: 'embedding' },
  name: 'embedding',
  dimensions: 3,
  elementType: 'float',
  cqlType: 'vector<float, 3>',
  index: {
    identity: { kind: 'INDEX', keyspace: 'demo', table: 'doc_embeddings', index: 'ann' },
    name: 'ann',
    target: 'embedding',
    options: {},
  },
  similarityFunction: 'cosine',
};

function capabilities(support: 'SUPPORTED' | 'UNSUPPORTED' | 'PARTIAL'): ClusterCapabilities {
  return {
    flavour: 'CASSANDRA',
    probedAt: '2026-08-18T00:00:00Z',
    capabilities: {
      vector: {
        name: 'vector',
        support,
        reason: 'Vector columns and ANN queries require Cassandra 5.x or Astra.',
      },
    },
  } as ClusterCapabilities;
}

describe('VectorSparkline', () => {
  it('renders a sparkline and a dimension badge, never 1536 floats', () => {
    renderWithProviders(<VectorSparkline values={VECTOR_1536} dimensions={1536} />);

    const cell = screen.getByTestId('vector-sparkline');
    expect(within(cell).getByTestId('vector-dimension-badge')).toHaveTextContent('1536d');
    expect(cell.querySelector('path')?.getAttribute('d')).toMatch(/^M/);
    // The literal values must not be in the DOM - that is the whole point of the sparkline.
    expect(cell.textContent).not.toContain(String(VECTOR_1536[10]));
  });

  it('is keyboard-operable when it opens the inspector', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<VectorSparkline values={[1, 2, 3]} onClick={onClick} />);

    const cell = screen.getByTestId('vector-sparkline');
    cell.focus();
    await user.keyboard('{Enter}');
    await user.click(cell);

    expect(onClick).toHaveBeenCalledTimes(2);
  });

  it('renders an empty vector without crashing', () => {
    renderWithProviders(<VectorSparkline values={[]} dimensions={1536} />);
    expect(screen.getByTestId('vector-dimension-badge')).toHaveTextContent('1536d');
  });
});

describe('VectorInspector', () => {
  it('shows magnitude and similarity to the selected row', () => {
    renderWithProviders(
      <VectorInspector values={[3, 4]} comparisonValues={[3, 4]} columnName="embedding" />,
    );

    expect(screen.getByTestId('vector-magnitude')).toHaveTextContent('5');
    const similarity = screen.getByTestId('vector-similarity');
    expect(within(similarity).getByText('cosine')).toBeInTheDocument();
    expect(within(similarity).getByText('euclidean')).toBeInTheDocument();
  });

  it('pages the components rather than rendering every row of a large vector', () => {
    // 96 rows is plenty to prove paging; rendering the full 1536 MUI rows is a jsdom performance
    // test, not a behaviour test, and it times out under a loaded parallel suite.
    const values = VECTOR_1536.slice(0, 96);
    renderWithProviders(<VectorInspector values={values} initialComponentCount={8} />);

    expect(screen.getAllByRole('row')).toHaveLength(8);
    fireEvent.click(screen.getByTestId('vector-show-all'));
    expect(screen.getAllByRole('row')).toHaveLength(96);
  });

  it('explains a dimension mismatch instead of showing a meaningless number', () => {
    renderWithProviders(<VectorInspector values={[1, 2]} comparisonValues={[1, 2, 3]} />);

    expect(screen.queryByTestId('vector-similarity')).not.toBeInTheDocument();
    expect(screen.getByTestId('vector-similarity-mismatch')).toHaveTextContent(
      /3 dimensions, this one has 2/,
    );
  });
});

describe('CapabilityGate (plan §7.1)', () => {
  it('hides the feature with the probe explanation on an unsupported cluster', () => {
    renderWithProviders(
      <CapabilityGate capabilities={capabilities('UNSUPPORTED')} capability="vector">
        <div data-testid="ann-builder">builder</div>
      </CapabilityGate>,
    );

    expect(screen.queryByTestId('ann-builder')).not.toBeInTheDocument();
    expect(screen.getByTestId('capability-unsupported')).toHaveTextContent(
      'require Cassandra 5.x or Astra',
    );
  });

  it('renders children untouched when supported', () => {
    renderWithProviders(
      <CapabilityGate capabilities={capabilities('SUPPORTED')} capability="vector">
        <div data-testid="ann-builder">builder</div>
      </CapabilityGate>,
    );

    expect(screen.getByTestId('ann-builder')).toBeInTheDocument();
    expect(screen.queryByTestId('capability-unsupported')).not.toBeInTheDocument();
  });

  it('shows PARTIAL features with the caveat, and honours a custom fallback', () => {
    const { unmount } = renderWithProviders(
      <CapabilityGate capabilities={capabilities('PARTIAL')} capability="vector">
        <div data-testid="ann-builder">builder</div>
      </CapabilityGate>,
    );
    expect(screen.getByTestId('capability-partial')).toBeInTheDocument();
    unmount();

    renderWithProviders(
      <CapabilityGate
        capabilities={capabilities('UNSUPPORTED')}
        capability="vector"
        fallback={<div data-testid="custom-fallback" />}
      >
        <div />
      </CapabilityGate>,
    );
    expect(screen.getByTestId('custom-fallback')).toBeInTheDocument();
  });
});

describe('SaiIndexList', () => {
  const index: SaiIndex = {
    identity: { kind: 'INDEX', keyspace: 'demo', table: 'doc_embeddings', index: 'ann' },
    name: 'doc_embeddings_ann',
    target: 'embedding',
    vectorIndex: true,
    similarityFunction: 'cosine',
    options: { similarity_function: 'cosine' },
  };

  it('lists indexes with build state and offers alter/drop', () => {
    const onDrop = vi.fn();

    renderWithProviders(
      <SaiIndexList
        indexes={[index]}
        statuses={{
          doc_embeddings_ann: {
            identity: index.identity,
            name: index.name,
            state: 'BUILDING',
            buildProgressPercent: 50,
          },
        }}
        onDrop={onDrop}
      />,
    );

    expect(screen.getByText('doc_embeddings_ann')).toBeInTheDocument();
    expect(screen.getByText('BUILDING 50%')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Drop' }));
    expect(onDrop).toHaveBeenCalledWith(index);
  });

  it('says why ANN is unavailable when there are no indexes', () => {
    renderWithProviders(<SaiIndexList indexes={[]} />);
    expect(screen.getByTestId('sai-index-list-empty')).toHaveTextContent('ANN queries need one');
  });
});

describe('AnnQueryBuilderForm', () => {
  it('will not run until a valid query vector is supplied, and says why', () => {
    const onRun = vi.fn();

    renderWithProviders(
      <AnnQueryBuilderForm
        keyspace="demo"
        table="doc_embeddings"
        columns={[COLUMN]}
        onRun={onRun}
      />,
    );

    expect(screen.getByTestId('ann-builder-problem')).toHaveTextContent('Paste a query vector');
    expect(screen.getByRole('button', { name: 'Run ANN query' })).toBeDisabled();

    // fireEvent, not user.type: userEvent reads `[` and `{` as key descriptors, so a pasted
    // vector literal is exactly the input it cannot take literally.
    fireEvent.change(screen.getByLabelText('Query vector'), {
      target: { value: '[0.1, 0.2, 0.3]' },
    });

    expect(screen.queryByTestId('ann-builder-problem')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Run ANN query' }));

    expect(onRun).toHaveBeenCalledWith(
      expect.objectContaining({
        keyspace: 'demo',
        table: 'doc_embeddings',
        vectorColumn: 'embedding',
        queryVector: { values: [0.1, 0.2, 0.3] },
        similarityProjections: ['cosine'],
      }),
    );
  });

  it('reports a dimension mismatch inline rather than sending a doomed request', () => {
    renderWithProviders(
      <AnnQueryBuilderForm keyspace="demo" table="doc_embeddings" columns={[COLUMN]} />,
    );

    fireEvent.change(screen.getByLabelText('Query vector'), { target: { value: '[0.1, 0.2]' } });

    // Reported as the field's own helper text, next to the input the user got wrong - and the
    // Run button stays disabled because nothing valid was parsed.
    expect(screen.getByText(/but the pasted vector has 2 values/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run ANN query' })).toBeDisabled();
  });

  it('builds a hybrid query with an SAI predicate', () => {
    const onPreview = vi.fn();

    renderWithProviders(
      <AnnQueryBuilderForm
        keyspace="demo"
        table="doc_embeddings"
        columns={[COLUMN]}
        onPreview={onPreview}
      />,
    );

    fireEvent.change(screen.getByLabelText('Query vector'), { target: { value: '[1, 2, 3]' } });
    fireEvent.click(screen.getByTestId('ann-add-predicate'));
    fireEvent.change(screen.getByLabelText('Column'), { target: { value: 'category' } });
    fireEvent.change(screen.getByLabelText('Value'), { target: { value: 'runbook' } });
    fireEvent.click(screen.getByRole('button', { name: 'Preview CQL' }));

    expect(onPreview).toHaveBeenCalledWith(
      expect.objectContaining({
        predicates: [{ column: 'category', operator: '=', value: 'runbook' }],
      }),
    );
  });

  it('shows the generated CQL and any warnings before anything runs', () => {
    renderWithProviders(
      <AnnQueryBuilderForm
        keyspace="demo"
        table="doc_embeddings"
        columns={[COLUMN]}
        preview={{
          cql: 'SELECT * FROM demo.doc_embeddings ORDER BY embedding ANN OF [0.1] LIMIT 3',
          abbreviatedCql: 'SELECT … ORDER BY embedding ANN OF [… 1536 floats …] LIMIT 3',
          warnings: ['Predicate column lang has no SAI index'],
        }}
      />,
    );

    expect(screen.getByTestId('ann-preview-cql')).toHaveTextContent('1536 floats');
    expect(screen.getByText(/lang has no SAI index/)).toBeInTheDocument();
  });

  it('refuses a column with no SAI index', () => {
    renderWithProviders(
      <AnnQueryBuilderForm
        keyspace="demo"
        table="doc_embeddings"
        columns={[{ ...COLUMN, index: null }]}
      />,
    );

    expect(screen.getByTestId('ann-builder-problem')).toHaveTextContent('no SAI index');
  });

  it('accepts a reference row instead of pasted values', () => {
    const onRun = vi.fn();

    renderWithProviders(
      <AnnQueryBuilderForm
        keyspace="demo"
        table="doc_embeddings"
        columns={[COLUMN]}
        onRun={onRun}
      />,
    );

    fireEvent.change(screen.getByLabelText('Reference row primary key'), {
      target: { value: '{"doc_id": "abc"}' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Run ANN query' }));

    expect(onRun).toHaveBeenCalledWith(
      expect.objectContaining({
        queryVector: { fromRow: { primaryKey: { doc_id: 'abc' }, column: 'embedding' } },
      }),
    );
  });
});
