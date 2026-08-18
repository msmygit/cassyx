import { useId, useState } from 'react';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import TextField, { type TextFieldProps } from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import VisibilityOffRoundedIcon from '@mui/icons-material/VisibilityOffRounded';
import VisibilityRoundedIcon from '@mui/icons-material/VisibilityRounded';

export interface SecretFieldProps extends Omit<
  TextFieldProps,
  'type' | 'value' | 'onChange' | 'slotProps'
> {
  value: string;
  onValueChange: (value: string) => void;
  /** Test hook, mirrored onto the input element. */
  testId?: string;
}

/**
 * Masked credential input with an explicit reveal toggle.
 *
 * Used for BOTH the Cassandra password and the Astra token. The prior art masked the password
 * while showing the far more sensitive Astra token in plaintext — a single shared component makes
 * that class of mistake impossible.
 *
 * The field is masked by default, opts out of autofill/spellcheck/password managers, and never
 * has its value echoed into helper text or error messages.
 */
export function SecretField({ value, onValueChange, testId, ...props }: SecretFieldProps) {
  const [revealed, setRevealed] = useState(false);
  const generatedId = useId();
  const inputId = props.id ?? generatedId;

  return (
    <TextField
      {...props}
      id={inputId}
      type={revealed ? 'text' : 'password'}
      value={value}
      onChange={(event) => onValueChange(event.target.value)}
      onBlur={(event) => {
        setRevealed(false);
        props.onBlur?.(event);
      }}
      slotProps={{
        htmlInput: {
          autoComplete: 'off',
          autoCorrect: 'off',
          autoCapitalize: 'off',
          spellCheck: false,
          'data-testid': testId,
          'data-secret': 'true',
        },
        input: {
          endAdornment: (
            <InputAdornment position="end">
              <Tooltip title={revealed ? 'Hide' : 'Reveal'}>
                <IconButton
                  size="small"
                  edge="end"
                  aria-label={revealed ? 'Hide value' : 'Reveal value'}
                  onClick={() => setRevealed((current) => !current)}
                  tabIndex={-1}
                >
                  {revealed ? (
                    <VisibilityOffRoundedIcon fontSize="small" />
                  ) : (
                    <VisibilityRoundedIcon fontSize="small" />
                  )}
                </IconButton>
              </Tooltip>
            </InputAdornment>
          ),
        },
      }}
    />
  );
}
