import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import SvgIcon, { type SvgIconProps } from '@mui/material/SvgIcon';

/**
 * The cassyx mark: a token ring (the Cassandra partitioner ring) with two chords crossing it to
 * form an `x`. Node dots sit on the ring at the token boundaries.
 *
 * Explicitly *not* the predecessor's magnifying glass: cassyx moves data, it does not just
 * inspect it. Colours come from the theme so the mark works in both light and dark mode.
 */
export function CassyxMark(props: SvgIconProps) {
  return (
    <SvgIcon viewBox="0 0 24 24" {...props}>
      <circle
        cx="12"
        cy="12"
        r="8.5"
        fill="none"
        stroke="currentColor"
        strokeOpacity="0.35"
        strokeWidth="1.6"
      />
      <path
        d="M7.4 7.4 L16.6 16.6"
        stroke="currentColor"
        strokeWidth="2.1"
        strokeLinecap="round"
        fill="none"
      />
      <path
        d="M16.6 7.4 L7.4 16.6"
        stroke="currentColor"
        strokeWidth="2.1"
        strokeLinecap="round"
        strokeOpacity="0.55"
        fill="none"
      />
      <circle cx="12" cy="3.5" r="1.6" fill="currentColor" />
      <circle cx="19.4" cy="15.5" r="1.35" fill="currentColor" fillOpacity="0.7" />
      <circle cx="4.6" cy="15.5" r="1.35" fill="currentColor" fillOpacity="0.7" />
    </SvgIcon>
  );
}

export interface CassyxLogoProps {
  /** Hide the wordmark, e.g. in a collapsed sidebar. */
  markOnly?: boolean;
  size?: 'small' | 'medium';
}

/** Mark + wordmark lockup used in the connection bar and on the activation screen. */
export function CassyxLogo({ markOnly = false, size = 'medium' }: CassyxLogoProps) {
  const fontSize = size === 'small' ? '0.95rem' : '1.15rem';
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, userSelect: 'none' }}>
      <CassyxMark
        sx={{ color: 'primary.main', fontSize: size === 'small' ? 22 : 28 }}
        titleAccess="cassyx"
      />
      {!markOnly && (
        <Typography
          component="span"
          sx={{
            fontSize,
            fontWeight: 700,
            letterSpacing: '-0.03em',
            lineHeight: 1,
            color: 'text.primary',
          }}
        >
          cass
          <Box component="span" sx={{ color: 'primary.main' }}>
            yx
          </Box>
        </Typography>
      )}
    </Box>
  );
}
