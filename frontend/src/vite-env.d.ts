/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CASSYX_API_BASE_URL?: string;
  readonly VITE_CASSYX_API_TIMEOUT_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
