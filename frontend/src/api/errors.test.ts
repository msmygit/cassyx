import { describe, expect, it } from 'vitest';
import {
  AppError,
  httpStatusTitle,
  isLicenseRequiredError,
  parseLicenseRequired,
  problemFromResponse,
  toAppError,
  toProblemDetails,
} from './errors';

function problemResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });
}

describe('RFC 9457 problem parsing', () => {
  it('parses a full problem+json body', async () => {
    const error = await problemFromResponse(
      problemResponse(
        {
          type: 'https://cassyx.dev/problems/connection-not-found',
          title: 'Connection not found',
          status: 404,
          detail: 'No connection with id 7f3.',
          code: 'connection.not_found',
        },
        404,
      ),
      'GET /api/connections/7f3',
    );

    expect(error).toBeInstanceOf(AppError);
    expect(error.kind).toBe('http');
    expect(error.status).toBe(404);
    expect(error.isNotFound).toBe(true);
    expect(error.code).toBe('connection.not_found');
    expect(error.userMessage).toBe('No connection with id 7f3.');
    expect(error.request).toBe('GET /api/connections/7f3');
  });

  it('surfaces per-field validation errors', async () => {
    const error = await problemFromResponse(
      problemResponse(
        {
          title: 'Validation failed',
          status: 422,
          errors: { localDatacenter: ['must not be blank'] },
        },
        422,
      ),
    );
    expect(error.fieldErrors.localDatacenter).toEqual(['must not be blank']);
  });

  it('flags 402 as a license problem so the gate can react', async () => {
    const error = await problemFromResponse(
      problemResponse({ title: 'License required', status: 402 }, 402),
    );
    expect(error.isLicenseRequired).toBe(true);
    expect(error.isRetryable).toBe(false);
  });

  it('falls back gracefully for a non-JSON error body (nginx HTML, empty 502)', async () => {
    const response = new Response('<html>502 Bad Gateway</html>', {
      status: 502,
      headers: { 'content-type': 'text/html' },
    });
    const error = await problemFromResponse(response);
    expect(error.status).toBe(502);
    expect(error.message).toBe('Bad gateway');
    expect(error.isRetryable).toBe(true);
  });

  it('falls back when the body claims JSON but is malformed', async () => {
    const response = new Response('{oops', {
      status: 500,
      headers: { 'content-type': 'application/json' },
    });
    const error = await problemFromResponse(response);
    expect(error.status).toBe(500);
    expect(error.problem?.title).toBe('Internal server error');
  });

  it('fills RFC 9457 defaults for a non-object body', () => {
    expect(toProblemDetails('nope', 400)).toEqual({
      type: 'about:blank',
      title: 'Bad request',
      status: 400,
    });
  });

  it('names unknown statuses', () => {
    expect(httpStatusTitle(599)).toBe('Server error');
    expect(httpStatusTitle(418)).toBe('Request failed');
  });
});

/** plan §9.1 — the gate's 402 is the first thing an unlicensed customer meets. */
describe('license gate 402 parsing', () => {
  it('parses a full license problem+json body', async () => {
    const error = await problemFromResponse(
      problemResponse(
        {
          type: 'https://cassyx.dev/problems/license-required',
          title: 'License required',
          status: 402,
          detail: 'Your trial expired on 1 September 2026.',
          instance: '/api/connections',
          state: 'EXPIRED',
          invitesPurchase: true,
          unlockHint: 'Paste a key on the activation screen.',
        },
        402,
      ),
      'GET /api/connections',
    );

    expect(isLicenseRequiredError(error)).toBe(true);
    expect(error.licenseRequired).toEqual({
      state: 'EXPIRED',
      detail: 'Your trial expired on 1 September 2026.',
      invitesPurchase: true,
      unlockHint: 'Paste a key on the activation screen.',
    });
  });

  it('degrades to UNKNOWN for a 402 with no body at all (a proxy can produce one)', async () => {
    const error = await problemFromResponse(new Response(null, { status: 402 }));
    expect(error.licenseRequired).toEqual({
      state: 'UNKNOWN',
      detail: null,
      invitesPurchase: false,
      unlockHint: null,
    });
  });

  it('degrades to UNKNOWN for a 402 that is not problem+json', async () => {
    const error = await problemFromResponse(
      new Response('<html>Payment Required</html>', {
        status: 402,
        headers: { 'content-type': 'text/html' },
      }),
    );
    expect(error.licenseRequired?.state).toBe('UNKNOWN');
    expect(error.userMessage).toBe('License required');
  });

  it('degrades to UNKNOWN for an empty problem+json body', async () => {
    const error = await problemFromResponse(
      new Response('', { status: 402, headers: { 'content-type': 'application/problem+json' } }),
    );
    expect(error.licenseRequired?.state).toBe('UNKNOWN');
  });

  it('degrades to UNKNOWN for a state this build does not recognise', () => {
    const details = parseLicenseRequired(
      { type: 'about:blank', title: 'License required', status: 402, state: 'REVOKED' },
      402,
    );
    expect(details?.state).toBe('UNKNOWN');
  });

  it('ignores extension members of the wrong type rather than trusting them', () => {
    const details = parseLicenseRequired(
      {
        type: 'about:blank',
        title: 'License required',
        status: 402,
        state: 42,
        invitesPurchase: 'yes',
        unlockHint: { hint: 'no' },
      },
      402,
    );
    expect(details).toEqual({
      state: 'UNKNOWN',
      detail: null,
      // A non-boolean must never be read as "offer to sell them something".
      invitesPurchase: false,
      unlockHint: null,
    });
  });

  it('is null for every other status, so nothing else can reach the activation screen', async () => {
    const error = await problemFromResponse(
      problemResponse({ title: 'Forbidden', status: 403, state: 'ABSENT' }, 403),
    );
    expect(error.licenseRequired).toBeNull();
    expect(isLicenseRequiredError(error)).toBe(false);
    expect(isLicenseRequiredError(new Error('boom'))).toBe(false);
  });
});

describe('toAppError', () => {
  it('passes AppError through unchanged', () => {
    const original = new AppError('x', { kind: 'network' });
    expect(toAppError(original)).toBe(original);
  });

  it('classifies an AbortError as aborted and not retryable', () => {
    const error = toAppError(new DOMException('aborted', 'AbortError'));
    expect(error.kind).toBe('aborted');
    expect(error.isRetryable).toBe(false);
  });

  it('classifies a plain Error as a network failure', () => {
    const error = toAppError(new TypeError('Failed to fetch'), 'GET /api/health');
    expect(error.kind).toBe('network');
    expect(error.isRetryable).toBe(true);
    expect(error.request).toBe('GET /api/health');
  });

  it('handles non-Error throwables', () => {
    expect(toAppError('boom').message).toBe('Unknown error');
  });
});
