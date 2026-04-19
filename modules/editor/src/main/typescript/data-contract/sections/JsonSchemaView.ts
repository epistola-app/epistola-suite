/**
 * JsonSchemaView — Read-only, collapsible JSON Schema viewer.
 *
 * Displays the schema as a formatted, collapsible tree using native
 * <details>/<summary> elements. Provides copy-to-clipboard and import buttons.
 * When compatibility issues exist, shows a warning banner.
 */

import { html, nothing } from 'lit';
import { icon } from '../../ui/icons.js';
import type { CompatibilityIssue } from '../utils/schemaCompatibility.js';

/* oxlint-disable eslint/no-use-before-define */

export interface JsonSchemaViewCallbacks {
  onCopyToClipboard: () => void;
  onImportSchema: () => void;
}

export function renderJsonSchemaView(
  jsonSchema: object | null,
  compatibilityIssues: CompatibilityIssue[],
  copySuccess: boolean,
  callbacks: JsonSchemaViewCallbacks,
): unknown {
  return html`
    <section class="dc-section">
      <h3 class="dc-section-label">JSON Schema</h3>
      <p class="dc-section-hint">
        ${compatibilityIssues.length > 0
          ? 'This schema uses features not supported by the visual editor. It is shown in read-only mode.'
          : 'Read-only view of the JSON Schema representation.'}
      </p>

      <!-- Toolbar -->
      <div class="dc-toolbar">
        <button
          class="ep-btn-outline btn-sm dc-btn-icon"
          @click=${() => callbacks.onImportSchema()}
          title="Import a JSON Schema"
        >
          ${icon('upload', 14)} Import
        </button>

        <div class="dc-toolbar-spacer"></div>

        ${copySuccess ? html`<span class="dc-status-success">Copied!</span>` : nothing}
        <button
          class="ep-btn-outline btn-sm"
          @click=${() => callbacks.onCopyToClipboard()}
          ?disabled=${!jsonSchema}
          title="Copy JSON Schema to clipboard"
        >
          Copy
        </button>
      </div>

      <!-- Compatibility issues banner -->
      ${compatibilityIssues.length > 0 ? renderCompatibilityBanner(compatibilityIssues) : nothing}

      <!-- JSON display -->
      ${jsonSchema
        ? html` <div class="dc-json-view">${renderJsonNode(jsonSchema, 0, true)}</div> `
        : html`<div class="dc-empty-state">No schema defined.</div>`}
    </section>
  `;
}

// =============================================================================
// Compatibility banner
// =============================================================================

function renderCompatibilityBanner(issues: CompatibilityIssue[]): unknown {
  return html`
    <div class="dc-compat-banner">
      <div class="dc-compat-banner-header">
        Visual editor is disabled — ${issues.length} unsupported
        feature${issues.length !== 1 ? 's' : ''} detected
      </div>
      <details class="dc-compat-details">
        <summary class="dc-compat-details-summary">Show details</summary>
        <ul class="dc-compat-issue-list">
          ${issues.map(
            (issue) => html` <li><code>${issue.path}</code> — ${issue.description}</li> `,
          )}
        </ul>
      </details>
    </div>
  `;
}

// =============================================================================
// Recursive JSON renderer with collapsible objects/arrays
// =============================================================================

function renderJsonNode(value: unknown, depth: number, open: boolean): unknown {
  if (value === null) {
    return html`<span class="dc-json-null">null</span>`;
  }

  if (Array.isArray(value)) {
    return renderJsonArray(value, depth, open);
  }

  if (isRecord(value)) {
    return renderJsonObject(value, depth, open);
  }

  if (typeof value === 'string') {
    return html`<span class="dc-json-string">"${value}"</span>`;
  }

  if (typeof value === 'boolean') {
    return html`<span class="dc-json-boolean">${String(value)}</span>`;
  }

  if (typeof value === 'number') {
    return html`<span class="dc-json-number">${value}</span>`;
  }

  return html`<span class="dc-json-string">${JSON.stringify(value)}</span>`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function renderJsonObject(obj: Record<string, unknown>, depth: number, open: boolean): unknown {
  const entries = Object.entries(obj);

  if (entries.length === 0) {
    return html`<span class="dc-json-brace">{}</span>`;
  }

  // For small leaf objects (no nested objects/arrays), render inline
  const hasNested = entries.some(
    ([, v]) => (typeof v === 'object' && v !== null) || Array.isArray(v),
  );
  if (!hasNested && entries.length <= 3) {
    return renderInlineObject(entries);
  }

  return html`
    <details class="dc-json-collapsible" ?open=${open}>
      <summary class="dc-json-summary">
        <span class="dc-json-brace">{</span>
        <span class="dc-json-preview">${entries.length} properties</span>
      </summary>
      <div class="dc-json-content">
        ${entries.map(
          ([key, val], i) => html`
            <div class="dc-json-entry">
              <span class="dc-json-key">"${key}"</span
              ><span class="dc-json-colon">: </span>${renderJsonNode(
                val,
                depth + 1,
                depth < 1,
              )}${i < entries.length - 1 ? html`<span class="dc-json-comma">,</span>` : nothing}
            </div>
          `,
        )}
      </div>
      <span class="dc-json-brace">}</span>
    </details>
  `;
}

function renderJsonArray(arr: unknown[], depth: number, open: boolean): unknown {
  if (arr.length === 0) {
    return html`<span class="dc-json-brace">[]</span>`;
  }

  // For simple primitive arrays, render inline
  const hasNested = arr.some((v) => typeof v === 'object' && v !== null);
  if (!hasNested && arr.length <= 5) {
    return renderInlineArray(arr);
  }

  return html`
    <details class="dc-json-collapsible" ?open=${open}>
      <summary class="dc-json-summary">
        <span class="dc-json-brace">[</span>
        <span class="dc-json-preview">${arr.length} items</span>
      </summary>
      <div class="dc-json-content">
        ${arr.map(
          (val, i) => html`
            <div class="dc-json-entry">
              ${renderJsonNode(val, depth + 1, depth < 1)}${i < arr.length - 1
                ? html`<span class="dc-json-comma">,</span>`
                : nothing}
            </div>
          `,
        )}
      </div>
      <span class="dc-json-brace">]</span>
    </details>
  `;
}

function renderInlineObject(entries: [string, unknown][]): unknown {
  return html`<span class="dc-json-brace">{</span>${entries.map(
      ([key, val], i) =>
        html`<span class="dc-json-key">"${key}"</span
          ><span class="dc-json-colon">: </span>${renderJsonNode(val, 0, false)}${i <
          entries.length - 1
            ? html`<span class="dc-json-comma">, </span>`
            : nothing}`,
    )}<span class="dc-json-brace">}</span>`;
}

function renderInlineArray(arr: unknown[]): unknown {
  return html`<span class="dc-json-brace">[</span>${arr.map(
      (val, i) =>
        html`${renderJsonNode(val, 0, false)}${i < arr.length - 1
          ? html`<span class="dc-json-comma">, </span>`
          : nothing}`,
    )}<span class="dc-json-brace">]</span>`;
}
