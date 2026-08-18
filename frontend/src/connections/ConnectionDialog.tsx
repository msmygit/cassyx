import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Dialog from '@mui/material/Dialog';
import DialogActions from '@mui/material/DialogActions';
import DialogContent from '@mui/material/DialogContent';
import DialogTitle from '@mui/material/DialogTitle';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import type { ConnectionMode } from '../api/types';
import { AstraConnectionForm } from './AstraConnectionForm';
import type { AstraApi } from './astraApi';
import { SecretField } from './SecretField';
import {
  emptyConnectionForm,
  validateConnection,
  type ConnectionFormState,
  type ValidationErrors,
} from './connectionModel';

export interface ConnectionDialogProps {
  open: boolean;
  onClose: () => void;
  onSave?: (form: ConnectionFormState, bundleFile: File | null) => void | Promise<void>;
  initial?: ConnectionFormState;
  astraApi?: AstraApi;
}

const MODES: { value: ConnectionMode; label: string }[] = [
  { value: 'CASSANDRA', label: 'Cassandra / DSE' },
  { value: 'ASTRA', label: 'Astra DB' },
  { value: 'ADVANCED', label: 'Advanced' },
];

/**
 * Create/edit a connection (plan §3): the three connection modes.
 *
 * This is the shell — persistence, SSH tunnels and SSL/mTLS material are Phase 1 workstream A.
 */
export function ConnectionDialog({
  open,
  onClose,
  onSave,
  initial,
  astraApi,
}: ConnectionDialogProps) {
  const [form, setForm] = useState<ConnectionFormState>(() => initial ?? emptyConnectionForm());
  const [bundleFile, setBundleFile] = useState<File | null>(null);
  const [submitted, setSubmitted] = useState(false);

  const errors: ValidationErrors = useMemo(() => validateConnection(form), [form]);
  const visibleErrors = submitted ? errors : {};
  const valid = Object.keys(errors).length === 0;

  const handleSave = async () => {
    setSubmitted(true);
    if (!valid) return;
    await onSave?.(form, bundleFile);
    onClose();
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>New connection</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <TextField
            label="Connection name"
            value={form.name}
            onChange={(event) => setForm({ ...form, name: event.target.value })}
            error={Boolean(visibleErrors.name)}
            helperText={visibleErrors.name}
            fullWidth
            autoFocus
            slotProps={{ htmlInput: { 'data-testid': 'connection-name' } }}
          />

          <Tabs
            value={form.mode}
            onChange={(_event, mode: ConnectionMode) => setForm({ ...form, mode })}
            variant="fullWidth"
          >
            {MODES.map((mode) => (
              <Tab key={mode.value} value={mode.value} label={mode.label} />
            ))}
          </Tabs>

          {form.mode === 'CASSANDRA' && (
            <Stack spacing={2} data-testid="cassandra-form">
              <TextField
                label="Contact points"
                placeholder="host1:9042, host2:9042"
                value={form.cassandra.contactPoints}
                onChange={(event) =>
                  setForm({
                    ...form,
                    cassandra: { ...form.cassandra, contactPoints: event.target.value },
                  })
                }
                error={Boolean(visibleErrors.contactPoints)}
                helperText={visibleErrors.contactPoints}
                fullWidth
              />
              <TextField
                label="Local datacenter"
                value={form.cassandra.localDatacenter}
                onChange={(event) =>
                  setForm({
                    ...form,
                    cassandra: { ...form.cassandra, localDatacenter: event.target.value },
                  })
                }
                error={Boolean(visibleErrors.localDatacenter)}
                helperText={
                  visibleErrors.localDatacenter ??
                  'Required by the driver’s default load-balancing policy.'
                }
                fullWidth
              />
              <TextField
                label="Username"
                value={form.cassandra.username}
                onChange={(event) =>
                  setForm({
                    ...form,
                    cassandra: { ...form.cassandra, username: event.target.value },
                  })
                }
                fullWidth
              />
              <SecretField
                label="Password"
                value={form.cassandra.password}
                onValueChange={(password) =>
                  setForm({ ...form, cassandra: { ...form.cassandra, password } })
                }
                fullWidth
                testId="cassandra-password"
              />
              <TextField
                label="Protocol version override (optional)"
                placeholder="V4"
                value={form.cassandra.protocolVersion}
                onChange={(event) =>
                  setForm({
                    ...form,
                    cassandra: { ...form.cassandra, protocolVersion: event.target.value },
                  })
                }
                fullWidth
              />
            </Stack>
          )}

          {form.mode === 'ASTRA' && (
            <AstraConnectionForm
              value={form.astra}
              onChange={(astra) => setForm({ ...form, astra })}
              errors={visibleErrors}
              onBundleFile={setBundleFile}
              {...(astraApi ? { api: astraApi } : {})}
            />
          )}

          {form.mode === 'ADVANCED' && (
            <Stack spacing={1} data-testid="advanced-form">
              <Typography variant="caption" color="text.secondary">
                Raw HOCON <code>application.conf</code>, passed to the driver untouched. Use this
                for exotic setups the two other modes do not cover.
              </Typography>
              <TextField
                multiline
                minRows={10}
                value={form.advanced.applicationConf}
                onChange={(event) =>
                  setForm({ ...form, advanced: { applicationConf: event.target.value } })
                }
                error={Boolean(visibleErrors.applicationConf)}
                helperText={visibleErrors.applicationConf}
                fullWidth
                slotProps={{
                  htmlInput: {
                    spellCheck: false,
                    style: { fontFamily: 'monospace', fontSize: '0.8rem' },
                    'data-testid': 'advanced-conf',
                  },
                }}
              />
            </Stack>
          )}

          {submitted && !valid && (
            <Alert severity="warning">Fix the highlighted fields before saving.</Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => void handleSave()} data-testid="connection-save">
          Save &amp; connect
        </Button>
      </DialogActions>
    </Dialog>
  );
}
