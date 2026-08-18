import { useMemo, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import CircularProgress from '@mui/material/CircularProgress';
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
  redactSecrets,
  toConnectionRequest,
  validateConnection,
  type ConnectionFormState,
  type ValidationErrors,
} from './connectionModel';
import { saveConnection, type SaveConnectionResult } from './saveConnection';
import { testConnection, type ConnectionTestResult } from './connectionsApi';

export interface ConnectionDialogProps {
  open: boolean;
  onClose: () => void;
  /**
   * Override the default persistence. Left unset, the dialog talks to the real API: create (or
   * update), attach the secure connect bundle, then connect.
   */
  onSave?: (form: ConnectionFormState, bundleFile: File | null) => void | Promise<void>;
  /** Present when editing an existing connection. */
  connectionId?: string;
  initial?: ConnectionFormState;
  astraApi?: AstraApi;
  /** Injectable for tests; defaults to the real endpoints. */
  saveFn?: typeof saveConnection;
  testFn?: typeof testConnection;
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
  connectionId,
  initial,
  astraApi,
  saveFn = saveConnection,
  testFn = testConnection,
}: ConnectionDialogProps) {
  const [form, setForm] = useState<ConnectionFormState>(() => initial ?? emptyConnectionForm());
  const [bundleFile, setBundleFile] = useState<File | null>(null);
  const [submitted, setSubmitted] = useState(false);
  const [busy, setBusy] = useState<'saving' | 'testing' | null>(null);
  const [serverError, setServerError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);

  const errors: ValidationErrors = useMemo(() => validateConnection(form), [form]);
  const visibleErrors = submitted ? errors : {};
  const valid = Object.keys(errors).length === 0;

  /**
   * SECURITY: server messages are pushed through `redactSecrets` before display. The backend does
   * not echo credentials, but the prior art rendered the Astra token in plaintext and this is the
   * path that would reintroduce it.
   */
  const reportError = (error: unknown, fallback: string) => {
    const message = error instanceof Error && error.message ? error.message : fallback;
    setServerError(redactSecrets(message));
  };

  const handleSave = async () => {
    setSubmitted(true);
    setServerError(null);
    if (!valid) return;
    setBusy('saving');
    try {
      if (onSave) {
        await onSave(form, bundleFile);
      } else {
        const result: SaveConnectionResult = await saveFn({
          form,
          connectionId,
          bundleFile,
          connect: true,
        });
        void result;
      }
      onClose();
    } catch (error) {
      reportError(error, 'Could not save the connection.');
    } finally {
      setBusy(null);
    }
  };

  /**
   * Probe without saving. A failed probe still resolves — `success` carries the answer — so the
   * diagnostic reaches the user instead of being swallowed by generic error handling.
   */
  const handleTest = async () => {
    setSubmitted(true);
    setServerError(null);
    setTestResult(null);
    if (!valid) return;
    setBusy('testing');
    try {
      setTestResult(
        await testFn(
          connectionId ? { connectionId } : { connection: toConnectionRequest(form) },
        ),
      );
    } catch (error) {
      reportError(error, 'Could not reach the cassyx server.');
    } finally {
      setBusy(null);
    }
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
              {...(connectionId ? { connectionId } : {})}
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

          {serverError && (
            <Alert severity="error" data-testid="connection-error">
              {serverError}
            </Alert>
          )}

          {testResult && (
            <Alert
              severity={testResult.success ? 'success' : 'error'}
              data-testid="connection-test-result"
            >
              {testResult.success
                ? `Connected to ${testResult.clusterName ?? 'the cluster'} (${
                    testResult.releaseVersion ?? 'unknown version'
                  }) in ${testResult.elapsedMillis} ms.`
                : redactSecrets(
                    testResult.problem?.detail ??
                      testResult.problem?.title ??
                      'The cluster could not be reached.',
                  )}
            </Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy !== null}>
          Cancel
        </Button>
        <Button
          onClick={() => void handleTest()}
          disabled={busy !== null}
          startIcon={busy === 'testing' ? <CircularProgress size={14} /> : undefined}
          data-testid="connection-test"
        >
          Test connection
        </Button>
        <Button
          variant="contained"
          onClick={() => void handleSave()}
          disabled={busy !== null}
          startIcon={busy === 'saving' ? <CircularProgress size={14} /> : undefined}
          data-testid="connection-save"
        >
          Save &amp; connect
        </Button>
      </DialogActions>
    </Dialog>
  );
}
