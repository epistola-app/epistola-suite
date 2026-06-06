/**
 * Parameter-bindings dialog — modal for binding a stencil instance's
 * declared parameters to JSONata expressions. Used from the StencilInspector
 * for re-editing existing bindings; the stencil picker's step 4 has its own
 * inline equivalent for fresh insertions, sharing the same `renderBindingRow`
 * helper.
 *
 * Required parameters block confirmation when their expression is empty.
 */
import type { JsonSchema } from '../../data-contract/types.js';
import type { FieldPath } from '../../engine/schema-paths.js';
import { isValidExpression } from '../../engine/resolve-expression.js';
import { renderBindingRow } from './binding-row.js';
import { RESERVED_ALIASES } from '../../engine/node-parameter-keys.js';

export interface BindingsDialogResult {
  bindings: Record<string, string>;
  paramsAlias: string;
}

export interface BindingsDialogOptions {
  title?: string;
  schema: JsonSchema;
  initialBindings: Record<string, string>;
  initialAlias: string;
  /** Fields available at the insertion point — passed through to openExpressionDialog. */
  fieldPaths?: FieldPath[];
  /** Example data for live preview inside openExpressionDialog. */
  getExampleData?: () => Record<string, unknown> | undefined;
  /** BCP-47 locale passed through to openExpressionDialog (preview + number examples). */
  locale?: string;
  /** Per-parameter backend validation errors to display inline (e.g. from a failed save). */
  bindingErrors?: Record<string, string>;
}

export function openParameterBindingsDialog(
  options: BindingsDialogOptions,
): Promise<BindingsDialogResult | null> {
  const bindingErrors = options.bindingErrors;
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = 'stencil-picker-dialog';
    dialog.style.maxWidth = 'min(720px, 90vw)';
    dialog.innerHTML = `
      <div class="stencil-picker-content">
        <div class="stencil-picker-header">
          <h3>${options.title ?? 'Configure parameters'}</h3>
          <button type="button" class="stencil-picker-close" aria-label="Close">&times;</button>
        </div>
        <div class="stencil-picker-section stencil-picker-scroll">
          <div class="stencil-picker-group-mb3">
            <label class="stencil-picker-label-inline">Alias</label>
            <input type="text" id="bindings-alias" class="ep-input stencil-picker-full" />
            <div id="bindings-alias-error" class="stencil-picker-error hidden"></div>
            <div class="stencil-picker-hint">
              Namespace this stencil's parameters live under inside its content (default <code>params</code>).
              Change to avoid shadowing when nested inside another parametrised component.
            </div>
          </div>
          <div id="bindings-rows"></div>
        </div>
        <div class="stencil-picker-footer">
          <div class="stencil-picker-flex-fill"></div>
          <button type="button" class="stencil-picker-btn cancel">Cancel</button>
          <button type="button" class="stencil-picker-btn insert save">Save</button>
        </div>
      </div>
    `;

    const aliasInput = dialog.querySelector<HTMLInputElement>('#bindings-alias')!;
    const aliasError = dialog.querySelector<HTMLElement>('#bindings-alias-error')!;
    const rowsContainer = dialog.querySelector<HTMLElement>('#bindings-rows')!;
    const closeBtn = dialog.querySelector('.stencil-picker-close')!;
    const cancelBtn = dialog.querySelector('.cancel')!;
    const saveBtn = dialog.querySelector('.save') as HTMLButtonElement;

    aliasInput.value = options.initialAlias || 'params';
    const bindings: Record<string, string> = { ...options.initialBindings };

    const required = new Set(options.schema.required ?? []);
    const properties = options.schema.properties ?? {};
    const fieldPaths = options.fieldPaths ?? [];

    for (const [name, prop] of Object.entries(properties)) {
      const row = renderBindingRow({
        name,
        prop,
        required: required.has(name),
        initialValue: bindings[name] ?? '',
        fieldPaths,
        getExampleData: options.getExampleData,
        locale: options.locale,
        onChange: (newValue) => {
          if (newValue) bindings[name] = newValue;
          else delete bindings[name];
          updateSaveState();
        },
        error: bindingErrors?.[name],
      });
      rowsContainer.appendChild(row.element);
    }

    function aliasIsReserved(): boolean {
      return RESERVED_ALIASES.has(aliasInput.value.trim());
    }

    function updateSaveState() {
      const reserved = aliasIsReserved();
      if (reserved) {
        aliasError.textContent = `Alias '${aliasInput.value.trim()}' collides with a reserved scope (${[...RESERVED_ALIASES].join(', ')}). Pick another name.`;
        aliasError.classList.remove('hidden');
      } else {
        aliasError.classList.add('hidden');
      }
      const allBound = Array.from(required).every(
        (name) => (bindings[name] ?? '').trim().length > 0,
      );
      const allValid = Object.values(bindings).every((expr) => {
        const trimmed = expr.trim();
        return trimmed.length === 0 || isValidExpression(trimmed);
      });
      saveBtn.disabled = !allBound || reserved || !allValid;
    }
    aliasInput.addEventListener('input', updateSaveState);
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
