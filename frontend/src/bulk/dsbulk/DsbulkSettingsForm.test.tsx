import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/render';
import { DsbulkSettingsForm } from './DsbulkSettingsForm';
import { DSBULK_SETTING_GROUPS, findSetting, settingsForGroup } from './dsbulkSettingsCatalog';
import type { DerivedSetting, FlatSettings } from './dsbulkSettingsModel';

const DERIVED: DerivedSetting[] = [
  {
    path: 'batch.maxBatchStatements',
    value: '32',
    auto: true,
    upstreamDefault: '32',
    rationale: '32 statements per batch; reduced to 1 for counter tables.',
    group: 'batch',
  },
  {
    path: 'batch.mode',
    value: 'PARTITION_KEY',
    auto: true,
    rationale: 'The target has a clustering key.',
    group: 'batch',
  },
];

/** Controlled wrapper — the form is fully controlled, like it is at its real call site. */
function Harness({
  onChange,
  derived = DERIVED,
  initial = {},
}: {
  onChange?: (next: FlatSettings) => void;
  derived?: DerivedSetting[];
  initial?: FlatSettings;
}) {
  const [values, setValues] = useState<FlatSettings>(initial);
  return (
    <DsbulkSettingsForm
      values={values}
      derived={derived}
      onChange={(next) => {
        setValues(next);
        onChange?.(next);
      }}
    />
  );
}

// The Advanced accordion renders the whole DSBulk settings surface; under coverage instrumentation
// a single interaction test can exceed the 5s default.
vi.setConfig({ testTimeout: 20_000 });

describe('DsbulkSettingsForm', () => {
  it('opens on the Simple tab with only the simple fields', () => {
    renderWithProviders(<Harness />);
    expect(screen.getByTestId('dsbulk-simple')).toBeInTheDocument();
    expect(screen.queryByTestId('dsbulk-advanced')).not.toBeInTheDocument();
    expect(screen.getByTestId('setting-schema.keyspace')).toBeInTheDocument();
    // An advanced-only setting must not be on the simple tab.
    expect(screen.queryByTestId('setting-codec.locale')).not.toBeInTheDocument();
  });

  it('renders the upstream default as placeholder text, never as a value', () => {
    renderWithProviders(<Harness derived={[]} />);
    const delimiter = screen.getByTestId('setting-connector.csv.delimiter');
    expect(delimiter).toHaveValue('');
    expect(delimiter).toHaveAttribute('placeholder', ',');
  });

  it('shows an auto chip whose tooltip is the server rationale', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const chips = screen.getAllByTestId('auto-chip');
    expect(chips.length).toBeGreaterThan(0);
    await user.hover(chips[0] as HTMLElement);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('clustering key');
  });

  it('clears the auto marker when the field is edited, and reports the override', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} />);

    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Batching/ }));

    const field = screen.getByTestId('setting-batch.maxBatchStatements');
    expect(field).toHaveValue('32');
    expect(within(screen.getByTestId('group-batch')).getAllByTestId('auto-chip')).toHaveLength(2);

    await user.clear(field);
    await user.type(field, '64');

    expect(onChange).toHaveBeenLastCalledWith({ 'batch.maxBatchStatements': '64' });
    expect(screen.getByTestId('setting-batch.maxBatchStatements')).toHaveValue('64');
    // Only `batch.mode` is still auto.
    expect(within(screen.getByTestId('group-batch')).getAllByTestId('auto-chip')).toHaveLength(1);
  });

  it('offers a reset back to the derived value once a field is overridden', async () => {
    renderWithProviders(<Harness initial={{ 'batch.maxBatchStatements': '64' }} />);

    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Batching/ }));

    fireEvent.click(screen.getByRole('button', { name: /Reset Max batch statements to auto/i }));
    expect(screen.getByTestId('setting-batch.maxBatchStatements')).toHaveValue('32');
  });

  it('validates NC multipliers inline without blocking the keystroke', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness derived={[]} />);

    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Schema & mapping/ }));

    const splits = screen.getByTestId('setting-schema.splits');
    await user.type(splits, '8C');
    expect(screen.queryByText(/Expected a number or an NC multiplier/)).not.toBeInTheDocument();

    await user.clear(splits);
    await user.type(splits, 'lots');
    expect(screen.getByText(/Expected a number or an NC multiplier/)).toBeInTheDocument();
    expect(splits).toHaveValue('lots');
  });

  it('renders one accordion panel per DSBulk group', async () => {
    renderWithProviders(<Harness />);
    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));

    for (const group of DSBULK_SETTING_GROUPS) {
      expect(screen.getByTestId(`group-${group}`)).toBeInTheDocument();
    }
  });

  it('honours a restricted group list', async () => {
    renderWithProviders(
      <DsbulkSettingsForm values={{}} onChange={vi.fn()} groups={['batch', 'codec']} />,
    );
    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));

    expect(screen.getByTestId('group-batch')).toBeInTheDocument();
    expect(screen.queryByTestId('group-s3')).not.toBeInTheDocument();
  });

  it('never renders a secret value back into the form', async () => {
    renderWithProviders(
      <DsbulkSettingsForm
        values={{}}
        onChange={vi.fn()}
        derived={[
          // A misbehaving server: a secret must still not be shown.
          { path: 's3.secretAccessKey', value: 'leaked-secret', auto: true },
        ]}
      />,
    );
    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /^S3/ }));

    const secret = screen.getByTestId('setting-s3.secretAccessKey');
    expect(secret).toHaveValue('');
    expect(secret).toHaveAttribute('type', 'password');
    expect(document.body.textContent).not.toContain('leaked-secret');
  });

  it('edits an enum setting through a select', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} derived={[]} />);

    await user.click(screen.getByRole('combobox', { name: 'Batch mode' }));
    await user.click(screen.getByRole('option', { name: 'DISABLED' }));

    expect(onChange).toHaveBeenLastCalledWith({ 'batch.mode': 'DISABLED' });
  });

  it('edits a boolean setting as an explicit tri-state, so “unset” stays distinguishable', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} derived={[]} />);

    await user.click(screen.getByRole('combobox', { name: 'Header row' }));
    await user.click(screen.getByRole('option', { name: 'false' }));
    expect(onChange).toHaveBeenLastCalledWith({ 'connector.csv.header': 'false' });

    await user.click(screen.getByRole('combobox', { name: 'Header row' }));
    await user.click(screen.getByRole('option', { name: /Default \(true\)/ }));
    // "unset" is an empty override, which `unflattenSettings` drops from the request.
    expect(onChange).toHaveBeenLastCalledWith({ 'connector.csv.header': '' });
  });

  it('edits a multi-valued list setting', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness onChange={onChange} derived={[]} />);

    await user.click(screen.getByRole('combobox', { name: 'Statistics modes' }));
    await user.click(screen.getByRole('option', { name: 'global' }));
    await user.click(screen.getByRole('option', { name: 'ranges' }));

    expect(onChange).toHaveBeenLastCalledWith({ 'stats.modes': 'global, ranges' });
  });

  it('links every advanced field to the upstream documentation', async () => {
    renderWithProviders(<Harness derived={[]} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Driver/ }));

    const link = screen.getByRole('link', {
      name: 'Documentation for driver.basic.request.consistency',
    });
    expect(link).toHaveAttribute('href', findSetting('driver.basic.requestConsistency')?.docsUrl);
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener') as unknown as string);
  });

  it('renders every setting of an expanded group', async () => {
    renderWithProviders(<Harness derived={[]} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Advanced' }));
    fireEvent.click(screen.getByRole('button', { name: /Codecs & formats/ }));

    for (const setting of settingsForGroup('codec')) {
      expect(screen.getByTestId(`setting-${setting.path}`)).toBeInTheDocument();
    }
  });
});
