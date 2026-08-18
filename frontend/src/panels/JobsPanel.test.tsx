import { describe, expect, it, vi } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '../test/render';
import type { Job, JobsApi } from '../bulk/jobsApi';
import { JobsPanel } from './JobsPanel';

const RUNNING_ID = '6c8f2a10-b4f9-4a1e-9a12-5f0a7e2d3b44';
const DONE_ID = 'aaaaaaaa-0000-0000-0000-000000000000';

function stubApi(overrides: Partial<JobsApi> = {}): JobsApi {
  return {
    listJobs: vi.fn(async () => ({ items: [], total: 0, limit: 50, offset: 0 })),
    getJob: vi.fn(),
    cancelJob: vi.fn(async () => ({}) as Job),
    deleteJob: vi.fn(async () => undefined),
    fetchLogs: vi.fn(async () => ({ jobId: RUNNING_ID, lines: [] })),
    createUnloadJob: vi.fn(),
    artifactUrl: vi.fn((jobId: string, artifactId?: string) =>
      artifactId ? `/api/jobs/${jobId}/artifact?artifactId=${artifactId}` : `/api/jobs/${jobId}`,
    ),
    ...overrides,
  } as JobsApi;
}

const running: Job = {
  id: RUNNING_ID,
  name: 'Export demo.users',
  type: 'UNLOAD',
  status: 'RUNNING',
  engine: 'NATIVE',
  createdAt: '2026-08-17T12:00:00Z',
  progress: {
    rowsProcessed: 4210000,
    percent: null,
    splitsCompleted: 4103,
    splitsTotal: 10000,
    rowsPerSecond: 186420,
    etaMillis: 31100,
  },
};

const succeeded: Job = {
  id: DONE_ID,
  name: 'Export demo.orders',
  type: 'UNLOAD',
  status: 'SUCCEEDED',
  engine: 'DSBULK',
  createdAt: '2026-08-16T09:00:00Z',
  artifacts: [
    {
      artifactId: 'a1',
      fileName: 'orders.csv',
      sizeBytes: 2147483648,
      contentType: 'text/csv',
      kind: 'DATA',
    },
  ],
};

describe('JobsPanel', () => {
  it('renders the placeholder empty state when there are no jobs', () => {
    renderWithProviders(<JobsPanel jobs={[]} live={false} api={stubApi()} />);
    expect(screen.getByTestId('jobs-panel-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('jobs-panel')).not.toBeInTheDocument();
  });

  it('lists jobs with type, status and engine, and selects the first by default', () => {
    renderWithProviders(<JobsPanel jobs={[running, succeeded]} live={false} api={stubApi()} />);

    expect(screen.getByTestId('jobs-panel')).toBeInTheDocument();
    const row = screen.getByTestId(`job-row-${RUNNING_ID}`);
    expect(within(row).getByText('Export demo.users')).toBeInTheDocument();
    expect(within(row).getByText('NATIVE')).toBeInTheDocument();
    expect(
      within(screen.getByTestId(`job-row-${DONE_ID}`)).getByText('DSBULK'),
    ).toBeInTheDocument();
    expect(screen.getByTestId(`job-row-${RUNNING_ID}`)).toHaveAttribute('aria-pressed', 'true');
  });

  it('drives a determinate bar from splitsCompleted/splitsTotal when percent is null', () => {
    renderWithProviders(<JobsPanel jobs={[running]} live={false} api={stubApi()} />);

    // MUI rounds `aria-valuenow`; the exact 41.03 is asserted on `progressPercent` itself.
    expect(screen.getByTestId('job-progress')).toHaveAttribute('aria-valuenow', '41');
    const readout = screen.getByTestId('job-readout');
    expect(readout).toHaveTextContent('186.4K rows/s');
    expect(readout).toHaveTextContent('ETA 31s');
    expect(readout).toHaveTextContent('4.2M');
  });

  it('goes indeterminate when nothing about progress is known', () => {
    const unknown: Job = { ...running, progress: undefined };
    renderWithProviders(<JobsPanel jobs={[unknown]} live={false} api={stubApi()} />);
    expect(screen.getByTestId('job-progress')).not.toHaveAttribute('aria-valuenow');
  });

  it('offers Cancel only for a live job and calls the API', async () => {
    const user = userEvent.setup();
    const api = stubApi();
    renderWithProviders(<JobsPanel jobs={[running, succeeded]} live={false} api={api} />);

    await user.click(screen.getByTestId('job-cancel'));
    expect(api.cancelJob).toHaveBeenCalledWith(RUNNING_ID);

    await user.click(screen.getByTestId(`job-row-${DONE_ID}`));
    expect(screen.queryByTestId('job-cancel')).not.toBeInTheDocument();
  });

  it('renders the artifact download as a plain anchor, never a fetch of the bytes', async () => {
    const user = userEvent.setup();
    const api = stubApi();
    renderWithProviders(<JobsPanel jobs={[running, succeeded]} live={false} api={api} />);

    expect(screen.queryByTestId('job-download')).not.toBeInTheDocument();

    await user.click(screen.getByTestId(`job-row-${DONE_ID}`));
    const link = screen.getByTestId('job-download');
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', `/api/jobs/${DONE_ID}/artifact?artifactId=a1`);
    expect(link).toHaveAttribute('download', 'orders.csv');
  });

  it('toggles the collapsible log view', async () => {
    const user = userEvent.setup();
    const api = stubApi({
      fetchLogs: vi.fn(async () => ({
        jobId: RUNNING_ID,
        lines: [
          { at: '2026-08-17T12:00:03Z', level: 'INFO' as const, message: 'Operation started' },
        ],
      })),
    });
    renderWithProviders(<JobsPanel jobs={[running]} live={false} api={api} />);

    expect(screen.queryByTestId('job-logs')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('job-logs-toggle'));
    expect(await screen.findByText(/Operation started/)).toBeInTheDocument();
  });

  it('shows the job failure problem', () => {
    const failed: Job = {
      ...running,
      status: 'FAILED',
      error: { type: 'about:blank', title: 'Write failed', status: 500, detail: 'disk full' },
    };
    renderWithProviders(<JobsPanel jobs={[failed]} live={false} api={stubApi()} />);
    expect(screen.getByTestId('job-error')).toHaveTextContent('Write failed — disk full');
  });
});
