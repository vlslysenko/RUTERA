/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  /** 'true' = in-browser mock API, 'false' = real backend via the /app proxy. Defaults to 'true' in dev. */
  readonly VITE_API_MOCK?: string;
  readonly VITE_API_PROXY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
