import { useRef, useState } from 'react';
import Checkbox from '@mui/material/Checkbox';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import type { DdlAction, DdlObjectType } from '../schema/schemaTypes';
import { isVectorType } from './ddlModel';
import { CQL_PERMISSIONS, fieldsFor, type FieldSpec } from './editorFields';

export interface ObjectEditorFormProps {
  objectType: DdlObjectType;
  action: DdlAction;
  value: Record<string, unknown>;
  onChange: (next: Record<string, unknown>) => void;
}

interface ColumnRow {
  name: string;
  type: string;
  static?: boolean;
}

/** The visual half of every DDL editor. The other half is always the Preview CQL pane. */
export function ObjectEditorForm({ objectType, action, value, onChange }: ObjectEditorFormProps) {
  const set = (key: string, next: unknown) => onChange({ ...value, [key]: next });

  return (
    <Stack spacing={1.5} data-testid="object-editor-form">
      {fieldsFor(objectType, action).map((field) => (
        <Field key={field.key} field={field} value={value[field.key]} onChange={set} />
      ))}
    </Stack>
  );
}

interface FieldProps {
  field: FieldSpec;
  value: unknown;
  onChange: (key: string, next: unknown) => void;
}

function Field({ field, value, onChange }: FieldProps) {
  switch (field.kind) {
    case 'boolean':
      return (
        <FormControlLabel
          control={
            <Checkbox
              size="small"
              checked={Boolean(value)}
              onChange={(event) => onChange(field.key, event.target.checked)}
              inputProps={
                { 'aria-label': field.label } as React.InputHTMLAttributes<HTMLInputElement>
              }
            />
          }
          label={<Typography variant="body2">{field.label}</Typography>}
        />
      );

    case 'select':
      return (
        <TextField
          select
          size="small"
          label={field.label}
          helperText={field.help}
          value={typeof value === 'string' ? value : (field.options?.[0] ?? '')}
          onChange={(event) => onChange(field.key, event.target.value)}
          // A native select rather than MUI's listbox: it is keyboard- and
          // screen-reader-native, and it is what assistive tech expects from a short,
          // fixed option list.
          slotProps={{ select: { native: true }, htmlInput: { 'aria-label': field.label } }}
        >
          {(field.options ?? []).map((option) => (
            <option key={option || '(none)'} value={option}>
              {option || '(default)'}
            </option>
          ))}
        </TextField>
      );

    case 'number':
      return (
        <TextField
          size="small"
          type="number"
          label={field.label}
          helperText={field.help}
          value={value ?? ''}
          onChange={(event) =>
            onChange(field.key, event.target.value === '' ? undefined : Number(event.target.value))
          }
          slotProps={{ htmlInput: { 'aria-label': field.label } }}
        />
      );

    case 'multiline':
      return (
        <TextField
          size="small"
          multiline
          minRows={2}
          label={field.label}
          helperText={field.help}
          placeholder={field.placeholder}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(field.key, event.target.value)}
          slotProps={{ htmlInput: { 'aria-label': field.label } }}
        />
      );

    case 'tags':
      return (
        <TagsField
          label={field.label}
          help={field.help}
          value={Array.isArray(value) ? (value as string[]) : []}
          onChange={(next) => onChange(field.key, next)}
        />
      );

    case 'permissions':
      return (
        <PermissionsField
          label={field.label}
          value={Array.isArray(value) ? (value as string[]) : []}
          onChange={(next) => onChange(field.key, next)}
        />
      );

    case 'columns':
      return (
        <RowsField
          label={field.label}
          help={field.help}
          rows={Array.isArray(value) ? (value as ColumnRow[]) : []}
          onChange={(next) => onChange(field.key, next)}
          withStatic
        />
      );

    case 'typeFields':
    case 'functionArgs':
      return (
        <RowsField
          label={field.label}
          help={field.help}
          rows={Array.isArray(value) ? (value as ColumnRow[]) : []}
          onChange={(next) => onChange(field.key, next)}
        />
      );

    case 'primaryKey':
      return (
        <PrimaryKeyField
          label={field.label}
          value={
            value as {
              partitionKey?: string[];
              clusteringKey?: { column: string; order?: string }[];
            }
          }
          onChange={(next) => onChange(field.key, next)}
        />
      );

    case 'replication':
      return (
        <ReplicationField
          value={
            value as {
              strategy?: string;
              replicationFactor?: number;
              datacenters?: Record<string, number>;
            }
          }
          onChange={(next) => onChange(field.key, next)}
        />
      );

    case 'text':
    default:
      return (
        <TextField
          size="small"
          label={field.label}
          helperText={
            isVectorType(typeof value === 'string' ? value : undefined)
              ? 'Vector columns require Cassandra 5.x, DSE 6.8+ or Astra.'
              : field.help
          }
          placeholder={field.placeholder}
          value={typeof value === 'string' ? value : ''}
          onChange={(event) => onChange(field.key, event.target.value)}
          slotProps={{ htmlInput: { 'aria-label': field.label } }}
        />
      );
  }
}

/**
 * A comma-separated text field that reports a parsed array.
 *
 * The raw text is local state on purpose. Round-tripping through `value.join(', ')` on every
 * keystroke eats the separator the moment it is typed - "double," parses to ["double"], renders
 * back as "double", and the next character lands in the wrong token.
 */
function DelimitedField({
  label,
  help,
  initialText,
  onText,
}: {
  label: string;
  help?: string;
  initialText: string;
  onText: (text: string) => void;
}) {
  const [text, setText] = useState(initialText);
  return (
    <TextField
      size="small"
      label={label}
      helperText={help}
      value={text}
      onChange={(event) => {
        setText(event.target.value);
        onText(event.target.value);
      }}
      slotProps={{ htmlInput: { 'aria-label': label } }}
    />
  );
}

function splitList(text: string): string[] {
  return text
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function TagsField({
  label,
  help,
  value,
  onChange,
}: {
  label: string;
  help?: string;
  value: string[];
  onChange: (next: string[]) => void;
}) {
  return (
    <DelimitedField
      label={label}
      help={help ?? 'Comma-separated.'}
      initialText={value.join(', ')}
      onText={(text) => onChange(splitList(text))}
    />
  );
}

function PermissionsField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string[];
  onChange: (next: string[]) => void;
}) {
  const toggle = (permission: string) =>
    onChange(
      value.includes(permission)
        ? value.filter((entry) => entry !== permission)
        : [...value, permission],
    );

  return (
    <Stack spacing={0.5}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Stack direction="row" flexWrap="wrap" gap={0.5}>
        {CQL_PERMISSIONS.map((permission) => (
          <Chip
            key={permission}
            label={permission}
            size="small"
            color={value.includes(permission) ? 'primary' : 'default'}
            variant={value.includes(permission) ? 'filled' : 'outlined'}
            onClick={() => toggle(permission)}
          />
        ))}
      </Stack>
    </Stack>
  );
}

function RowsField({
  label,
  help,
  rows,
  onChange,
  withStatic = false,
}: {
  label: string;
  help?: string;
  rows: ColumnRow[];
  onChange: (next: ColumnRow[]) => void;
  withStatic?: boolean;
}) {
  const update = (index: number, patch: Partial<ColumnRow>) =>
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));

  return (
    <Stack spacing={0.5}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="caption" color="text.secondary">
          {label}
        </Typography>
        <IconButton
          size="small"
          aria-label={`Add ${label}`}
          onClick={() => onChange([...rows, { name: '', type: '' }])}
        >
          <AddRoundedIcon fontSize="small" />
        </IconButton>
      </Stack>
      {help && (
        <Typography variant="caption" color="text.secondary">
          {help}
        </Typography>
      )}
      {rows.map((row, index) => (
        <Stack key={index} direction="row" spacing={1} alignItems="center">
          <TextField
            size="small"
            placeholder="name"
            value={row.name}
            onChange={(event) => update(index, { name: event.target.value })}
            slotProps={{ htmlInput: { 'aria-label': `${label} ${index + 1} name` } }}
          />
          <TextField
            size="small"
            placeholder="type"
            value={row.type}
            onChange={(event) => update(index, { type: event.target.value })}
            slotProps={{ htmlInput: { 'aria-label': `${label} ${index + 1} type` } }}
          />
          {withStatic && (
            <FormControlLabel
              control={
                <Checkbox
                  size="small"
                  checked={Boolean(row.static)}
                  onChange={(event) => update(index, { static: event.target.checked })}
                  inputProps={
                    {
                      'aria-label': `${label} ${index + 1} static`,
                    } as React.InputHTMLAttributes<HTMLInputElement>
                  }
                />
              }
              label={<Typography variant="caption">static</Typography>}
            />
          )}
          <IconButton
            size="small"
            aria-label={`Remove ${label} ${index + 1}`}
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
          >
            <DeleteOutlineRoundedIcon fontSize="small" />
          </IconButton>
        </Stack>
      ))}
    </Stack>
  );
}

function PrimaryKeyField({
  label,
  value,
  onChange,
}: {
  label: string;
  value?: { partitionKey?: string[]; clusteringKey?: { column: string; order?: string }[] };
  onChange: (next: {
    partitionKey: string[];
    clusteringKey: { column: string; order: string }[];
  }) => void;
}) {
  const partitionKey = value?.partitionKey ?? [];
  const clusteringKey = (value?.clusteringKey ?? []).map((entry) => ({
    column: entry.column,
    order: entry.order ?? 'ASC',
  }));

  // Each half reports only its own change; the other half is read from the current prop, which is
  // the parent's authoritative state.
  const latest = useRef({ partitionKey, clusteringKey });
  latest.current = { partitionKey, clusteringKey };

  return (
    <Stack spacing={0.5}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <DelimitedField
        label="Partition key"
        help="Comma-separated. More than one column becomes a composite partition key."
        initialText={partitionKey.join(', ')}
        onText={(text) =>
          onChange({
            partitionKey: splitList(text),
            clusteringKey: latest.current.clusteringKey,
          })
        }
      />
      <DelimitedField
        label="Clustering key"
        help="Comma-separated, optionally with a direction: created_at DESC, id ASC"
        initialText={clusteringKey.map((entry) => `${entry.column} ${entry.order}`).join(', ')}
        onText={(text) =>
          onChange({
            partitionKey: latest.current.partitionKey,
            clusteringKey: splitList(text).map((entry) => {
              const [column = '', order = 'ASC'] = entry.split(/\s+/);
              return { column, order: order.toUpperCase() === 'DESC' ? 'DESC' : 'ASC' };
            }),
          })
        }
      />
    </Stack>
  );
}

function ReplicationField({
  value,
  onChange,
}: {
  value?: { strategy?: string; replicationFactor?: number; datacenters?: Record<string, number> };
  onChange: (next: {
    strategy: string;
    replicationFactor?: number;
    datacenters?: Record<string, number>;
  }) => void;
}) {
  const strategy = value?.strategy ?? 'SimpleStrategy';
  const datacenters = value?.datacenters ?? {};

  return (
    <Stack spacing={0.5}>
      <TextField
        select
        size="small"
        label="Replication strategy"
        value={strategy}
        onChange={(event) =>
          onChange(
            event.target.value === 'SimpleStrategy'
              ? { strategy: 'SimpleStrategy', replicationFactor: value?.replicationFactor ?? 1 }
              : { strategy: event.target.value, datacenters },
          )
        }
        slotProps={{
          select: { native: true },
          htmlInput: { 'aria-label': 'Replication strategy' },
        }}
      >
        <option value="SimpleStrategy">SimpleStrategy</option>
        <option value="NetworkTopologyStrategy">NetworkTopologyStrategy</option>
      </TextField>

      {strategy === 'SimpleStrategy' ? (
        <TextField
          size="small"
          type="number"
          label="Replication factor"
          value={value?.replicationFactor ?? 1}
          onChange={(event) =>
            onChange({ strategy, replicationFactor: Number(event.target.value) })
          }
          slotProps={{ htmlInput: { 'aria-label': 'Replication factor' } }}
        />
      ) : (
        <DelimitedField
          label="Per-datacenter replication"
          help="dc1:3, dc2:2"
          initialText={Object.entries(datacenters)
            .map(([dc, rf]) => `${dc}:${rf}`)
            .join(', ')}
          onText={(text) => {
            const parsed: Record<string, number> = {};
            for (const entry of text.split(',')) {
              const [dc, rf] = entry.split(':').map((part) => part.trim());
              if (dc && rf && !Number.isNaN(Number(rf))) parsed[dc] = Number(rf);
            }
            onChange({ strategy, datacenters: parsed });
          }}
        />
      )}
    </Stack>
  );
}
