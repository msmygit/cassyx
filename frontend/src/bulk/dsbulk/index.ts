/**
 * DSBulk settings UI (plan §5.3 / §5.4).
 *
 * Self-contained: nothing in here touches routes, `App.tsx` or the layout shell.
 *
 * ─── WIRING OWED BY THE PARENT WORKSTREAM ────────────────────────────────────────────────────
 *  1. Route + nav entry for the load flow, rendering `<LoadJobForm connectionId={…} />` with the
 *     active connection id, and a "Load data" action on the schema-tree table context menu that
 *     pre-fills `keyspace`/`table`.
 *  2. DONE — `src/routes/StatisticsPage.tsx` renders `<CountStatisticsView />` from
 *     `GET /api/connections/{id}/keyspaces/{ks}/tables/{t}/statistics` and posts a
 *     `CountJobRequest` via `useCreateCountJob`, behind a cost confirmation (plan §5.4).
 *  3. Job progress: `createLoadJob`/`createCountJob` return a queued `Job`; subscribe to
 *     `/api/jobs/{id}/events` with `src/api/sse.ts` and surface it in the jobs panel.
 *  4. Job templates: `listJobTemplates` / `createJobTemplate` are implemented here but no UI owns
 *     the template picker yet — it belongs next to the job launcher.
 *
 * ─── BACKEND ASSUMPTIONS ─────────────────────────────────────────────────────────────────────
 *  • `DerivedSetting.path` is either the contract path (`driver.basic.requestConsistency`) or
 *    DSBulk's own (`driver.basic.request.consistency`); both are accepted and normalised.
 *  • `BulkCommandPreview.maskedFields` lists every redacted path — the UI shows them explicitly.
 *  • S3 credentials are write-only and are never returned by any response.
 */
export {
  DSBULK_DOCS_BASE,
  DSBULK_GROUP_LABELS,
  DSBULK_SETTING_GROUPS,
  DSBULK_SETTINGS,
  DSBULK_STATS_MODES,
  DSBULK_STATS_MODE_ALIASES,
  SECRET_SETTING_PATHS,
  dsbulkDocsUrl,
  findSetting,
  isSecretPath,
  settingsForGroup,
  simpleSettings,
  type DsbulkSettingDef,
  type DsbulkSettingFormat,
  type DsbulkSettingGroup,
  type DsbulkSettingKind,
} from './dsbulkSettingsCatalog';

export {
  applyOverride,
  buildLoadJobRequest,
  buildMappingString,
  clearOverride,
  displayValue,
  flattenSettings,
  isAuto,
  isConcurrencyExpression,
  isErrorThreshold,
  isIntegerString,
  isNumberString,
  normalisePath,
  parseMap,
  parseMappingString,
  resolveSettings,
  serialiseMap,
  stripSecrets,
  unflattenSettings,
  validateFlatSettings,
  validateLoadDraft,
  validateSettingValue,
  type DerivedSetting,
  type DsbulkSettings,
  type FlatSettings,
  type LoadJobDraft,
  type MappingRow,
  type ResolvedSetting,
  type ResolvedSettings,
} from './dsbulkSettingsModel';

export {
  UPLOAD_TIMEOUT_MS,
  createCountJob,
  createJobTemplate,
  createLoadJob,
  defaultDsbulkApi,
  deleteJobTemplate,
  deriveBulkDefaults,
  listJobTemplates,
  previewBulkCommand,
  updateJobTemplate,
  uploadBulkSourceFile,
  type BulkCommandPreview,
  type BulkCommandPreviewRequest,
  type BulkDefaultsRequest,
  type BulkUpload,
  type CountJobRequest,
  type DerivedSettingsResponse,
  type DsbulkApi,
  type LoadJobRequest,
} from './dsbulkApi';

export {
  dsbulkQueryKeys,
  useCreateCountJob,
  useCreateLoadJob,
  useDebouncedValue,
  useDsbulkCommandPreview,
  useDsbulkDefaults,
  type CommandPreviewOptions,
  type DsbulkHookOptions,
} from './useDsbulkDefaults';

export { DsbulkSettingsForm, type DsbulkSettingsFormProps } from './DsbulkSettingsForm';
export { DsbulkCommandPreview, type DsbulkCommandPreviewProps } from './DsbulkCommandPreview';
export { LoadJobForm, type LoadJobFormProps, type LoadSourceKind } from './LoadJobForm';
export {
  CountStatisticsView,
  type CountStatisticsViewProps,
  type TableStatistics,
} from './CountStatisticsView';
