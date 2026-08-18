import { useCallback, useMemo, useState, type ChangeEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Chip from '@mui/material/Chip';
import CircularProgress from '@mui/material/CircularProgress';
import FormControl from '@mui/material/FormControl';
import FormControlLabel from '@mui/material/FormControlLabel';
import FormHelperText from '@mui/material/FormHelperText';
import FormLabel from '@mui/material/FormLabel';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Radio from '@mui/material/Radio';
import RadioGroup from '@mui/material/RadioGroup';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import CloudDownloadRoundedIcon from '@mui/icons-material/CloudDownloadRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import UploadFileRoundedIcon from '@mui/icons-material/UploadFileRounded';
import type {
  AstraBundleDatacenter,
  AstraDatabase,
  ScbAcquisitionMode,
  ScbType,
} from '../api/types';
import { defaultAstraApi, type AstraApi } from './astraApi';
import { SecretField } from './SecretField';
import {
  domainsForRegion,
  isConnectableDatabase,
  looksLikeAstraToken,
  redactSecrets,
  regionsFromBundles,
  type AstraFormState,
  type ValidationErrors,
} from './connectionModel';

export interface AstraConnectionFormProps {
  value: AstraFormState;
  onChange: (next: AstraFormState) => void;
  errors?: ValidationErrors;
  /** Selected bundle file for UPLOAD mode; owned by the parent so it can build the multipart body. */
  onBundleFile?: (file: File | null) => void;
  /**
   * The saved connection the bundle belongs to. Absent while creating: the bundle endpoints are
   * keyed by connection id, so "re-download" only exists once the connection has been saved.
   */
  connectionId?: string;
  api?: AstraApi;
}

const ACQUISITION_MODES: { value: ScbAcquisitionMode; label: string; help: string }[] = [
  {
    value: 'AUTO_DOWNLOAD',
    label: 'Download automatically (recommended)',
    help: 'Your token is used to list your databases and fetch the matching bundle. No UUIDs, no file downloads.',
  },
  {
    value: 'UPLOAD',
    label: 'Upload a bundle file',
    help: 'For air-gapped installs, restricted egress, or a hand-issued bundle. Stored encrypted.',
  },
  {
    value: 'PATH',
    label: 'Server-side file path',
    help: 'For Docker/Kubernetes deployments that mount the bundle as a volume or secret.',
  },
];

/**
 * Astra DB connection form (plan §3 / §3.1).
 *
 * Three first-class secure-connect-bundle acquisition modes, `AUTO_DOWNLOAD` by default.
 *
 * SECURITY invariants enforced here:
 *  - the token is entered through `SecretField` (masked, explicit reveal toggle);
 *  - the token is only ever sent in a request BODY, never a URL/query string (see `endpoints.ts`);
 *  - error messages from the DevOps path are passed through `redactSecrets` before display, so a
 *    backend that echoes a request cannot leak the token into the UI.
 */
export function AstraConnectionForm({
  value,
  onChange,
  errors = {},
  onBundleFile,
  connectionId,
  api = defaultAstraApi,
}: AstraConnectionFormProps) {
  const [databases, setDatabases] = useState<AstraDatabase[] | null>(null);
  const [bundles, setBundles] = useState<AstraBundleDatacenter[]>([]);
  const [loading, setLoading] = useState<'databases' | 'bundles' | 'refresh' | null>(null);
  const [remoteError, setRemoteError] = useState<string | null>(null);
  const [refreshedAt, setRefreshedAt] = useState<string | null>(null);

  const patch = useCallback(
    (partial: Partial<AstraFormState>) => onChange({ ...value, ...partial }),
    [onChange, value],
  );

  const tokenUsable = looksLikeAstraToken(value.astraToken);

  const reportError = (error: unknown, fallback: string) => {
    const raw = error instanceof Error ? error.message : fallback;
    setRemoteError(redactSecrets(raw || fallback));
  };

  const loadDatabases = async () => {
    setRemoteError(null);
    setLoading('databases');
    try {
      const result = await api.listDatabases(value.astraToken);
      setDatabases(result);
      if (result.length === 0) {
        setRemoteError('This token has access to no databases.');
      }
    } catch (error) {
      setDatabases(null);
      reportError(
        error,
        'Could not reach api.astra.datastax.com. If this network has no egress, upload the bundle instead.',
      );
    } finally {
      setLoading(null);
    }
  };

  const selectDatabase = async (databaseId: string) => {
    patch({ databaseId, region: '', customDomain: '', scbType: 'default' });
    setBundles([]);
    if (!databaseId) return;
    setRemoteError(null);
    setLoading('bundles');
    try {
      setBundles(await api.listBundles(databaseId, value.astraToken));
    } catch (error) {
      reportError(error, 'Could not list secure connect bundles for that database.');
    } finally {
      setLoading(null);
    }
  };

  const refreshBundle = async () => {
    if (!value.databaseId || !connectionId) return;
    setRemoteError(null);
    setLoading('refresh');
    try {
      await api.redownload(value.databaseId, value.astraToken, {
        connectionId,
        region: value.region,
        scbType: value.scbType,
        domain: value.customDomain,
      });
      setBundles(await api.listBundles(value.databaseId, value.astraToken));
      setRefreshedAt(new Date().toISOString());
    } catch (error) {
      reportError(error, 'Re-download failed.');
    } finally {
      setLoading(null);
    }
  };

  const regions = useMemo(() => regionsFromBundles(bundles), [bundles]);
  const domains = useMemo(() => domainsForRegion(bundles, value.region), [bundles, value.region]);

  const handleFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0] ?? null;
    patch({ bundleFileName: file?.name ?? '' });
    onBundleFile?.(file);
  };

  return (
    <Stack spacing={2} data-testid="astra-form">
      <SecretField
        label="Astra token"
        placeholder="AstraCS:…"
        value={value.astraToken}
        onValueChange={(astraToken) => patch({ astraToken })}
        error={Boolean(errors.astraToken)}
        helperText={
          errors.astraToken ??
          'Full-privilege credential. Stored encrypted, never returned by the API, never logged.'
        }
        fullWidth
        testId="astra-token"
      />

      <FormControl>
        <FormLabel sx={{ fontSize: '0.8rem', fontWeight: 600, mb: 0.5 }}>
          Secure connect bundle
        </FormLabel>
        <RadioGroup
          value={value.acquisitionMode}
          onChange={(event) => patch({ acquisitionMode: event.target.value as ScbAcquisitionMode })}
        >
          {ACQUISITION_MODES.map((mode) => (
            <FormControlLabel
              key={mode.value}
              value={mode.value}
              control={<Radio size="small" slotProps={{ input: { 'aria-label': mode.label } }} />}
              label={
                <Box>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    {mode.label}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {mode.help}
                  </Typography>
                </Box>
              }
              sx={{ alignItems: 'flex-start', mb: 0.5 }}
            />
          ))}
        </RadioGroup>
      </FormControl>

      {remoteError && (
        <Alert severity="error" data-testid="astra-error">
          {remoteError}
        </Alert>
      )}

      {value.acquisitionMode === 'AUTO_DOWNLOAD' && (
        <Stack spacing={2} sx={{ pl: 1, borderLeft: 2, borderColor: 'divider' }}>
          <Box sx={{ pl: 1.5 }}>
            <Button
              variant="outlined"
              size="small"
              startIcon={
                loading === 'databases' ? (
                  <CircularProgress size={14} />
                ) : (
                  <CloudDownloadRoundedIcon />
                )
              }
              disabled={!tokenUsable || loading !== null}
              onClick={() => void loadDatabases()}
              data-testid="astra-load-databases"
            >
              {databases ? 'Reload databases' : 'List my databases'}
            </Button>
            {!tokenUsable && (
              <FormHelperText>Enter a valid Astra token to list your databases.</FormHelperText>
            )}
          </Box>

          {databases && (
            <FormControl fullWidth error={Boolean(errors.databaseId)} sx={{ pl: 1.5 }}>
              <InputLabel id="astra-db-label">Database</InputLabel>
              <Select
                labelId="astra-db-label"
                label="Database"
                value={value.databaseId}
                onChange={(event) => void selectDatabase(event.target.value)}
                inputProps={{ 'data-testid': 'astra-database-select' }}
              >
                {databases.map((database) => (
                  <MenuItem
                    key={database.id}
                    value={database.id}
                    disabled={!isConnectableDatabase(database.status)}
                  >
                    <Stack direction="row" spacing={1} alignItems="center">
                      <Typography variant="body2">{database.name}</Typography>
                      <Chip
                        size="small"
                        label={database.status}
                        color={database.status === 'ACTIVE' ? 'success' : 'default'}
                        variant="outlined"
                      />
                      {database.regions?.[0] && (
                        <Typography variant="caption" color="text.secondary">
                          {database.regions.join(', ')}
                        </Typography>
                      )}
                    </Stack>
                  </MenuItem>
                ))}
              </Select>
              <FormHelperText>
                {errors.databaseId ?? 'Picked from your account — no database UUID to type.'}
              </FormHelperText>
            </FormControl>
          )}

          {value.databaseId && (
            <Stack spacing={2} sx={{ pl: 1.5 }}>
              {loading === 'bundles' && (
                <Typography variant="caption" color="text.secondary">
                  Loading bundle options…
                </Typography>
              )}

              {/* Region and bundle type are ORTHOGONAL inputs — see plan §3.1 deviation 1. */}
              <FormControl fullWidth disabled={regions.length === 0}>
                <InputLabel id="astra-region-label">Region (optional)</InputLabel>
                <Select
                  labelId="astra-region-label"
                  label="Region (optional)"
                  value={value.region}
                  onChange={(event) => patch({ region: event.target.value, customDomain: '' })}
                  inputProps={{ 'data-testid': 'astra-region-select' }}
                >
                  <MenuItem value="">
                    <em>First available</em>
                  </MenuItem>
                  {regions.map((region) => (
                    <MenuItem key={region} value={region}>
                      {region}
                    </MenuItem>
                  ))}
                </Select>
                <FormHelperText>
                  Multi-region databases publish one bundle per datacenter.
                </FormHelperText>
              </FormControl>

              <FormControl fullWidth error={Boolean(errors.scbType)}>
                <InputLabel id="astra-scb-type-label">Bundle type</InputLabel>
                <Select
                  labelId="astra-scb-type-label"
                  label="Bundle type"
                  value={value.scbType}
                  onChange={(event) =>
                    patch({ scbType: event.target.value as ScbType, customDomain: '' })
                  }
                  inputProps={{ 'data-testid': 'astra-scb-type-select' }}
                >
                  <MenuItem value="default">default</MenuItem>
                  <MenuItem value="custom">custom domain</MenuItem>
                </Select>
                <FormHelperText>
                  {errors.scbType ?? 'Exactly two types. Region is chosen separately, above.'}
                </FormHelperText>
              </FormControl>

              {value.scbType === 'custom' && (
                <FormControl fullWidth error={Boolean(errors.customDomain)}>
                  <InputLabel id="astra-domain-label">Custom domain</InputLabel>
                  <Select
                    labelId="astra-domain-label"
                    label="Custom domain"
                    value={value.customDomain}
                    onChange={(event) => patch({ customDomain: event.target.value })}
                    inputProps={{ 'data-testid': 'astra-domain-select' }}
                  >
                    {domains.map((domain) => (
                      <MenuItem key={domain} value={domain}>
                        {domain}
                      </MenuItem>
                    ))}
                  </Select>
                  <FormHelperText>
                    {errors.customDomain ??
                      (domains.length === 0
                        ? 'This database publishes no custom-domain bundles for the selected region.'
                        : 'From this database’s custom domain bundles.')}
                  </FormHelperText>
                </FormControl>
              )}

              <Box>
                <Button
                  size="small"
                  variant="text"
                  startIcon={
                    loading === 'refresh' ? <CircularProgress size={14} /> : <RefreshRoundedIcon />
                  }
                  onClick={() => void refreshBundle()}
                  disabled={loading !== null || !connectionId}
                  data-testid="astra-redownload"
                >
                  Re-download bundle
                </Button>
                <FormHelperText>
                  {connectionId
                    ? 'Astra rotates bundles. A stale one fails with a confusing TLS error rather than an obvious one — re-download if a previously working connection starts failing handshake.'
                    : 'Save the connection first; the bundle is stored encrypted against it.'}
                  {refreshedAt ? ` Last refreshed ${refreshedAt}.` : ''}
                </FormHelperText>
              </Box>
            </Stack>
          )}
        </Stack>
      )}

      {value.acquisitionMode === 'UPLOAD' && (
        <Box sx={{ pl: 1.5, borderLeft: 2, borderColor: 'divider' }}>
          <Button
            component="label"
            variant="outlined"
            size="small"
            startIcon={<UploadFileRoundedIcon />}
          >
            Choose bundle (.zip)
            <input
              type="file"
              accept=".zip,application/zip"
              hidden
              onChange={handleFile}
              data-testid="astra-bundle-file"
            />
          </Button>
          <FormHelperText error={Boolean(errors.bundleFile)}>
            {errors.bundleFile ??
              (value.bundleFileName
                ? `Selected: ${value.bundleFileName}`
                : 'Uploaded over multipart/form-data and stored encrypted (AES-256-GCM) alongside the connection.')}
          </FormHelperText>
        </Box>
      )}

      {value.acquisitionMode === 'PATH' && (
        <Box sx={{ pl: 1.5, borderLeft: 2, borderColor: 'divider' }}>
          <TextField
            fullWidth
            label="Bundle path"
            placeholder="/etc/cassyx/scb/my-db.zip"
            value={value.bundlePath}
            onChange={(event) => patch({ bundlePath: event.target.value })}
            error={Boolean(errors.bundlePath)}
            slotProps={{ htmlInput: { 'data-testid': 'astra-bundle-path', spellCheck: false } }}
          />
          <Alert severity="info" sx={{ mt: 1 }} data-testid="astra-path-warning">
            This path is resolved <strong>on the cassyx server</strong>, not on your computer. The
            file must exist inside the server container or host, under the configured allow-list
            root (<code>CASSYX_SCB_PATH_ROOT</code>, default <code>/etc/cassyx/scb</code>
            ). Use this mode for Docker/Kubernetes volume or secret mounts.
          </Alert>
          {errors.bundlePath && <FormHelperText error>{errors.bundlePath}</FormHelperText>}
        </Box>
      )}

      <TextField
        fullWidth
        label="Default keyspace (optional)"
        value={value.keyspace}
        onChange={(event) => patch({ keyspace: event.target.value })}
        slotProps={{ htmlInput: { 'data-testid': 'astra-keyspace' } }}
      />
    </Stack>
  );
}
