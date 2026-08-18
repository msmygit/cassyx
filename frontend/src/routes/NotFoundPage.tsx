import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Stack from '@mui/material/Stack';
import Typography from '@mui/material/Typography';
import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <Box sx={{ flex: 1, display: 'grid', placeItems: 'center', p: 4 }}>
      <Stack spacing={2} alignItems="center">
        <Typography variant="h2">Nothing here</Typography>
        <Typography variant="body2" color="text.secondary">
          That route does not exist.
        </Typography>
        <Button component={Link} to="/" variant="contained">
          Back to the workspace
        </Button>
      </Stack>
    </Box>
  );
}
