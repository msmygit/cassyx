import { useState, type FormEvent, type ReactNode } from 'react';
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
import RocketLaunchRoundedIcon from '@mui/icons-material/RocketLaunchRounded';
import { CassyxLogo } from '../theme/brand';
import { looksLikeLicenseKey, type LicenseAccessDetail } from './licenseModel';
import type { TrialRequest } from '../api/types';

export interface ActivationScreenProps {
  detail: LicenseAccessDetail;
  onActivate: (licenseKey: string) => Promise<void>;
  onPurchase?: (email?: string) => Promise<void> | void;
  onStartTrial?: (request: TrialRequest) => Promise<void>;
  activateBusy?: boolean;
  trialBusy?: boolean;
  trialError?: Error | null;
}

/**
 * Unlicensed states (plan §9.1/§9.4/§9.5): one screen per `LicenseState`, because collapsing
 * "expired customer" and "tampered key" into the same locked box throws away the product's only
 * conversion moment. `LicenseGate` never mounts this while `access.unlocked` is true, so `VALID`
 * and `BYPASS` fall through to the same neutral screen as an unrecognised `state` - belt and
 * braces, not an expected path.
 *
 * Verification is offline (Ed25519 against an embedded public key), so this screen works in an
 * air-gapped install - the purchase/trial buttons are the only things that need egress and they
 * degrade to plain errors rather than hanging.
 */
export function ActivationScreen(props: ActivationScreenProps) {
  const { detail } = props;
  let body: ReactNode;
  switch (detail.state) {
    case 'ABSENT':
      body = <AbsentState {...props} />;
      break;
    case 'EXPIRED':
      body = <ExpiredState {...props} detail={detail} />;
      break;
    case 'UPGRADE_REQUIRED':
      body = <UpgradeRequiredState {...props} detail={detail} />;
      break;
    case 'MALFORMED':
      body = <MalformedState {...props} detail={detail} />;
      break;
    case 'INVALID_SIGNATURE':
      body = <InvalidSignatureState {...props} detail={detail} />;
      break;
    case 'VALID':
    case 'BYPASS':
    case 'UNKNOWN':
    default:
      body = <UnknownState {...props} />;
      break;
  }

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
          {body}
        </Stack>
      </Paper>
    </Box>
  );
}

/* ---------------------------------------------------------------------- shared building blocks */

function BypassHint() {
  return (
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
  );
}

interface KeyActivationFormProps {
  onActivate: (licenseKey: string) => Promise<void>;
  busy?: boolean;
  submitLabel?: string;
  autoFocus?: boolean;
}

/** The "paste a key" form, reused by every state that can still be resolved with a key. */
function KeyActivationForm({
  onActivate,
  busy,
  submitLabel = 'Activate',
  autoFocus = true,
}: KeyActivationFormProps) {
  const [licenseKey, setLicenseKey] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const malformed = licenseKey.trim().length > 0 && !looksLikeLicenseKey(licenseKey);
  const pending = Boolean(busy) || submitting;

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

  return (
    <Box component="form" onSubmit={handleSubmit} noValidate>
      <Stack spacing={2}>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField
          label="License key"
          placeholder="eyJsaWMiOiJDU1gt….ZmFrZXNpZ25hdHVyZQ"
          value={licenseKey}
          onChange={(event) => setLicenseKey(event.target.value)}
          multiline
          minRows={3}
          fullWidth
          autoFocus={autoFocus}
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
          {pending ? 'Verifying…' : submitLabel}
        </Button>
      </Stack>
    </Box>
  );
}

interface PurchaseButtonProps {
  onPurchase?: (email?: string) => Promise<void> | void;
  email?: string | null;
  label?: string;
  variant?: 'contained' | 'outlined';
}

function PurchaseButton({
  onPurchase,
  email,
  label = 'Purchase a license',
  variant = 'outlined',
}: PurchaseButtonProps) {
  const [pending, setPending] = useState(false);
  return (
    <Button
      variant={variant}
      startIcon={<ShoppingCartRoundedIcon />}
      onClick={async () => {
        setPending(true);
        try {
          await onPurchase?.(email ?? undefined);
        } finally {
          setPending(false);
        }
      }}
      disabled={!onPurchase || pending}
    >
      {label}
    </Button>
  );
}

/* ---------------------------------------------------------------------------------- ABSENT */

function AbsentState({
  onActivate,
  onPurchase,
  onStartTrial,
  activateBusy,
  trialBusy,
  trialError,
}: ActivationScreenProps) {
  const [showKeyForm, setShowKeyForm] = useState(false);
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [trialSubmitError, setTrialSubmitError] = useState<string | null>(null);

  const handleTrial = async (event: FormEvent) => {
    event.preventDefault();
    setTrialSubmitError(null);
    try {
      await onStartTrial?.({ email: email.trim(), name: name.trim() || undefined });
    } catch (cause) {
      setTrialSubmitError(cause instanceof Error ? cause.message : 'Could not start the trial.');
    }
  };

  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          Welcome to cassyx
        </Typography>
        <Typography variant="body2" color="text.secondary">
          cassyx is a one-time purchase - every feature, no tiers. Try it free for 14 days against
          your own cluster, no card required, or activate a key you already have.
        </Typography>
      </Box>

      {onStartTrial && (
        <Box component="form" onSubmit={handleTrial} noValidate>
          <Stack spacing={2}>
            {(trialSubmitError || trialError) && (
              <Alert severity="error">{trialSubmitError ?? trialError?.message}</Alert>
            )}
            <TextField
              label="Work email"
              type="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              fullWidth
              autoFocus
              slotProps={{ htmlInput: { 'data-testid': 'trial-email-input' } }}
            />
            <TextField
              label="Company (optional)"
              value={name}
              onChange={(event) => setName(event.target.value)}
              fullWidth
            />
            <Button
              type="submit"
              variant="contained"
              size="large"
              startIcon={
                trialBusy ? (
                  <CircularProgress size={16} color="inherit" />
                ) : (
                  <RocketLaunchRoundedIcon />
                )
              }
              disabled={trialBusy || email.trim().length === 0}
            >
              {trialBusy ? 'Starting trial…' : 'Start 14-day trial'}
            </Button>
          </Stack>
        </Box>
      )}

      <Divider flexItem>
        <Typography variant="caption" color="text.secondary">
          or
        </Typography>
      </Divider>

      <PurchaseButton onPurchase={onPurchase} />

      {showKeyForm ? (
        <KeyActivationForm onActivate={onActivate} busy={activateBusy} autoFocus={false} />
      ) : (
        <Link component="button" type="button" variant="body2" onClick={() => setShowKeyForm(true)}>
          I already have a license key
        </Link>
      )}

      <BypassHint />
    </>
  );
}

/* --------------------------------------------------------------------------------- EXPIRED */

function ExpiredState({
  onActivate,
  onPurchase,
  activateBusy,
  detail,
}: ActivationScreenProps & { detail: Extract<LicenseAccessDetail, { state: 'EXPIRED' }> }) {
  const greeting = detail.name ? `Welcome back, ${detail.name}` : 'Your license has expired';
  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          {greeting}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {detail.expires
            ? `Your cassyx license lapsed on ${detail.expires}. Purchasing keeps everything you already
               have configured - connections, jobs and workspace all stay put.`
            : 'Your cassyx license has lapsed. Purchasing keeps everything you already have configured.'}
        </Typography>
      </Box>

      <PurchaseButton
        onPurchase={onPurchase}
        email={detail.email}
        label="Renew with a purchase"
        variant="contained"
      />

      <Divider flexItem>
        <Typography variant="caption" color="text.secondary">
          have a new key already?
        </Typography>
      </Divider>

      <KeyActivationForm onActivate={onActivate} busy={activateBusy} autoFocus={false} />
      <BypassHint />
    </>
  );
}

/* -------------------------------------------------------------------------- UPGRADE_REQUIRED */

function UpgradeRequiredState({
  onActivate,
  onPurchase,
  activateBusy,
}: ActivationScreenProps & {
  detail: Extract<LicenseAccessDetail, { state: 'UPGRADE_REQUIRED' }>;
}) {
  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          A paid upgrade unlocks this version
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Your license is genuine and still works fine on the version it was purchased for - nothing
          about it has stopped working. This build is a newer major version, which is a paid upgrade
          rather than something your existing key covers.
        </Typography>
      </Box>

      <Alert severity="info">
        Keep running your current version with your existing key, or purchase the upgrade to move to
        this one.
      </Alert>

      <PurchaseButton onPurchase={onPurchase} label="Purchase the upgrade" variant="contained" />

      <Divider flexItem>
        <Typography variant="caption" color="text.secondary">
          already purchased the upgrade?
        </Typography>
      </Divider>

      <KeyActivationForm onActivate={onActivate} busy={activateBusy} autoFocus={false} />
      <BypassHint />
    </>
  );
}

/* -------------------------------------------------------------------------------- MALFORMED */

function MalformedState({
  onActivate,
  activateBusy,
  detail,
}: ActivationScreenProps & { detail: Extract<LicenseAccessDetail, { state: 'MALFORMED' }> }) {
  if (detail.operatorIssue) {
    return (
      <>
        <Box>
          <Typography variant="h2" gutterBottom>
            This server is not configured for licensing
          </Typography>
          <Typography variant="body2" color="text.secondary">
            This is not something a license key can fix - it is a deployment setting your
            administrator controls.
          </Typography>
        </Box>
        <Alert severity="error">{detail.message}</Alert>
      </>
    );
  }

  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          That key could not be read
        </Typography>
        <Typography variant="body2" color="text.secondary">
          The key you pasted is not shaped like a cassyx license (expected base64url
          payload.signature). Check it was copied in full, then try again.
        </Typography>
      </Box>
      {detail.message && <Alert severity="warning">{detail.message}</Alert>}
      <KeyActivationForm onActivate={onActivate} busy={activateBusy} />
    </>
  );
}

/* ----------------------------------------------------------------------- INVALID_SIGNATURE */

function InvalidSignatureState({
  onActivate,
  onPurchase,
  activateBusy,
  detail,
}: ActivationScreenProps & {
  detail: Extract<LicenseAccessDetail, { state: 'INVALID_SIGNATURE' }>;
}) {
  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          That key doesn't check out
        </Typography>
        <Typography variant="body2" color="text.secondary">
          The key is readable but was not signed by cassyx. Double-check it was copied in full and
          without extra whitespace - a partial paste or an extra character is the usual cause.
        </Typography>
      </Box>
      {detail.message && <Alert severity="warning">{detail.message}</Alert>}
      <KeyActivationForm onActivate={onActivate} busy={activateBusy} />

      <Divider flexItem>
        <Typography variant="caption" color="text.secondary">
          no key yet?
        </Typography>
      </Divider>
      <PurchaseButton onPurchase={onPurchase} />
      <BypassHint />
    </>
  );
}

/* ------------------------------------------------------------------------- UNKNOWN (fallback) */

function UnknownState({ onActivate, onPurchase, activateBusy, detail }: ActivationScreenProps) {
  const reason = detail.state === 'UNKNOWN' ? detail.message : null;
  return (
    <>
      <Box>
        <Typography variant="h2" gutterBottom>
          Activate cassyx
        </Typography>
        <Typography variant="body2" color="text.secondary">
          cassyx is a one-time purchase - every feature, no tiers. Paste the license key from your
          purchase email to unlock this instance. Verification happens locally; no data leaves your
          network.
        </Typography>
      </Box>

      {reason && <Alert severity="warning">{reason}</Alert>}

      <KeyActivationForm onActivate={onActivate} busy={activateBusy} />

      <Divider flexItem>
        <Typography variant="caption" color="text.secondary">
          no key yet?
        </Typography>
      </Divider>

      <PurchaseButton onPurchase={onPurchase} />
      <BypassHint />
    </>
  );
}
