import { test, expect } from '@playwright/test';

/**
 * Phase 0 smoke journey.
 *
 * This is deliberately the thinnest test that proves the whole stack is wired:
 * nginx serves the SPA on one origin, /api is proxied to the backend, the
 * backend is alive, and it is talking to the seeded Cassandra 5.x cluster.
 *
 * The full §11.2 journey (connect → browse schema → run query → page → edit a
 * cell → export CSV → run an unload job → activate a license) is added by the
 * Phase 1 workstreams as they land their UI; the fixtures those tests need are
 * already in scripts/seed.cql (cassyx_demo.users / app_events / sensor_readings
 * / doc_embeddings / wide_grid / e2e_scratch).
 */

test.describe('cassyx stack smoke', () => {
  test('single origin serves the SPA shell', async ({ page }) => {
    const response = await page.goto('/');
    expect(response?.status(), 'GET / should return 200').toBeLessThan(400);

    // The SPA must actually mount something — an empty body means the build
    // did not make it into the nginx image.
    await expect(page.locator('body')).not.toBeEmpty();
    await expect(page).toHaveTitle(/cassyx/i);
  });

  test('nginx proxies /api to the backend and the backend is healthy', async ({ request }) => {
    const res = await request.get('/api/health');
    expect(res.status(), '/api/health must be reachable through the same origin').toBe(200);
    const body = await res.text();
    expect(body.toLowerCase()).toMatch(/up|ok|healthy/);
  });

  test('the SPA shell loads without console errors or failed requests', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
    page.on('requestfailed', (r) => errors.push(`request failed: ${r.url()}`));

    await page.goto('/', { waitUntil: 'networkidle' });
    expect(errors, `console/network errors on first paint:\n${errors.join('\n')}`).toEqual([]);
  });
});
