/**
 * A process-wide "the licence gate refused us" signal (plan §9.1).
 *
 * WHY A BUS AND NOT PER-CALL HANDLING: `LicenseGateFilter` refuses *every* gated `/api/**`
 * request, so a 402 can surface from a background refetch, a mutation, a poll or an SSE stream -
 * places with no component nearby to render an activation screen. Handling it at each call site
 * guarantees the one site nobody remembers is the one a real customer hits first.
 *
 * Deliberately React-free and framework-free so it can be published from the transport layer
 * (`ApiClient`, `sse.ts`) and consumed by exactly one subscriber (`LicenseGate`).
 */
import type { AppError, LicenseRequiredDetails } from './errors';

export interface LicenseRequiredEvent extends LicenseRequiredDetails {
  /** Method + path of the call that was refused, for diagnostics only. Never a query string. */
  request?: string;
}

export type LicenseRequiredListener = (event: LicenseRequiredEvent) => void;

const listeners = new Set<LicenseRequiredListener>();

/** Subscribe; returns an unsubscribe function suitable for a `useEffect` cleanup. */
export function subscribeToLicenseRequired(listener: LicenseRequiredListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Publish a 402. No-ops for any other error, so callers can pass anything they caught.
 *
 * A throwing listener must not break the API client's error path (the caller still has to receive
 * its rejection), hence the per-listener try/catch.
 */
export function publishLicenseRequired(error: AppError): void {
  const details = error.licenseRequired;
  if (!details) return;
  const event: LicenseRequiredEvent = { ...details };
  if (error.request !== undefined) event.request = error.request;
  for (const listener of [...listeners]) {
    try {
      listener(event);
    } catch {
      // Swallowed on purpose: a broken subscriber cannot be allowed to mask the original 402.
    }
  }
}

/** Test seam only. */
export function resetLicenseRequiredListeners(): void {
  listeners.clear();
}
