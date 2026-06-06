/**
 * Parameter-definitions dialog — wraps StencilParameterDefinitionsPanel in a
 * native <dialog> so stencil authors get a roomy modal instead of a cramped
 * sidebar grid.
 *
 * Opens with the current schema, lets the author edit, and resolves with
 * either the new schema (Save) or null (Cancel / Esc).
 */
import type { JsonSchema } from '../../data-contract/types.js';
import './StencilParameterDefinitionsPanel.js';

export function openParameterDefinitionsDialog(
  initialSchema: JsonSchema | undefined,
): Promise<JsonSchema | null> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';
    // Override the .stencil-picker-dialog default max-width (36rem) — the
    // two-panel layout needs more horizontal room than the picker list.
    dialog.style.maxWidth = 'min(960px, 90vw)';
    dialog.innerHTML = `
      <div class="stencil-picker-content">
        <div class="stencil-picker-header">
          <h3>Define parameters</h3>
          <button type="button" class="stencil-picker-close" aria-label="Close">&times;</button>
        </div>
        <div class="stencil-picker-section stencil-picker-scroll">
          <stencil-parameter-definitions-panel></stencil-parameter-definitions-panel>
        </div>
        <div class="stencil-picker-footer">
          <div class="stencil-picker-flex-fill"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert save">Save</button>
        </div>
      </div>
    `;

    const panel = dialog.querySelector('stencil-parameter-definitions-panel') as HTMLElement & {
      schema?: JsonSchema;
    };
    panel.schema = initialSchema;

    let pending: JsonSchema | undefined = initialSchema;
    let valid = true;

    panel.addEventListener('parameter-schema-change', (e: Event) => {
      const detail = (e as CustomEvent<{ schema: JsonSchema; valid: boolean }>).detail;
      pending = detail.schema;
      valid = detail.valid;
      saveBtn.disabled = !valid;
    });
    panel.addEventListener('parameter-schema-validation', (e: Event) => {
      const detail = (e as CustomEvent<{ valid: boolean }>).detail;
      valid = detail.valid;
      saveBtn.disabled = !valid;
    });

    const closeBtn = dialog.querySelector('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector('.cancel')!;
    const saveBtn = dialog.querySelector('.save') as HTMLButtonElement;

    const finish = (result: JsonSchema | null) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    closeBtn.addEventListener('click', () => finish(null));
    cancelBtn.addEventListener('click', () => finish(null));
    saveBtn.addEventListener('click', () => finish(pending ?? { type: 'object', properties: {} }));
    dialog.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        finish(null);
      }
    });

    document.body.appendChild(dialog);
    dialog.showModal();
  });
}
