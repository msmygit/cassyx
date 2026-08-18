import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import Box from '@mui/material/Box';
import { layout } from '../theme/tokens';

const STORAGE_KEY = 'cassyx.sidebarWidth';

function clampWidth(width: number): number {
  return Math.min(layout.sidebarMaxWidth, Math.max(layout.sidebarMinWidth, width));
}

function readStoredWidth(): number {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = Number.parseInt(raw, 10);
      if (!Number.isNaN(parsed)) return clampWidth(parsed);
    }
  } catch {
    // ignore
  }
  return layout.sidebarDefaultWidth;
}

export interface ResizableSidebarProps {
  children: ReactNode;
  collapsed?: boolean;
}

/**
 * Left sidebar with a drag handle (plan §2 shell).
 *
 * Width persists across reloads, is clamped to sane bounds, and is keyboard-adjustable — the
 * splitter is a real `separator` role with arrow-key support, not a mouse-only affordance.
 */
export function ResizableSidebar({ children, collapsed = false }: ResizableSidebarProps) {
  const [width, setWidth] = useState<number>(readStoredWidth);
  const dragging = useRef(false);

  const persist = useCallback((next: number) => {
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, String(next));
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    const onMove = (event: MouseEvent) => {
      if (!dragging.current) return;
      event.preventDefault();
      setWidth(clampWidth(event.clientX));
    };
    const onUp = () => {
      if (!dragging.current) return;
      dragging.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      setWidth((current) => {
        persist(current);
        return current;
      });
    };
    globalThis.addEventListener('mousemove', onMove);
    globalThis.addEventListener('mouseup', onUp);
    return () => {
      globalThis.removeEventListener('mousemove', onMove);
      globalThis.removeEventListener('mouseup', onUp);
    };
  }, [persist]);

  if (collapsed) return null;

  const nudge = (delta: number) => {
    setWidth((current) => {
      const next = clampWidth(current + delta);
      persist(next);
      return next;
    });
  };

  return (
    <Box
      component="aside"
      data-testid="schema-sidebar"
      sx={{
        width,
        flex: `0 0 ${width}px`,
        minWidth: 0,
        display: 'flex',
        position: 'relative',
        bgcolor: 'chrome.sidebar',
        borderRight: 1,
        borderColor: 'chrome.border',
      }}
    >
      <Box sx={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {children}
      </Box>
      <Box
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize schema sidebar"
        aria-valuenow={width}
        aria-valuemin={layout.sidebarMinWidth}
        aria-valuemax={layout.sidebarMaxWidth}
        tabIndex={0}
        data-testid="sidebar-resize-handle"
        onMouseDown={() => {
          dragging.current = true;
          document.body.style.cursor = 'col-resize';
          document.body.style.userSelect = 'none';
        }}
        onDoubleClick={() => {
          setWidth(layout.sidebarDefaultWidth);
          persist(layout.sidebarDefaultWidth);
        }}
        onKeyDown={(event) => {
          if (event.key === 'ArrowLeft') nudge(-16);
          if (event.key === 'ArrowRight') nudge(16);
        }}
        sx={{
          position: 'absolute',
          top: 0,
          right: -3,
          width: 6,
          height: '100%',
          cursor: 'col-resize',
          zIndex: 2,
          '&:hover, &:focus-visible': { bgcolor: 'primary.main', opacity: 0.5, outline: 'none' },
        }}
      />
    </Box>
  );
}
