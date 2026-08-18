import { defineConfig, devices } from '@playwright/test';

/**
 * cassyx E2E configuration (plan §11.1/§11.2).
 *
 * The stack is started by `make e2e` (compose, freshly seeded) — Playwright
 * never starts the app itself, so local and CI runs are byte-identical.
 * Traces and video are retained on failure, as §11.1 requires.
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './tests',
  outputDir: './test-results',
  // @bench specs are benchmarks, not gates: excluded from `make e2e`, selected
  // explicitly by `make bench` (E2E_GREP=@bench).
  grep: process.env.E2E_GREP ? new RegExp(process.env.E2E_GREP) : undefined,
  grepInvert: process.env.E2E_GREP ? undefined : /@bench/,
  // Fail the CI build if a test was accidentally committed with .only
  forbidOnly: !!process.env.CI,
  fullyParallel: true,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: process.env.CI
    ? [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }],
       ['junit', { outputFile: 'test-results/junit.xml' }]]
    : [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],

  use: {
    baseURL,
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    // Bulk exports are streamed; give downloads room.
    acceptDownloads: true,
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
