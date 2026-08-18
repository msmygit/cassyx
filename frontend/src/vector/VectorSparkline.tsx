import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import Stack from '@mui/material/Stack';
import Tooltip from '@mui/material/Tooltip';
import { useTheme } from '@mui/material/styles';
import { abbreviateVector, dimensionBadge, sparklinePath } from './vectorModel';

export interface VectorSparklineProps {
  values: readonly number[];
  /** Declared dimension from the schema. Differs from `values.length` only if the API truncated. */
  dimensions?: number;
  width?: number;
  height?: number;
  onClick?: () => void;
}

/**
 * How a `vector<float, N>` renders in a grid cell (plan §6): a compact sparkline plus a dimension
 * badge — **not** 1536 comma-separated floats.
 *
 * That is not only a readability choice. Pasting 1536 floats into a table cell blows out column
 * widths, makes row virtualisation useless, and buries every other column in the row.
 */
export function VectorSparkline({
  values,
  dimensions,
  width = 96,
  height = 20,
  onClick,
}: VectorSparklineProps) {
  const theme = useTheme();
  const count = dimensions ?? values.length;
  const path = sparklinePath(values, width, height);
  const label = `vector<float, ${count}> ${abbreviateVector(values)}`;

  return (
    <Tooltip title={label} enterDelay={400}>
      <Stack
        direction="row"
        spacing={0.75}
        alignItems="center"
        data-testid="vector-sparkline"
        aria-label={label}
        role={onClick ? 'button' : undefined}
        tabIndex={onClick ? 0 : undefined}
        onClick={onClick}
        onKeyDown={(event) => {
          if (onClick && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            onClick();
          }
        }}
        sx={{ cursor: onClick ? 'pointer' : 'default', minWidth: 0 }}
      >
        {values.length > 0 ? (
          <Box
            component="svg"
            width={width}
            height={height}
            viewBox={`0 0 ${width} ${height}`}
            preserveAspectRatio="none"
            aria-hidden
            sx={{ flexShrink: 0, display: 'block' }}
          >
            <path
              d={path}
              fill="none"
              stroke={theme.palette.secondary.main}
              strokeWidth={1.25}
              strokeLinejoin="round"
              strokeLinecap="round"
            />
          </Box>
        ) : (
          <Box sx={{ width, height }} aria-hidden />
        )}
        <Chip
          size="small"
          variant="outlined"
          color="secondary"
          label={dimensionBadge(count)}
          data-testid="vector-dimension-badge"
          sx={{ height: 18, fontSize: 11 }}
        />
      </Stack>
    </Tooltip>
  );
}
