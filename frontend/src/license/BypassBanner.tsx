import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import Tooltip from '@mui/material/Tooltip';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import { layout } from '../theme/tokens';

/**
 * PERSISTENT bypass banner (plan §9.2).
 *
 * Rendered whenever the backend reports `edition: "unlicensed-bypass"` / `enforce=false`.
 *
 * Deliberately NOT dismissible and NOT a toast: the whole point is that an instance running with
 * license enforcement disabled can never be mistaken for a paid one — by the operator, by a
 * screenshot in a bug report, or by whoever inherits the deployment.
 */
export function BypassBanner() {
  return (
    <Box
      role="status"
      aria-live="polite"
      data-testid="license-bypass-banner"
      sx={{
        height: layout.bannerHeight,
        flex: `0 0 ${layout.bannerHeight}px`,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 1,
        px: 2,
        bgcolor: 'warning.main',
        color: 'warning.contrastText',
        borderBottom: 1,
        borderColor: 'warning.dark',
        zIndex: (theme) => theme.zIndex.appBar + 1,
      }}
    >
      <WarningAmberRoundedIcon fontSize="small" />
      <Typography variant="body2" sx={{ fontWeight: 700, letterSpacing: '0.01em' }}>
        License enforcement is DISABLED — this instance is running unlicensed (bypass mode).
      </Typography>
      <Tooltip
        title={
          'CASSYX_LICENSE_ENFORCE=false. Every feature is unlocked without a valid license key. ' +
          'Intended for development, CI, evaluation and enterprise site-license deployments. ' +
          'Set CASSYX_LICENSE_ENFORCE=true and activate a key to remove this banner.'
        }
      >
        <Typography
          component="span"
          variant="caption"
          sx={{ textDecoration: 'underline dotted', cursor: 'help', fontWeight: 600 }}
        >
          why am I seeing this?
        </Typography>
      </Tooltip>
    </Box>
  );
}
