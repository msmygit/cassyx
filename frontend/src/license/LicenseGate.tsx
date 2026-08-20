import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  activateLicense,
  createCheckoutSession,
  fetchLicenseStatus,
  requestTrial,
} from '../api/endpoints';
import { queryKeys } from '../api/queryClient';
import { isLicenseRequiredError } from '../api/errors';
import { subscribeToLicenseRequired } from '../api/licenseSignal';
import type { LicenseStatus, TrialRequest } from '../api/types';
import { CassyxLogo } from '../theme/brand';
import { ActivationScreen } from './ActivationScreen';
import { deriveLicenseAccess, LicenseContext, type LicenseContextValue } from './licenseModel';

export interface LicenseGateProps {
  children: ReactNode;
  /** Test/Storybook seam: bypass the network and drive the gate from a fixed status. */
  statusOverride?: LicenseStatus;
}

/**
 * Floor between two gate-triggered licence re-checks.
 *
 * `/api/license` is ungated so it can never itself 402, but a shell full of queries can emit a
 * burst of refusals, and a stale-but-unlocked cached status would otherwise let refusal → refetch
 * → refusal run as fast as the network allows. In-flight coalescing plus this floor make the
 * worst case one extra request per interval.
 */
export const GATE_REFRESH_MIN_INTERVAL_MS = 5_000;

/**
 * Gates the whole application (plan §9.1).
 *
 * Three terminal states:
 *  - loading            → a minimal splash (no shell, no API calls behind the gate)
 *  - unlocked / bypass  → renders `children`; bypass additionally forces the persistent banner,
 *                         which is rendered by the shell via `useLicense().showBypassBanner`
 *  - unlicensed/invalid → the activation + purchase screen
 *
 * It also owns the app-wide reaction to a `402` from the server-side gate: any gated `/api/**`
 * call can be refused at any moment (a trial lapsing mid-session is the everyday case), the
 * transport publishes it, and this component re-reads the licence and switches screens.
 */
export function LicenseGate({ children, statusOverride }: LicenseGateProps) {
  const queryClient = useQueryClient();

  const query = useQuery({
    queryKey: queryKeys.license,
    queryFn: () => fetchLicenseStatus(),
    enabled: statusOverride === undefined,
    staleTime: 60_000,
  });

  const status = statusOverride ?? query.data ?? null;
  const access = useMemo(() => deriveLicenseAccess(status), [status]);

  const activation = useMutation({
    mutationFn: (licenseKey: string) => activateLicense({ licenseKey }),
    onSuccess: (next) => {
      queryClient.setQueryData(queryKeys.license, next);
    },
  });

  const activate = useCallback(
    async (licenseKey: string) => {
      await activation.mutateAsync(licenseKey);
    },
    [activation],
  );

  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: queryKeys.license });
  }, [queryClient]);

  // --------------------------------------------------------------- reacting to a 402 (plan §9.1)

  const gateRefreshRef = useRef<Promise<unknown> | null>(null);
  const lastGateRefreshRef = useRef(0);

  useEffect(() => {
    if (statusOverride !== undefined) return;
    return subscribeToLicenseRequired(() => {
      // The 402 body carries a `state`, but we deliberately do NOT render from it: `LicenseGate`
      // (backend) and `GET /api/license` are one bean and required never to disagree (plan §9.1),
      // so the status endpoint is the single source the screens read. The 402 is the trigger.
      if (gateRefreshRef.current) return;
      const now = Date.now();
      if (now - lastGateRefreshRef.current < GATE_REFRESH_MIN_INTERVAL_MS) return;
      lastGateRefreshRef.current = now;
      gateRefreshRef.current = queryClient
        .invalidateQueries({ queryKey: queryKeys.license })
        .finally(() => {
          gateRefreshRef.current = null;
        });
    });
  }, [queryClient, statusOverride]);

  // Once the shell has been mounted we keep it mounted behind the activation screen. A 402 on a
  // background refetch would otherwise unmount the workspace and throw away whatever the user had
  // typed into the query editor - a locked instance is a reason to stop them saving, not a reason
  // to delete their work. Nothing behind the gate is reachable while it is hidden.
  const [shellMounted, setShellMounted] = useState(false);
  useEffect(() => {
    if (access.unlocked) setShellMounted(true);
  }, [access.unlocked]);

  // Recovery in place (no reload): when the gate opens, retry everything it previously refused.
  const wasLockedRef = useRef(true);
  useEffect(() => {
    if (!access.unlocked) {
      wasLockedRef.current = true;
      return;
    }
    if (!wasLockedRef.current) return;
    wasLockedRef.current = false;
    void queryClient.invalidateQueries({
      predicate: (query) => isLicenseRequiredError(query.state.error),
      refetchType: 'all',
    });
  }, [access.unlocked, queryClient]);

  const purchase = useCallback(async (email?: string) => {
    const session = await createCheckoutSession(email);
    globalThis.location.assign(session.url);
  }, []);

  const trialMutation = useMutation({
    mutationFn: (request: TrialRequest) => requestTrial(request),
    onSuccess: (next) => {
      queryClient.setQueryData(queryKeys.license, next);
    },
  });

  const startTrial = useCallback(
    async (request: TrialRequest) => {
      await trialMutation.mutateAsync(request);
    },
    [trialMutation],
  );

  const value = useMemo<LicenseContextValue>(
    () => ({
      ...access,
      status,
      loading: query.isLoading,
      error: (query.error as Error | null) ?? null,
      activate,
      refresh,
    }),
    [access, status, query.isLoading, query.error, activate, refresh],
  );

  let content: ReactNode;
  if (statusOverride === undefined && query.isLoading) {
    content = <LicenseSplash />;
  } else if (statusOverride === undefined && query.isError && !status) {
    content = <LicenseUnavailable onRetry={() => void refresh()} error={query.error as Error} />;
  } else {
    // The shell always sits in the SAME slot of the tree, whether it is showing or parked -
    // moving it would remount it, which is exactly the lost-work problem `shellMounted` exists to
    // avoid. `display: contents` keeps the wrapper out of the layout while unlocked.
    const shell =
      access.unlocked || shellMounted ? (
        <Box
          data-testid="license-shell"
          aria-hidden={!access.unlocked}
          sx={{ display: access.unlocked ? 'contents' : 'none' }}
        >
          {children}
        </Box>
      ) : null;

    content = (
      <>
        {shell}
        {access.unlocked ? null : (
          <ActivationScreen
            detail={access.detail}
            onActivate={activate}
            onPurchase={purchase}
            onStartTrial={startTrial}
            activateBusy={activation.isPending}
            trialBusy={trialMutation.isPending}
            trialError={(trialMutation.error as Error | null) ?? null}
          />
        )}
      </>
    );
  }

  return <LicenseContext.Provider value={value}>{content}</LicenseContext.Provider>;
}

function LicenseSplash() {
  return (
    <Box
      data-testid="license-splash"
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'background.default',
      }}
    >
      <Stack spacing={2} alignItems="center">
        <CassyxLogo />
        <CircularProgress size={22} />
        <Typography variant="caption" color="text.secondary">
          Checking license…
        </Typography>
      </Stack>
    </Box>
  );
}

function LicenseUnavailable({ onRetry, error }: { onRetry: () => void; error: Error | null }) {
  return (
    <Box
      data-testid="license-unavailable"
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: 3,
        bgcolor: 'background.default',
      }}
    >
      <Stack spacing={2} sx={{ maxWidth: 520 }}>
        <CassyxLogo />
        <Alert severity="error">
          Could not reach the cassyx API to check the license.
          {error?.message ? ` (${error.message})` : ''}
        </Alert>
        <Typography variant="body2" color="text.secondary">
          The backend may still be starting. This check is local — it does not require internet
          access.
        </Typography>
        <Button variant="contained" onClick={onRetry} sx={{ alignSelf: 'flex-start' }}>
          Retry
        </Button>
      </Stack>
    </Box>
  );
}
