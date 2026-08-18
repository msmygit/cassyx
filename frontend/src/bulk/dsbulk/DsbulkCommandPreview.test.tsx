import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../../test/render';
import { DsbulkCommandPreview } from './DsbulkCommandPreview';
import type { BulkCommandPreview } from './dsbulkApi';

const PREVIEW: BulkCommandPreview = {
  command: 'dsbulk load -k demo -t users -f /var/lib/cassyx/jobs/6c8f2a10/dsbulk.conf',
  argv: ['load', '-k', 'demo', '-t', 'users', '-f', '/var/lib/cassyx/jobs/6c8f2a10/dsbulk.conf'],
  hocon: 'dsbulk {\n  connector.name = csv\n  s3.secretAccessKey = "***"\n}\n',
  maskedFields: ['s3.secretAccessKey', 'driver.advanced.auth-provider.password'],
};

describe('DsbulkCommandPreview', () => {
  it('prompts for a target before there is anything to preview', () => {
    renderWithProviders(<DsbulkCommandPreview preview={undefined} />);
    expect(screen.getByTestId('command-preview-empty')).toBeInTheDocument();
  });

  it('shows a loading state while the debounced request is in flight', () => {
    renderWithProviders(<DsbulkCommandPreview preview={undefined} loading />);
    expect(screen.getByTestId('command-preview-loading')).toBeInTheDocument();
  });

  it('renders the command, the argv and the generated HOCON', () => {
    renderWithProviders(<DsbulkCommandPreview preview={PREVIEW} />);
    expect(screen.getByTestId('command-line')).toHaveTextContent('dsbulk load -k demo -t users');
    expect(screen.getByTestId('command-argv')).toHaveTextContent('load -k demo -t users -f');
    expect(screen.getByTestId('command-hocon')).toHaveTextContent('connector.name = csv');
  });

  it('states explicitly which fields were masked', () => {
    renderWithProviders(<DsbulkCommandPreview preview={PREVIEW} />);
    const masked = screen.getByTestId('masked-fields');
    expect(masked).toHaveTextContent('s3.secretAccessKey');
    expect(masked).toHaveTextContent('driver.advanced.auth-provider.password');
  });

  it('omits the masking notice when nothing was redacted', () => {
    renderWithProviders(<DsbulkCommandPreview preview={{ ...PREVIEW, maskedFields: [] }} />);
    expect(screen.queryByTestId('masked-fields')).not.toBeInTheDocument();
  });

  it('copies the command and the HOCON to the clipboard', async () => {
    // `userEvent.setup()` installs its own clipboard stub, so spy AFTER it.
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    renderWithProviders(<DsbulkCommandPreview preview={PREVIEW} />);

    await user.click(screen.getByRole('button', { name: /copy command/i }));
    expect(writeText).toHaveBeenLastCalledWith(PREVIEW.command);
    expect(await screen.findByTestId('copy-confirmation')).toHaveTextContent('command');

    await user.click(screen.getByRole('button', { name: /copy hocon/i }));
    expect(writeText).toHaveBeenLastCalledWith(PREVIEW.hocon);
  });

  it('reports nothing when the clipboard is unavailable rather than throwing', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    renderWithProviders(<DsbulkCommandPreview preview={PREVIEW} />);

    await user.click(screen.getByRole('button', { name: /copy command/i }));
    expect(screen.queryByTestId('copy-confirmation')).not.toBeInTheDocument();
  });

  it('offers the HOCON as a downloadable .conf artifact', () => {
    renderWithProviders(<DsbulkCommandPreview preview={PREVIEW} fileName="load-users.conf" />);
    const link = screen.getByRole('link', { name: /download \.conf/i });
    expect(link).toHaveAttribute('download', 'load-users.conf');
    expect(link.getAttribute('href')).toContain(encodeURIComponent('connector.name = csv'));
  });
});
