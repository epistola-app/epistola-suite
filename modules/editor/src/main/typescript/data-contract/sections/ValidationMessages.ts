/**
 * ValidationMessages — Renders a list of validation warnings/errors.
 *
 * Pure render function: takes an array of {path, message} objects,
 * returns a Lit html template with amber warning styling.
 */

import { html, nothing } from 'lit'

export interface ValidationWarning {
  path: string
  message: string
}

export function renderValidationMessages(warnings: ValidationWarning[]): unknown {
  if (warnings.length === 0) return nothing

  return html`
    <div class="dc-validation-messages" role="alert">
      <div class="dc-validation-header">
        <span class="dc-validation-icon">!</span>
        <span class="dc-validation-title">${warnings.length} warning${warnings.length !== 1 ? 's' : ''}</span>
      </div>
      <ul class="dc-validation-list">
        ${warnings.map(
          (w) => html`
            <li class="dc-validation-item">
              <code class="dc-validation-path">${w.path}</code>
              <span class="dc-validation-message">${w.message}</span>
            </li>
          `,
        )}
      </ul>
    </div>
  `
}
