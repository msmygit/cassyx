import { useState, type FormEvent } from 'react';
import Alert from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Divider from '@mui/material/Divider';
import Link from '@mui/material/Link';
import Paper from '@mui/material/Paper';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Typography from '@mui/material/Typography';
import CircularProgress from '@mui/material/CircularProgress';
import ShoppingCartRoundedIcon from '@mui/icons-material/ShoppingCartRounded';
import { CassyxLogo } from '../theme/brand';
import { looksLikeLicenseKey } from './licenseModel';

export interface ActivationScreenProps {
  onActivate: (licenseKey: string) => Promise<void>;
  onPurchase?: () => Promise<void> | void;
  /** Reason the current key was rejected, if any. */
  reason?: string | null;
  busy?: boolean;
}

/**
 * Unlicensed state (plan §9.1): activation + purchase.
 *
 * Verification is offline (Ed25519 against an embedded public key), so this screen works in an
 * air-gapped install — the purchase button is the only thing that needs egress and it degrades to
 * a plain link.
 */
export function ActivationScreen({ onActivate, onPurchase, reason, busy }: ActivationScreenProps) {
  const [licenseKey, setLicenseKey] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const malformed = licenseKey.trim().length > 0 && !looksLikeLicenseKey(licenseKey);

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await onActivate(licenseKey.trim());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Activation failed.');
    } finally {
      setSubmitting(false);
    }
  };

  const pending = Boolean(busy) || submitting;

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        bgcolor: 'background.default',
        p: 3,
      }}
    >
      <Paper
        elevation={0}
        variant="outlined"
        sx={{ width: '100%', maxWidth: 520, p: 4 }}
        data-testid="activation-screen"
      >
        <Stack spacing={3}>
          <CassyxLogo />

          <Box>
            <Typography variant="h2" gutterBottom>
              Activate cassyx
            </Typography>
            <Typography variant="body2" color="text.secondary">
              cassyx is a one-time purchase — every feature, no tiers. Paste the license key from
              your purchase email to unlock this instance. Verification happens locally; no data
              leaves your network.
            </Typography>
          </Box>

          {reason && <Alert severity="warning">{reason}</Alert>}
          {error && <Alert severity="error">{error}</Alert>}

          <Box component="form" onSubmit={handleSubmit} noValidate>
            <Stack spacing={2}>
              <TextField
                label="License key"
                placeholder="eyJsaWMiOiJDU1gt….ZmFrZXNpZ25hdHVyZQ"
                value={licenseKey}
                onChange={(event) => setLicenseKey(event.target.value)}
                multiline
                minRows={3}
                fullWidth
                autoFocus
                error={malformed}
                helperText={
                  malformed
                    ? 'That does not look like a cassyx key (expected payload.signature, base64url).'
                    : 'Format: base64url payload, a dot, then the Ed25519 signature.'
                }
                slotProps={{ htmlInput: { spellCheck: false, 'data-testid': 'license-key-input' } }}
              />
              <Button
                type="submit"
                variant="contained"
                size="large"
                disabled={pending || licenseKey.trim().length === 0 || malformed}
                startIcon={pending ? <CircularProgress size={16} color="inherit" /> : undefined}
              >
                {pending ? 'Verifying…' : 'Activate'}
              </Button>
            </Stack>
          </Box>

          <Divider flexItem>
            <Typography variant="caption" color="text.secondary">
              no key yet?
            </Typography>
          </Divider>

          <Button
            variant="outlined"
            startIcon={<ShoppingCartRoundedIcon />}
            onClick={() => void onPurchase?.()}
            disabled={!onPurchase || pending}
          >
            Purchase a license
          </Button>

          <Typography variant="caption" color="text.secondary">
            Self-hosting, CI or an enterprise site license? Start the server with{' '}
            <Box component="code" sx={{ fontFamily: 'monospace' }}>
              CASSYX_LICENSE_ENFORCE=false
            </Box>{' '}
            to bypass licensing entirely. Bypassed instances show a permanent banner.{' '}
            <Link href="https://cassyx.dev/license" target="_blank" rel="noreferrer">
              License terms
            </Link>
          </Typography>
        </Stack>
      </Paper>
    </Box>
  );
}
