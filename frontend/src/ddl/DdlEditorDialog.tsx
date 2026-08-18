import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Divider from '@mui/material/Divider';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import type {
  CapabilityName,
  DdlAction,
  DdlExecutionResult,
  DdlObjectType,
} from '../schema/schemaTypes';
import { CqlPreviewPane } from './CqlPreviewPane';
import { ObjectEditorForm } from './ObjectEditorForm';
import { availability, objectTypeSpec, type DdlTarget } from './ddlModel';
import { toDefinition } from './editorFields';
import { useDdlPreview } from './useDdlPreview';

export interface DdlEditorDialogProps {
  open: boolean;
  onClose: () => void;
  connectionId: string;
  objectType: DdlObjectType;
  action: DdlAction;
  target: DdlTarget;
  /** Cluster capabilities, so unsupported object types explain themselves instead of erroring. */
  capabilities?: readonly CapabilityName[];
  /** Prefilled form state, e.g. when editing an existing object. */
  initialValue?: Record<string, unknown>;
  onExecuted?: (result: DdlExecutionResult) => void;
}

/**
 * A visual editor over a Preview CQL pane — the shape every object type gets (plan §4).
 *
 * The dialog cannot execute anything the pane is not showing: `useDdlPreview` runs exactly the
 * text in the textarea, whether it came from the generator or from the user's own edit.
 */
export function DdlEditorDialog({
  open,
  onClose,
  connectionId,
  objectType,
  action,
  target,
  capabilities,
  initialValue,
  onExecuted,
}: DdlEditorDialogProps) {
  const spec = objectTypeSpec(objectType);
  const gate = availability(spec, capabilities);
  const [form, setForm] = useState<Record<string, unknown>>(() => initialValue ?? {});

  const definition = useMemo(
    () => toDefinition(objectType, action, form),
    [objectType, action, form],
  );

  const preview = useDdlPreview({
    connectionId,
    objectType,
    action,
    target,
    definition,
    onExecuted: (result) => {
      onExecuted?.(result);
    },
  });

  const title = `${verb(action)} ${spec.label.toLowerCase()}`;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent dividers>
        {!gate.available ? (
          <Alert severity="info" data-testid="capability-unavailable">
            {gate.reason}
          </Alert>
        ) : (
          <Stack spacing={2}>
            <Typography variant="caption" color="text.secondary">
              {scopeLabel(target, spec.clusterScoped)}
            </Typography>

            <ObjectEditorForm
              objectType={objectType}
              action={action}
              value={form}
              onChange={setForm}
            />

            <Divider />

            <CqlPreviewPane
              cql={preview.cql}
              onCqlChange={preview.setCql}
              warnings={preview.warnings}
              problems={preview.problems}
              error={preview.error}
              loading={preview.loading}
              executing={preview.executing}
              onExecute={() => void preview.execute()}
              executeLabel={`Run ${action.toLowerCase()}`}
            />

            {preview.result && (
              <Alert severity="success" data-testid="ddl-result">
                {preview.result.statementsExecuted ?? preview.result.executedCql.length}{' '}
                statement(s) executed
                {preview.result.schemaAgreement === false
                  ? ' — the cluster has not yet agreed on the new schema.'
                  : '.'}
              </Alert>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

function verb(action: DdlAction): string {
  switch (action) {
    case 'CREATE':
      return 'New';
    case 'ALTER':
      return 'Alter';
    case 'DROP':
      return 'Drop';
    case 'TRUNCATE':
      return 'Truncate';
    case 'GRANT':
      return 'Grant';
    case 'REVOKE':
      return 'Revoke';
    default:
      return 'Edit';
  }
}

function scopeLabel(target: DdlTarget, clusterScoped?: boolean): string {
  if (clusterScoped) return 'Cluster-wide';
  if (target.table && target.keyspace) return `${target.keyspace}.${target.table}`;
  if (target.keyspace) return target.keyspace;
  return 'Cluster-wide';
}
