/**
 * The load (import) flow (plan §5.3).
 *
 * Source (staged upload / server-side path / S3 URI) → format → target keyspace + table →
 * field-to-column mapping → dry run → the shared `DsbulkSettingsForm` for everything else.
 *
 * The mapping UI builds DSBulk's `a=b, c=d` string, which is also editable directly: the rows are
 * a convenience, not a cage — DSBulk's mapping syntax has forms (function calls, `__ttl`) the row
 * editor deliberately does not model.
 */
import { useCallback, useMemo, useRef, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import FormControl from '@mui/material/FormControl';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import InputLabel from '@mui/material/InputLabel';
import MenuItem from '@mui/material/MenuItem';
import Radio from '@mui/material/Radio';
import RadioGroup from '@mui/material/RadioGroup';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import Switch from '@mui/material/Switch';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import { DsbulkSettingsForm } from './DsbulkSettingsForm';
import { defaultDsbulkApi, type DsbulkApi, type ExportFormat } from './dsbulkApi';
import {
  buildLoadJobRequest,
  buildMappingString,
  parseMappingString,
  validateLoadDraft,
  type DerivedSetting,
  type FlatSettings,
  type LoadJobDraft,
  type LoadJobRequest,
  type LoadSource,
  type MappingRow,
} from './dsbulkSettingsModel';

export type LoadSourceKind = 'upload' | 'path' | 's3';

const FORMATS: ExportFormat[] = ['CSV', 'JSON', 'JSONL', 'PARQUET', 'XML', 'XLSX'];

/** Groups relevant to a load; `stats` belongs to the count workflow only. */
const LOAD_GROUPS = [
  'connector',
  'schema',
  'batch',
  'codec',
  'engine',
  'executor',
  'log',
  'monitoring',
  'driver',
  's3',
] as const;

export interface LoadJobFormProps {
  connectionId: string;
  /** Pre-selected target, e.g. when launched from the schema tree. */
  keyspace?: string;
  table?: string;
  derived?: readonly DerivedSetting[];
  api?: DsbulkApi;
  onSubmit: (request: LoadJobRequest) => void | Promise<void>;
  /** Notified on every settings edit so the parent can refresh the command preview. */
  onSettingsChange?: (values: FlatSettings) => void;
}

export function LoadJobForm({
  connectionId,
  keyspace = '',
  table = '',
  derived = [],
  api = defaultDsbulkApi,
  onSubmit,
  onSettingsChange,
}: LoadJobFormProps) {
  const [name, setName] = useState('');
  const [sourceKind, setSourceKind] = useState<LoadSourceKind>('upload');
  const [uploadId, setUploadId] = useState('');
  const [uploadName, setUploadName] = useState('');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [path, setPath] = useState('');
  const [s3Uri, setS3Uri] = useState('');
  const [format, setFormat] = useState<ExportFormat>('CSV');
  const [targetKeyspace, setTargetKeyspace] = useState(keyspace);
  const [targetTable, setTargetTable] = useState(table);
  const [mappingRows, setMappingRows] = useState<MappingRow[]>([{ field: '', column: '' }]);
  const [mappingOverride, setMappingOverride] = useState<string | null>(null);
  const [dryRun, setDryRun] = useState(false);
  const [values, setValues] = useState<FlatSettings>({});
  const [submitted, setSubmitted] = useState(false);

  const mapping = mappingOverride ?? buildMappingString(mappingRows);

  const draft = useMemo<LoadJobDraft>(() => {
    // `AUTO` lets the server sniff gzip/zstd from the file itself rather than the extension.
    const source: LoadSource = { format, compression: 'AUTO' };
    if (sourceKind === 'upload' && uploadId) source.uploadId = uploadId;
    if (sourceKind === 'path') source.path = path;
    if (sourceKind === 's3') source.s3Uri = s3Uri;
    return {
      name,
      keyspace: targetKeyspace,
      table: targetTable,
      source,
      mapping,
      dryRun,
      overrides: values,
    };
  }, [
    name,
    targetKeyspace,
    targetTable,
    sourceKind,
    uploadId,
    path,
    s3Uri,
    format,
    mapping,
    dryRun,
    values,
  ]);

  const errors = validateLoadDraft(draft);
  const visibleErrors = submitted ? errors : {};
  const valid = Object.keys(errors).length === 0;

  // Stable identity so the (memoised) settings form is not re-rendered by every keystroke in the
  // mapping editor above it.
  const notifySettings = useRef(onSettingsChange);
  notifySettings.current = onSettingsChange;
  const handleSettingsChange = useCallback((next: FlatSettings) => {
    setValues(next);
    notifySettings.current?.(next);
  }, []);

  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      const upload = await api.uploadSourceFile(file, format);
      setUploadId(upload.uploadId);
      setUploadName(upload.fileName);
    } catch (error) {
      setUploadError(error instanceof Error ? error.message : 'Upload failed.');
    } finally {
      setUploading(false);
    }
  };

  const handleSubmit = async () => {
    setSubmitted(true);
    if (!valid) return;
    await onSubmit(buildLoadJobRequest(draft));
  };

  const updateRow = (index: number, patch: Partial<MappingRow>) => {
    setMappingOverride(null);
    setMappingRows((rows) => rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  };

  return (
    <Stack spacing={3} data-testid="load-job-form" data-connection-id={connectionId}>
      <TextField
        label="Job name"
        size="small"
        value={name}
        placeholder={`Load ${targetTable || 'table'}`}
        onChange={(event) => setName(event.target.value)}
        slotProps={{ inputLabel: { shrink: true }, htmlInput: { 'data-testid': 'load-name' } }}
      />

      <Stack spacing={1}>
        <Typography variant="subtitle2">Source</Typography>
        <RadioGroup
          row
          value={sourceKind}
          onChange={(event) => setSourceKind(event.target.value as LoadSourceKind)}
        >
          <FormControlLabel value="upload" control={<Radio />} label="Upload a file" />
          <FormControlLabel value="path" control={<Radio />} label="Server path" />
          <FormControlLabel value="s3" control={<Radio />} label="S3 URI" />
        </RadioGroup>

        {sourceKind === 'upload' && (
          <Stack spacing={1}>
            <Button component="label" variant="outlined" size="small" disabled={uploading}>
              {uploading ? 'Uploading…' : 'Choose file'}
              <input
                hidden
                type="file"
                data-testid="load-file"
                onChange={(event) => void handleFile(event.target.files?.[0])}
              />
            </Button>
            {uploadName && (
              <Typography variant="caption" color="text.secondary" data-testid="upload-name">
                Staged {uploadName} ({uploadId})
              </Typography>
            )}
            {uploadError && <Alert severity="error">{uploadError}</Alert>}
          </Stack>
        )}

        {sourceKind === 'path' && (
          <TextField
            label="Server-side path"
            size="small"
            value={path}
            placeholder="/data/imports/users.csv"
            onChange={(event) => setPath(event.target.value)}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { 'data-testid': 'load-path' } }}
          />
        )}

        {sourceKind === 's3' && (
          <TextField
            label="S3 URI"
            size="small"
            value={s3Uri}
            placeholder="s3://analytics-bucket/imports/users.csv"
            onChange={(event) => setS3Uri(event.target.value)}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { 'data-testid': 'load-s3' } }}
          />
        )}

        {visibleErrors.source && <Alert severity="warning">{visibleErrors.source}</Alert>}

        <FormControl size="small" sx={{ maxWidth: 240 }}>
          <InputLabel id="load-format-label">Format</InputLabel>
          <Select
            labelId="load-format-label"
            label="Format"
            value={format}
            onChange={(event) => setFormat(event.target.value as ExportFormat)}
            inputProps={{ 'data-testid': 'load-format' }}
          >
            {FORMATS.map((option) => (
              <MenuItem key={option} value={option}>
                {option}
              </MenuItem>
            ))}
          </Select>
        </FormControl>
      </Stack>

      <Divider />

      <Stack spacing={1}>
        <Typography variant="subtitle2">Target</Typography>
        <Stack direction="row" spacing={2}>
          <TextField
            label="Keyspace"
            size="small"
            value={targetKeyspace}
            error={Boolean(visibleErrors.keyspace)}
            helperText={visibleErrors.keyspace}
            onChange={(event) => setTargetKeyspace(event.target.value)}
            slotProps={{ htmlInput: { 'data-testid': 'load-keyspace' } }}
          />
          <TextField
            label="Table"
            size="small"
            value={targetTable}
            error={Boolean(visibleErrors.table)}
            helperText={visibleErrors.table}
            onChange={(event) => setTargetTable(event.target.value)}
            slotProps={{ htmlInput: { 'data-testid': 'load-table' } }}
          />
        </Stack>
      </Stack>

      <Stack spacing={1}>
        <Typography variant="subtitle2">Mapping</Typography>
        <Typography variant="caption" color="text.secondary">
          Field to column. Leave empty to infer the mapping from the file header.
        </Typography>
        {mappingRows.map((row, index) => (
          <Stack key={index} direction="row" spacing={1} alignItems="center">
            <TextField
              label="Field"
              size="small"
              value={row.field}
              placeholder={index === 0 ? 'email' : ''}
              onChange={(event) => updateRow(index, { field: event.target.value })}
              slotProps={{ htmlInput: { 'data-testid': `mapping-field-${index}` } }}
            />
            <Typography aria-hidden>=</Typography>
            <TextField
              label="Column"
              size="small"
              value={row.column}
              placeholder={index === 0 ? 'email_address' : ''}
              onChange={(event) => updateRow(index, { column: event.target.value })}
              slotProps={{ htmlInput: { 'data-testid': `mapping-column-${index}` } }}
            />
            <IconButton
              size="small"
              aria-label={`Remove mapping row ${index + 1}`}
              onClick={() => {
                setMappingOverride(null);
                setMappingRows((rows) => rows.filter((_row, i) => i !== index));
              }}
            >
              <DeleteOutlineRoundedIcon fontSize="small" />
            </IconButton>
          </Stack>
        ))}
        <Stack direction="row" spacing={1}>
          <Button
            size="small"
            startIcon={<AddRoundedIcon />}
            onClick={() => setMappingRows((rows) => [...rows, { field: '', column: '' }])}
          >
            Add mapping
          </Button>
        </Stack>
        <TextField
          label="schema.mapping"
          size="small"
          value={mapping}
          placeholder="user_id=user_id, email=email"
          onChange={(event) => {
            setMappingOverride(event.target.value);
            setMappingRows(parseMappingString(event.target.value));
          }}
          slotProps={{
            inputLabel: { shrink: true },
            htmlInput: {
              'data-testid': 'mapping-string',
              style: { fontFamily: 'monospace', fontSize: '0.8rem' },
            },
          }}
        />
      </Stack>

      <FormControlLabel
        control={
          <Switch
            checked={dryRun}
            onChange={(event) => setDryRun(event.target.checked)}
            inputProps={{ 'aria-label': 'Dry run' }}
          />
        }
        label="Dry run — validate and map every record without writing"
      />

      <Divider />

      <DsbulkSettingsForm
        values={values}
        onChange={handleSettingsChange}
        derived={derived}
        groups={LOAD_GROUPS}
      />

      {submitted && !valid && (
        <Alert severity="warning">Fix the highlighted fields before starting the job.</Alert>
      )}

      <Stack direction="row" justifyContent="flex-end">
        <Button variant="contained" onClick={() => void handleSubmit()} data-testid="load-submit">
          Start load
        </Button>
      </Stack>
    </Stack>
  );
}
