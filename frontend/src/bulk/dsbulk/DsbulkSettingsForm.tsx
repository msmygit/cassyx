/**
 * The DSBulk settings surface (plan §5.3), as progressive disclosure.
 *
 * A **Simple** tab carries the handful of fields most jobs need; the **Advanced** accordion
 * exposes every modelled setting, one panel per DSBulk group. Every field renders DSBulk's own
 * default as PLACEHOLDER text (never as a value — a pre-filled default is indistinguishable from
 * a deliberate choice), links to the upstream reference docs, and shows an "auto" chip whose
 * tooltip is the server's rationale when the value came from `deriveBulkDefaults`. Editing a field
 * clears that marker.
 */
import { memo, useCallback, useMemo, useRef, useState } from 'react';
import Accordion from '@mui/material/Accordion';
import AccordionDetails from '@mui/material/AccordionDetails';
import AccordionSummary from '@mui/material/AccordionSummary';
import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import FormControl from '@mui/material/FormControl';
import FormHelperText from '@mui/material/FormHelperText';
import IconButton from '@mui/material/IconButton';
import InputLabel from '@mui/material/InputLabel';
import Link from '@mui/material/Link';
import MenuItem from '@mui/material/MenuItem';
import Select from '@mui/material/Select';
import Stack from '@mui/material/Stack';
import Tab from '@mui/material/Tab';
import Tabs from '@mui/material/Tabs';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import HelpOutlineRoundedIcon from '@mui/icons-material/HelpOutlineRounded';
import RestartAltRoundedIcon from '@mui/icons-material/RestartAltRounded';
import {
  DSBULK_GROUP_LABELS,
  DSBULK_SETTING_GROUPS,
  settingsForGroup,
  simpleSettings,
  type DsbulkSettingDef,
  type DsbulkSettingGroup,
} from './dsbulkSettingsCatalog';
import {
  applyOverride,
  clearOverride,
  resolveSettings,
  validateSettingValue,
  type DerivedSetting,
  type FlatSettings,
  type ResolvedSetting,
} from './dsbulkSettingsModel';

export interface DsbulkSettingsFormProps {
  /** User overrides, keyed by setting path. The form is fully controlled. */
  values: FlatSettings;
  onChange: (next: FlatSettings) => void;
  /** Server-derived defaults from `POST /bulk/defaults`. */
  derived?: readonly DerivedSetting[];
  /** Restrict the accordion to these groups (e.g. hide `stats` outside a count job). */
  groups?: readonly DsbulkSettingGroup[];
}

interface SettingFieldProps {
  def: DsbulkSettingDef;
  resolved: ResolvedSetting | undefined;
  onChange: (path: string, value: string) => void;
  onReset: (path: string) => void;
}

function AutoChip({ rationale }: { rationale?: string }) {
  return (
    <Tooltip
      title={rationale && rationale.length > 0 ? rationale : 'Derived automatically by cassyx.'}
    >
      <Chip size="small" color="info" variant="outlined" label="auto" data-testid="auto-chip" />
    </Tooltip>
  );
}

function DocsLink({ def }: { def: DsbulkSettingDef }) {
  return (
    <Tooltip title={def.help}>
      <Link
        href={def.docsUrl}
        target="_blank"
        rel="noreferrer noopener"
        aria-label={`Documentation for ${def.dsbulkPath ?? def.path}`}
        sx={{ display: 'inline-flex' }}
      >
        <HelpOutlineRoundedIcon fontSize="small" />
      </Link>
    </Tooltip>
  );
}

/**
 * Memoised: the Advanced accordion renders a hundred-odd fields, and the form is embedded in
 * larger forms (the load flow) that re-render on every unrelated keystroke.
 */
const SettingField = memo(function SettingField({
  def,
  resolved,
  onChange,
  onReset,
}: SettingFieldProps) {
  // Write-only credentials are never rendered back: the field starts empty every time.
  const stored = resolved?.value ?? '';
  const value = def.secret && resolved?.auto !== false ? '' : stored;
  const error = validateSettingValue(def, value);
  const auto = resolved?.auto === true;
  const placeholder = resolved?.upstreamDefault ?? def.upstreamDefault;
  const canReset = !auto && Boolean(resolved?.autoValue);

  const adornment = (
    <Stack direction="row" spacing={0.5} alignItems="center">
      {auto && <AutoChip rationale={resolved?.rationale} />}
      {canReset && (
        <Tooltip title="Reset to the derived value">
          <IconButton
            size="small"
            aria-label={`Reset ${def.label} to auto`}
            onClick={() => onReset(def.path)}
          >
            <RestartAltRoundedIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      )}
      <DocsLink def={def} />
    </Stack>
  );

  const helperText = error ?? def.help;

  if (def.kind === 'enum' || (def.kind === 'stringList' && def.enumValues)) {
    const multiple = def.kind === 'stringList';
    const selected = multiple
      ? value
          .split(',')
          .map((item) => item.trim())
          .filter((item) => item.length > 0)
      : value;
    return (
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <FormControl fullWidth size="small" error={Boolean(error)}>
          <InputLabel id={`label-${def.path}`}>{def.label}</InputLabel>
          <Select
            labelId={`label-${def.path}`}
            label={def.label}
            multiple={multiple}
            value={selected}
            displayEmpty
            onChange={(event) => {
              const next = event.target.value;
              onChange(def.path, Array.isArray(next) ? next.join(', ') : String(next));
            }}
            inputProps={{ 'data-testid': `setting-${def.path}` }}
          >
            {!multiple && (
              <MenuItem value="">
                <em>Default ({def.upstreamDefault || 'unset'})</em>
              </MenuItem>
            )}
            {(def.enumValues ?? []).map((option) => (
              <MenuItem key={option} value={option}>
                {option}
              </MenuItem>
            ))}
          </Select>
          <FormHelperText>{helperText}</FormHelperText>
        </FormControl>
        <Box sx={{ pt: 1 }}>{adornment}</Box>
      </Stack>
    );
  }

  if (def.kind === 'boolean') {
    return (
      <Stack direction="row" spacing={1} alignItems="flex-start">
        <FormControl fullWidth size="small" error={Boolean(error)}>
          <InputLabel id={`label-${def.path}`}>{def.label}</InputLabel>
          <Select
            labelId={`label-${def.path}`}
            label={def.label}
            value={value}
            displayEmpty
            onChange={(event) => onChange(def.path, String(event.target.value))}
            inputProps={{ 'data-testid': `setting-${def.path}` }}
          >
            <MenuItem value="">
              <em>Default ({def.upstreamDefault})</em>
            </MenuItem>
            <MenuItem value="true">true</MenuItem>
            <MenuItem value="false">false</MenuItem>
          </Select>
          <FormHelperText>{helperText}</FormHelperText>
        </FormControl>
        <Box sx={{ pt: 1 }}>{adornment}</Box>
      </Stack>
    );
  }

  return (
    <Stack direction="row" spacing={1} alignItems="flex-start">
      <TextField
        fullWidth
        size="small"
        label={def.label}
        value={value}
        placeholder={placeholder}
        error={Boolean(error)}
        helperText={helperText}
        type={def.secret ? 'password' : 'text'}
        onChange={(event) => onChange(def.path, event.target.value)}
        slotProps={{
          inputLabel: { shrink: true },
          htmlInput: {
            'data-testid': `setting-${def.path}`,
            ...(def.secret
              ? { autoComplete: 'off', spellCheck: false, 'data-secret': 'true' }
              : {}),
          },
        }}
      />
      <Box sx={{ pt: 1 }}>{adornment}</Box>
    </Stack>
  );
});

/** Stable identity for the default, so the memoised fields are not invalidated every render. */
const NO_DERIVED: readonly DerivedSetting[] = [];

export const DsbulkSettingsForm = memo(function DsbulkSettingsForm({
  values,
  onChange,
  derived = NO_DERIVED,
  groups = DSBULK_SETTING_GROUPS,
}: DsbulkSettingsFormProps) {
  const [tab, setTab] = useState<'simple' | 'advanced'>('simple');
  const resolved = useMemo(() => resolveSettings(derived, values), [derived, values]);

  // Refs keep the two handlers referentially stable, which is what makes `SettingField`'s
  // memoisation actually pay off.
  const latest = useRef({ values, onChange });
  latest.current = { values, onChange };

  const handleChange = useCallback((path: string, value: string) => {
    const { values: current, onChange: notify } = latest.current;
    notify(applyOverride(current, path, value));
  }, []);
  const handleReset = useCallback((path: string) => {
    const { values: current, onChange: notify } = latest.current;
    notify(clearOverride(current, path));
  }, []);

  const visible = (def: DsbulkSettingDef) => groups.includes(def.group);
  const simple = simpleSettings().filter(visible);

  return (
    <Stack spacing={2} data-testid="dsbulk-settings-form">
      <Tabs
        value={tab}
        onChange={(_event, next: 'simple' | 'advanced') => setTab(next)}
        variant="fullWidth"
      >
        <Tab value="simple" label="Simple" />
        <Tab value="advanced" label="Advanced" />
      </Tabs>

      {tab === 'simple' && (
        <Stack spacing={2} data-testid="dsbulk-simple">
          <Typography variant="caption" color="text.secondary">
            Everything else is derived from a cluster probe. Values shown with an “auto” chip were
            chosen by cassyx — hover the chip to see why, and edit any field to override it.
          </Typography>
          {simple.map((def) => (
            <SettingField
              key={def.path}
              def={def}
              resolved={resolved[def.path]}
              onChange={handleChange}
              onReset={handleReset}
            />
          ))}
        </Stack>
      )}

      {tab === 'advanced' && (
        <Box data-testid="dsbulk-advanced">
          {groups.map((group) => (
            <Accordion
              key={group}
              disableGutters
              slotProps={{ transition: { unmountOnExit: true } }}
              data-testid={`group-${group}`}
            >
              <AccordionSummary expandIcon={<ExpandMoreRoundedIcon />}>
                <Typography variant="subtitle2">{DSBULK_GROUP_LABELS[group]}</Typography>
              </AccordionSummary>
              <AccordionDetails>
                <Stack spacing={2}>
                  {settingsForGroup(group).map((def) => (
                    <SettingField
                      key={def.path}
                      def={def}
                      resolved={resolved[def.path]}
                      onChange={handleChange}
                      onReset={handleReset}
                    />
                  ))}
                </Stack>
              </AccordionDetails>
            </Accordion>
          ))}
        </Box>
      )}
    </Stack>
  );
});
