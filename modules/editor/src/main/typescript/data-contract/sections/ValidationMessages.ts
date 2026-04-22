/**
 * ValidationMessages — Renders a list of validation warnings/errors.
 *
 * Pure render function: takes an array of {path, message} objects,
 * returns a Lit html template with amber warning styling.
 */

import { html, nothing } from 'lit';

/* oxlint-disable eslint/no-use-before-define */

export interface ValidationWarning {
  path: string;
  message: string;
}

interface ParsedWarning {
  sourceLabel: string;
  path: string;
  requestId?: string;
  message: string;
  status?: string;
  correlation?: string;
}

export function renderValidationMessages(warnings: ValidationWarning[]): unknown {
  if (warnings.length === 0) return nothing;
  const parsed = warnings.map(parseValidationWarning);

  return html`
    <div class="dc-validation-messages" role="alert">
      <div class="dc-validation-header">
        <span class="dc-validation-icon">!</span>
        <span class="dc-validation-title"
          >${warnings.length} warning${warnings.length !== 1 ? 's' : ''}</span
        >
      </div>
      <ul class="dc-validation-list">
        ${parsed.map(
          (w) => html`
            <li class="dc-validation-item">
              <div class="dc-validation-path-col">
                <span class="dc-validation-source">${w.sourceLabel}</span>
                ${w.requestId
                  ? html`<code class="dc-validation-request-id" title=${w.requestId}
                      >${shortId(w.requestId)}</code
                    >`
                  : nothing}
                <code class="dc-validation-path" title=${w.path}>${w.path}</code>
              </div>
              <div class="dc-validation-message-col">
                <span class="dc-validation-message">${w.message}</span>
                ${w.status || w.correlation
                  ? html`
                      <div class="dc-validation-meta">
                        ${w.status
                          ? html`<span class="dc-validation-chip">${w.status}</span>`
                          : nothing}
                        ${w.correlation
                          ? html`<span class="dc-validation-chip">${w.correlation}</span>`
                          : nothing}
                      </div>
                    `
                  : nothing}
              </div>
            </li>
          `,
        )}
      </ul>
    </div>
  `;
}

export function renderValidationSummary(
  warnings: ValidationWarning[],
  onReviewWarnings: () => void,
): unknown {
  if (warnings.length === 0) return nothing;

  const recentCount = warnings.filter((w) => w.path.startsWith('request:')).length;
  const schemaCount = warnings.length - recentCount;

  return html`
    <div class="dc-validation-summary" role="status">
      <div class="dc-validation-summary-left">
        <span class="dc-validation-icon">!</span>
        <span class="dc-validation-title">Compatibility issues: ${warnings.length} total</span>
        <span class="dc-validation-summary-meta"
          >${schemaCount} schema, ${recentCount} recent usage</span
        >
      </div>
      <button class="ep-btn-outline btn-sm" @click=${onReviewWarnings}>Review Issues</button>
    </div>
  `;
}

export function parseValidationWarning(w: ValidationWarning): ParsedWarning {
  const requestMatch = /^request:([^\s]+)(?:\s+(.+))?$/.exec(w.path);
  const metadataMatch = /\s*\[status=([^\]\s]+)(?:\s+correlation=([^\]]+))?\]\s*$/.exec(w.message);
  const cleanMessage = metadataMatch ? w.message.replace(metadataMatch[0], '').trim() : w.message;
  let status: string | undefined;
  let correlation: string | undefined;
  if (metadataMatch) {
    status = metadataMatch[1];
    correlation = metadataMatch[2];
  }

  if (requestMatch) {
    return {
      sourceLabel: 'Recent Request',
      requestId: requestMatch[1],
      path: requestMatch[2] ?? '',
      message: cleanMessage,
      status,
      correlation,
    };
  }

  return {
    sourceLabel: 'Schema',
    path: w.path,
    message: cleanMessage,
    status,
    correlation,
  };
}

export function shortId(id: string): string {
  if (id.length <= 12) return id;
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}
