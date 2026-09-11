// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { html, nothing } from 'lit';
import type { ValidationError } from '../types.js';

/** Shared `<li>` rendering for a path+message error list, used by both banners below. */
function renderErrorListItems(errors: ValidationError[], pathClass: string): unknown {
  return errors.map(
    (e) => html`
      <li>${e.path ? html`<code class="${pathClass}">${e.path}</code>` : nothing} ${e.message}</li>
    `,
  );
}

/** Alert-style banner for server-rejected saves: a summary title plus the individual errors below it. */
export function renderValidationErrorsAlert(errors: ValidationError[]): unknown {
  if (errors.length === 0) return nothing;

  return html`
    <div class="dc-save-errors-banner" role="alert">
      <div class="dc-save-errors-banner-title">
        Couldn't save — ${errors.length} validation error${errors.length === 1 ? '' : 's'}
      </div>
      <ul class="dc-save-errors-banner-list">
        ${renderErrorListItems(errors, 'dc-save-errors-banner-path')}
      </ul>
    </div>
  `;
}

/**
 * Generic, non-repeating summary of client-side blocking issues — schema
 * constraint errors and example validation errors are already pinpointed
 * inline next to their field, so this only says how many, not what each one
 * is. Also carries schema-level warnings (non-blocking).
 */
export function renderClientValidationBanner(
  messages: string[],
  warnings: ValidationError[],
): unknown {
  if (messages.length === 0 && warnings.length === 0) return nothing;

  return html`
    <div class="dc-validation-banner" role="alert">
      ${messages.map((message) => html`<div class="dc-validation-banner-message">${message}</div>`)}
      ${
        warnings.length > 0
          ? html`
              <ul class="dc-validation-banner-list">
                ${renderErrorListItems(warnings, 'dc-validation-banner-path')}
              </ul>
            `
          : nothing
      }
    </div>
  `;
}
