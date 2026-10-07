/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Public: baked into the bundle. Blank until issued. */
  readonly VITE_OPERATOR_LLPIN?: string;
  /** Public: baked into the bundle. Blank until confirmed. */
  readonly VITE_OPERATOR_REGISTERED_OFFICE?: string;
  /** Public: baked into the bundle. Blank until named. */
  readonly VITE_OPERATOR_GRIEVANCE_OFFICER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
