import Chip from '@mui/material/Chip';
import Tooltip from '@mui/material/Tooltip';
import { trialUrgency } from './licenseModel';

export interface TrialCountdownProps {
  daysRemaining: number | null;
}

/**
 * Trial countdown surfaced in the shell for a `VALID` license with `trial: true` (plan §9.4).
 * Escalates from a neutral chip to warning to error as `daysRemaining` drops, so the buyer notices
 * the deadline approaching rather than being surprised by a sudden lockout.
 */
export function TrialCountdown({ daysRemaining }: TrialCountdownProps) {
  if (daysRemaining === null) return null;

  const urgency = trialUrgency(daysRemaining);
  const color = urgency === 'critical' ? 'error' : urgency === 'warning' ? 'warning' : 'default';
  const label =
    daysRemaining <= 0
      ? 'Trial ends today'
      : daysRemaining === 1
        ? '1 day left in trial'
        : `${daysRemaining} days left in trial`;

  return (
    <Tooltip title="cassyx is a one-time purchase - purchase a license before the trial ends to keep working.">
      <Chip
        data-testid="trial-countdown"
        size="small"
        variant={urgency === 'normal' ? 'outlined' : 'filled'}
        color={color}
        label={label}
      />
    </Tooltip>
  );
}
