import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    port: 5173,
    // Vite HMR behind Docker / the compose stack.
    watch: { usePolling: process.env.CASSYX_POLL_WATCH === 'true' },
    proxy: {
      '/api': {
        target: process.env.CASSYX_API_PROXY ?? 'http://localhost:8080',
        changeOrigin: true,
        // SSE (job progress) must not be buffered by the dev proxy.
        ws: false,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router'],
          mui: ['@mui/material', '@mui/icons-material'],
          editor: ['@uiw/react-codemirror', '@codemirror/lang-sql'],
          table: ['@tanstack/react-table', '@tanstack/react-virtual'],
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    // jsdom refuses to expose localStorage for an opaque origin, which the shell's colour-mode
    // and sidebar-width persistence both rely on.
    environmentOptions: { jsdom: { url: 'http://localhost:5173' } },
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: './coverage',
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.d.ts',
        'src/main.tsx',
        'src/test/**',
        'src/**/*.test.{ts,tsx}',
        // Presentational placeholders are covered by Playwright E2E per plan §11.1,
        // not by unit tests.
        'src/panels/**',
        'src/routes/**',
        'src/theme/brand.tsx',
      ],
      // plan §11.1 — frontend gate: 70% statements.
      thresholds: {
        statements: 70,
        branches: 65,
        functions: 65,
        lines: 70,
      },
    },
  },
});
