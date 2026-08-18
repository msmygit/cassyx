/**
 * RFC 9457 (`application/problem+json`) error handling.
 *
 * The cassyx API returns a `Problem` body for *every* error response. We parse it into a single
 * typed `AppError` so UI code never has to branch on `Response.ok` / `instanceof TypeError` /
 * shape-sniffing a JSON body.
 */

/** RFC 9457 problem details object, plus the cassyx extension members. */
export interface ProblemDetails {
  /** URI reference identifying the problem type. Defaults to `about:blank`. */
  type: string;
  /** Short, human-readable summary. */
  title: string;
  /** HTTP status code. */
  status: number;
  /** Human-readable explanation specific to this occurrence. */
  detail?: string;
  /** URI reference identifying the specific occurrence. */
  instance?: string;
  /** cassyx extension: stable machine-readable error code, e.g. `connection.not_found`. */
  code?: string;
  /** cassyx extension: per-field validation messages. */
  errors?: Record<string, string[]>;
  /** Any further extension members. */
  [key: string]: unknown;
}

export type AppErrorKind =
  /** Server responded with a problem+json (or other) error body. */
  | 'http'
  /** Request never completed: DNS, offline, CORS, connection reset. */
  | 'network'
  /** Client-side timeout or user cancellation via AbortSignal. */
  | 'aborted'
  /** Response arrived but could not be decoded as expected. */
  | 'parse';

/** Extra context attached at throw sites. Never put credentials in here. */
export interface AppErrorInit {
  kind: AppErrorKind;
  status?: number;
  problem?: ProblemDetails;
  cause?: unknown;
  /** Request method + path, for logging. Query strings are stripped by the client. */
  request?: string;
}

/**
 * The single error type the whole app deals with.
 *
 * SECURITY: never construct an AppError with a credential in the message. The API client
 * deliberately strips query strings and never echoes request bodies.
 */
export class AppError extends Error {
  readonly kind: AppErrorKind;
  readonly status: number;
  readonly problem?: ProblemDetails;
  readonly request?: string;

  constructor(message: string, init: AppErrorInit) {
    super(message, init.cause !== undefined ? { cause: init.cause } : undefined);
    this.name = 'AppError';
    this.kind = init.kind;
    this.status = init.status ?? 0;
    this.problem = init.problem;
    this.request = init.request;
  }

  /** Stable machine-readable code when the server supplied one. */
  get code(): string | undefined {
    return this.problem?.code;
  }

  /** Per-field validation messages, if this was a 422/400 validation problem. */
  get fieldErrors(): Record<string, string[]> {
    return this.problem?.errors ?? {};
  }

  /** 402 — the license gate rejected the request (plan §9.1). */
  get isLicenseRequired(): boolean {
    return this.status === 402;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** Retrying is plausible: transient network, timeout, 5xx, or 429. */
  get isRetryable(): boolean {
    if (this.kind === 'network') return true;
    if (this.kind === 'aborted') return false;
    return this.status === 429 || this.status >= 500;
  }

  /** Message suitable for direct display to a user. */
  get userMessage(): string {
    return this.problem?.detail ?? this.problem?.title ?? this.message;
  }
}

const PROBLEM_MEDIA_TYPE = 'application/problem+json';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Coerce an arbitrary parsed body into a `ProblemDetails`, filling RFC 9457 defaults. */
export function toProblemDetails(body: unknown, status: number): ProblemDetails {
  if (!isRecord(body)) {
    return { type: 'about:blank', title: httpStatusTitle(status), status };
  }
  const problem: ProblemDetails = {
    ...body,
    type: typeof body.type === 'string' ? body.type : 'about:blank',
    title: typeof body.title === 'string' ? body.title : httpStatusTitle(status),
    status: typeof body.status === 'number' ? body.status : status,
  };
  if (typeof body.detail === 'string') problem.detail = body.detail;
  if (typeof body.instance === 'string') problem.instance = body.instance;
  if (typeof body.code === 'string') problem.code = body.code;
  return problem;
}

/**
 * Read an error `Response` and build an `AppError`.
 *
 * Handles the three real-world cases: a proper problem+json body, a JSON body that isn't
 * problem-shaped, and a non-JSON body (nginx HTML error pages, empty 502s).
 */
export async function problemFromResponse(response: Response, request?: string): Promise<AppError> {
  const contentType = response.headers.get('content-type') ?? '';
  let problem: ProblemDetails;

  if (contentType.includes(PROBLEM_MEDIA_TYPE) || contentType.includes('application/json')) {
    try {
      problem = toProblemDetails(await response.json(), response.status);
    } catch (cause) {
      problem = {
        type: 'about:blank',
        title: httpStatusTitle(response.status),
        status: response.status,
      };
      void cause;
    }
  } else {
    problem = {
      type: 'about:blank',
      title: httpStatusTitle(response.status),
      status: response.status,
    };
  }

  const message = problem.detail ?? problem.title;
  return new AppError(message, {
    kind: 'http',
    status: problem.status,
    problem,
    request,
  });
}

/** Normalise anything thrown by `fetch` (or by us) into an `AppError`. */
export function toAppError(error: unknown, request?: string): AppError {
  if (error instanceof AppError) return error;
  if (error instanceof DOMException && error.name === 'AbortError') {
    return new AppError('Request cancelled', { kind: 'aborted', cause: error, request });
  }
  if (error instanceof Error) {
    return new AppError(error.message || 'Network request failed', {
      kind: 'network',
      cause: error,
      request,
    });
  }
  return new AppError('Unknown error', { kind: 'network', cause: error, request });
}

const STATUS_TITLES: Record<number, string> = {
  400: 'Bad request',
  401: 'Unauthorized',
  402: 'License required',
  403: 'Forbidden',
  404: 'Not found',
  409: 'Conflict',
  422: 'Validation failed',
  429: 'Too many requests',
  500: 'Internal server error',
  502: 'Bad gateway',
  503: 'Service unavailable',
  504: 'Gateway timeout',
};

export function httpStatusTitle(status: number): string {
  return STATUS_TITLES[status] ?? (status >= 500 ? 'Server error' : 'Request failed');
}
