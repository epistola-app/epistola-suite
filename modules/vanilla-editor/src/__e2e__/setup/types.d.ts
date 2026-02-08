// Type declarations for E2E tests

declare global {
  interface Window {
    editorModuleLoaded: boolean;
    mountEditor: (config: import('../types').MountConfig) => import('../types').MountedEditor;
    testEditor: import('../types').MountedEditor;
    currentTestContainerId: string;
    TENANT_ID: string;
    TEMPLATE_ID: string;
    VARIANT_ID: string;
    TEMPLATE_MODEL: unknown;
    THEMES: unknown[];
    DEFAULT_THEME: unknown;
    DATA_EXAMPLES: unknown[];
    DATA_MODEL: unknown;
  }
}

export {};
