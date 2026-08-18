import { useCallback, useMemo, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { activateLicense, createCheckoutSession, fetchLicenseStatus } from '../api/endpoints';
import { queryKeys } from '../api/queryClient';
import type { LicenseStatus } from '../api/types';
import { CassyxLogo } from '../theme/brand';
import { ActivationScreen } from './ActivationScreen';
import { deriveLicenseAccess, LicenseContext, type LicenseContextValue } from './licenseModel';

export interface LicenseGateProps {
  children: ReactNode;
  /** Test/Storybook seam: bypass the network and drive the gate from a fixed status. */
  statusOverride?: LicenseStatus;
}

/**
 * Gates the whole application (plan §9.1).
 *
 * Three terminal states:
 *  - loading            → a minimal splash (no shell, no API calls behind the gate)
 *  - unlocked / bypass  → renders `children`; bypass additionally forces the persistent banner,
 *                         which is rendered by the shell via `useLicense().showBypassBanner`
 *  - unlicensed/invalid → the activation + purchase screen
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

  const purchase = useCallback(async () => {
    const session = await createCheckoutSession();
    globalThis.location.assign(session.url);
  }, []);

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
  } else if (!access.unlocked) {
    content = (
      <ActivationScreen
        onActivate={activate}
        onPurchase={purchase}
        reason={access.reason}
        busy={activation.isPending}
      />
    );
  } else {
    content = children;
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
