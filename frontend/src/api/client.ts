/**
 * Typed fetch wrapper for the cassyx API.
 *
 * Once `npm run gen:api` has produced `src/api/schema.d.ts` from `openapi/cassyx-api.yaml`,
 * callers get their request/response types from `paths` in that file (see `src/api/types.ts`).
 * This module deliberately stays generation-agnostic: it is a thin, well-tested transport.
 */
import { AppError, problemFromResponse, toAppError } from './errors';

export interface ApiClientOptions {
  /** Base URL. Empty string means same-origin. */
  baseUrl?: string;
  /** Default timeout for non-streaming requests, in ms. */
  timeoutMs?: number;
  /** Injectable for tests. */
  fetchImpl?: typeof fetch;
  /** Extra headers applied to every request (e.g. the license key header). */
  headers?: Record<string, string>;
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON body. Mutually exclusive with `formData`. */
  body?: unknown;
  /** multipart/form-data body — used for the Astra secure-connect-bundle upload (plan §3.1). */
  formData?: FormData;
  query?: Record<string, string | number | boolean | undefined | null>;
  signal?: AbortSignal;
  timeoutMs?: number;
  headers?: Record<string, string>;
}

function readEnv(key: string, fallback: string): string {
  const env = import.meta.env as unknown as Record<string, string | undefined>;
  const value = env[key];
  return value === undefined || value === '' ? fallback : value;
}

/** Base URL resolved from the Vite env at module load. Empty string = same-origin. */
export const API_BASE_URL: string = (import.meta.env.VITE_CASSYX_API_BASE_URL ?? '').replace(
  /\/+$/,
  '',
);

export const API_TIMEOUT_MS: number =
  Number.parseInt(readEnv('VITE_CASSYX_API_TIMEOUT_MS', '30000'), 10) || 30_000;

export function buildUrl(baseUrl: string, path: string, query?: RequestOptions['query']): string {
  const normalisedPath = path.startsWith('/') ? path : `/${path}`;
  let url = `${baseUrl}${normalisedPath}`;
  if (query) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null) continue;
      params.append(key, String(value));
    }
    const qs = params.toString();
    if (qs) url += `?${qs}`;
  }
  return url;
}

/** Query strings can contain identifiers; keep them out of error text and logs. */
function describeRequest(method: string, path: string): string {
  return `${method} ${path.split('?')[0]}`;
}

export class ApiClient {
  readonly baseUrl: string;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;
  private readonly baseHeaders: Record<string, string>;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/+$/, '');
    this.timeoutMs = options.timeoutMs ?? API_TIMEOUT_MS;
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.baseHeaders = options.headers ?? {};
  }

  /** Perform a request and decode the JSON body. `204`/empty bodies resolve to `undefined`. */
  async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    const requestLabel = describeRequest(method, path);
    const url = buildUrl(this.baseUrl, path, options.query);

    const headers: Record<string, string> = {
      Accept: 'application/json, application/problem+json',
      ...this.baseHeaders,
      ...options.headers,
    };

    let body: BodyInit | undefined;
    if (options.formData) {
      body = options.formData; // browser sets the multipart boundary
    } else if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(options.body);
    }

    const timeoutMs = options.timeoutMs ?? this.timeoutMs;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const onOuterAbort = () => controller.abort();
    options.signal?.addEventListener('abort', onOuterAbort);

    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body,
        signal: controller.signal,
        credentials: 'same-origin',
      });
    } catch (error) {
      throw toAppError(error, requestLabel);
    } finally {
      clearTimeout(timer);
      options.signal?.removeEventListener('abort', onOuterAbort);
    }

    if (!response.ok) {
      throw await problemFromResponse(response, requestLabel);
    }

    if (response.status === 204) return undefined as T;

    const contentType = response.headers.get('content-type') ?? '';
    if (!contentType.includes('json')) return undefined as T;

    try {
      return (await response.json()) as T;
    } catch (error) {
      throw new AppError('Malformed JSON in response', {
        kind: 'parse',
        status: response.status,
        cause: error,
        request: requestLabel,
      });
    }
  }

  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<T> {
    return this.request<T>(path, { ...options, method: 'GET' });
  }

  post<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method'>): Promise<T> {
    return this.request<T>(path, { ...options, method: 'POST', body });
  }

  put<T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method'>): Promise<T> {
    return this.request<T>(path, { ...options, method: 'PUT', body });
  }

  delete<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<T> {
    return this.request<T>(path, { ...options, method: 'DELETE' });
  }

  /** Multipart upload — used by the SCB `UPLOAD` acquisition mode. */
  upload<T>(
    path: string,
    formData: FormData,
    options?: Omit<RequestOptions, 'method'>,
  ): Promise<T> {
    return this.request<T>(path, { ...options, method: 'POST', formData });
  }

  /** Absolute URL for a streaming download / SSE endpoint. */
  url(path: string, query?: RequestOptions['query']): string {
    return buildUrl(this.baseUrl, path, query);
  }
}

/** Shared singleton used by the app; tests construct their own with a stub `fetchImpl`. */
export const apiClient = new ApiClient();
