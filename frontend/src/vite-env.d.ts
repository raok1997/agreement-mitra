/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Public: baked into the bundle. Blank until issued. */
  readonly VITE_OPERATOR_LLPIN?: string;
  /** Public: baked into the bundle. Blank until confirmed. */
  readonly VITE_OPERATOR_REGISTERED_OFFICE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
