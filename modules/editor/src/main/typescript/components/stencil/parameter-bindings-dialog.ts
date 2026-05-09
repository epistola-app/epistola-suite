/**
 * Parameter-bindings dialog — modal for binding a stencil instance's
 * declared parameters to JSONata expressions. Used from the StencilInspector
 * for re-editing existing bindings; the stencil picker's step 4 has its own
 * inline equivalent for fresh insertions.
 *
 * Required parameters block confirmation when their expression is empty.
 */
import type { JsonSchema, JsonSchemaProperty } from '../../data-contract/types.js';

export interface BindingsDialogResult {
  bindings: Record<string, string>;
  paramsAlias: string;
}

export interface BindingsDialogOptions {
  title?: string;
  schema: JsonSchema;
  initialBindings: Record<string, string>;
  initialAlias: string;
}

export function openParameterBindingsDialog(
  options: BindingsDialogOptions,
): Promise<BindingsDialogResult | null> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';
    dialog.innerHTML = `
      <div class="stencil-picker-content" style="max-width: 640px;">
        <div class="stencil-picker-header">
          <h3>${options.title ?? 'Configure parameters'}</h3>
          <button type="button" class="stencil-picker-close" aria-label="Close">&times;</button>
        </div>
        <div style="padding: var(--ep-space-3) var(--ep-space-6); max-height: 70vh; overflow: auto;">
          <div style="margin-bottom: var(--ep-space-3);">
            <label style="font-size: var(--ep-text-xs); font-weight: 500;">Alias</label>
            <input type="text" id="bindings-alias" class="ep-input" style="width: 100%;" />
            <div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-top: 2px;">
              Namespace this stencil's parameters live under inside its content (default <code>params</code>).
              Change to avoid shadowing when nested inside another parametrised component.
            </div>
          </div>
          <div id="bindings-rows"></div>
        </div>
        <div class="stencil-picker-footer">
          <div style="flex: 1;"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert save">Save</button>
        </div>
      </div>
    `;

    const aliasInput = dialog.querySelector<HTMLInputElement>('#bindings-alias')!;
    const rowsContainer = dialog.querySelector<HTMLElement>('#bindings-rows')!;
    const closeBtn = dialog.querySelector('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector('.cancel')!;
    const saveBtn = dialog.querySelector('.save') as HTMLButtonElement;

    aliasInput.value = options.initialAlias || 'params';
    const bindings: Record<string, string> = { ...options.initialBindings };

    const required = new Set(options.schema.required ?? []);
    const properties = options.schema.properties ?? {};
    for (const [name, prop] of Object.entries(properties)) {
      const row = document.createElement('div');
      row.style.marginBottom = 'var(--ep-space-3)';
      const isRequired = required.has(name);
      const typeLabel = typeOf(prop);
      const value = bindings[name] ?? '';
      row.innerHTML = `
        <div style="display:flex; align-items:center; gap:var(--ep-space-2); margin-bottom:2px;">
          <label style="font-size: var(--ep-text-xs); font-weight:500;">${escapeHtml(name)}</label>
          <span style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground);">${typeLabel}</span>
          ${isRequired ? '<span style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626);">required</span>' : ''}
        </div>
        ${prop?.description ? `<div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom:2px;">${escapeHtml(prop.description)}</div>` : ''}
        <input type="text" class="ep-input bindings-input" data-param="${escapeAttr(name)}" style="width:100%;" placeholder="JSONata expression, e.g. recipient.name" />
      `;
      const input = row.querySelector<HTMLInputElement>('.bindings-input')!;
      input.value = value;
      rowsContainer.appendChild(row);
    }

    rowsContainer.querySelectorAll<HTMLInputElement>('.bindings-input').forEach((input) => {
      input.addEventListener('input', () => {
        const name = input.dataset.param!;
        const v = input.value.trim();
        if (v) bindings[name] = v;
        else delete bindings[name];
        updateSaveState();
      });
    });

    function updateSaveState() {
      const ok = Array.from(required).every((name) => (bindings[name] ?? '').trim().length > 0);
      saveBtn.disabled = !ok;
    }
    updateSaveState();

    const finish = (result: BindingsDialogResult | null) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };

    closeBtn.addEventListener('click', () => finish(null));
    cancelBtn.addEventListener('click', () => finish(null));
    saveBtn.addEventListener('click', () => {
      finish({
        bindings: { ...bindings },
        paramsAlias: aliasInput.value.trim() || 'params',
      });
    });
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

function typeOf(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'array') {
    const inner = prop.items;
    const innerType = inner ? (Array.isArray(inner.type) ? inner.type[0] : inner.type) : 'string';
    return `list of ${innerType}`;
  }
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'date-time';
  return t ?? 'string';
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!;
  });
}

function escapeAttr(s: string): string {
  return escapeHtml(s);
}
