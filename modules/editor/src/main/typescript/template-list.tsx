import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { Root } from "react-dom/client";
import { TemplateListApp } from "./apps/template-list/TemplateListApp";
import "./index.css";

/**
 * Summary of a template for list display
 */
export interface TemplateSummary {
  id: number;
  name: string;
  variantCount: number;
  hasDraft: boolean;
  publishedVersionCount: number;
  lastModified: string;
}

/**
 * Options for mounting the template list
 */
export interface TemplateListOptions {
  /** The DOM element to mount into */
  container: HTMLElement;
  /** Tenant ID for API calls */
  tenantId: number;
  /** Initial templates data (server-rendered) */
  templates: TemplateSummary[];
}

/**
 * Result of mounting the template list
 */
export interface TemplateListInstance {
  /** Unmount the component and clean up */
  unmount: () => void;
}

/**
 * Mount the templates list into a container element.
 *
 * @example
 * ```typescript
 * const list = mountTemplateList({
 *   container: document.getElementById('templates-container'),
 *   tenantId: 1,
 *   templates: initialTemplates,
 * });
 *
 * // Later, to unmount:
 * list.unmount();
 * ```
 */
export function mountTemplateList(options: TemplateListOptions): TemplateListInstance {
  const { container, tenantId, templates } = options;

  // Add root class for CSS scoping
  container.classList.add("template-list-root");

  // Create React root and render
  const root: Root = createRoot(container);

  root.render(
    <StrictMode>
      <TemplateListApp
        tenantId={tenantId}
        initialTemplates={templates}
        baseUrl={`/tenants/${tenantId}/templates`}
      />
    </StrictMode>,
  );

  return {
    unmount: () => {
      root.unmount();
      container.classList.remove("template-list-root");
    },
  };
}
