import Typography from '@mui/material/Typography';
import { PanelPlaceholder } from './PanelPlaceholder';

/**
 * Vector / SAI / ANN panel (plan §6). SHELL ONLY.
 *
 * Vector support is first-class in v1, not an afterthought: `vector<float, N>` columns, SAI index
 * management with a similarity-function choice, and an ANN query builder producing
 * `SELECT … ORDER BY <col> ANN OF [...] LIMIT k`.
 */
export function VectorPanel() {
  return (
    <PanelPlaceholder
      title="Vector & ANN"
      section="§6"
      workstream="F"
      testId="vector-panel"
      todo={[
        'vector<float, N> column editor with dimension input',
        'SAI index lifecycle: similarity_function cosine | dot_product | euclidean',
        'ANN builder: pick column → paste/upload query vector or reference a row → LIMIT k',
        'Hybrid SAI + ANN statements; similarity_* projections as a sortable score column',
        'Sparkline + dimension badge rendering, expandable inspector',
        'Guards: driver 4.19.0 for describe (CASSJAVA-2); round-trip fidelity vs CASSANDRA-19333',
      ]}
    >
      <Typography variant="body2" color="text.secondary">
        Requires a cluster with vector support (Cassandra 5.x or Astra). The capability probe (§7.1)
        hides this panel with an explanatory tooltip on clusters that cannot do ANN, rather than
        showing it broken.
      </Typography>
    </PanelPlaceholder>
  );
}
