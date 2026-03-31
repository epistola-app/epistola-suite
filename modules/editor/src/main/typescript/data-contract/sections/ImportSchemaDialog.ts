/**
 * ImportSchemaDialog — Dialog for importing a JSON Schema via paste or file upload.
 *
 * Pure render function following the MigrationAssistant pattern.
 * Parses and validates the input, then hands the parsed object to the caller.
 */

import { html, nothing } from 'lit';
import { icon } from '../../ui/icons.js';

export interface ImportSchemaDialogCallbacks {
  onImportFromText: (jsonText: string) => void;
  onImportFromFile: (file: File) => void;
  onCancel: () => void;
}

export function renderImportSchemaDialog(
  parseError: string | null,
  callbacks: ImportSchemaDialogCallbacks,
): unknown {
  return html`
    <div class="dc-import-dialog">
      <h3 class="dc-dialog-title">Import JSON Schema</h3>
      <p class="dc-dialog-hint">
        Paste a JSON Schema or upload a <code>.json</code> file. The schema will be checked for
        compatibility with the visual editor.
      </p>

      <!-- Error display -->
      ${parseError ? html`<div class="dc-import-error">${parseError}</div>` : nothing}

      <!-- Paste section -->
      <div class="dc-import-section">
        <label class="dc-import-label" for="dc-import-textarea">Paste JSON Schema</label>
        <textarea
          id="dc-import-textarea"
          class="dc-import-textarea"
          placeholder='{"type": "object", "properties": { ... }}'
          rows="10"
        ></textarea>
        <button
          class="ep-btn-primary btn-sm dc-import-paste-btn dc-btn-icon"
          @click=${(e: Event) => {
            const dialog = (e.target as HTMLElement).closest('.dc-import-dialog')!;
            const textarea = dialog.querySelector<HTMLTextAreaElement>('#dc-import-textarea')!;
            const text = textarea.value.trim();
            if (text) {
              callbacks.onImportFromText(text);
            }
          }}
        >
          ${icon('upload', 14)} Import
        </button>
      </div>

      <div class="dc-import-divider">
        <span>or</span>
      </div>

      <!-- File upload section -->
      <div class="dc-import-section">
        <label class="dc-import-label" for="dc-import-file">Upload JSON file</label>
        <input
          type="file"
          id="dc-import-file"
          class="dc-import-file-input"
          accept=".json,application/json"
          @change=${(e: Event) => {
            const input = e.target as HTMLInputElement;
            const file = input.files?.[0];
            if (file) {
              callbacks.onImportFromFile(file);
            }
          }}
        />
      </div>

      <!-- Actions -->
      <div class="dc-dialog-actions">
        <button class="ep-btn-outline btn-sm" @click=${() => callbacks.onCancel()}>Cancel</button>
      </div>
    </div>
  `;
}
