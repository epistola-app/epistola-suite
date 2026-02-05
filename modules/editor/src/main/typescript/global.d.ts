// Global window properties exposed by the server
declare global {
  interface Window {
    TEMPLATE_MODEL?: unknown;
    TEMPLATE_ID?: string;
    TENANT_ID?: string;
    VARIANT_ID?: string;
    APP_VERSION?: string;
    APP_NAME?: string;
    // CSRF token helper injected by editor.html
    getCsrfToken?: () => string;
  }
}

export {};
