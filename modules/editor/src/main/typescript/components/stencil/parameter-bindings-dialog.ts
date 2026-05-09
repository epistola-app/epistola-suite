/**
 * Parameter-bindings dialog — modal for binding a stencil instance's
 * declared parameters to JSONata expressions. Used from the StencilInspector
 * for re-editing existing bindings; the stencil picker's step 4 has its own
 * inline equivalent for fresh insertions.
 *
 * Each row offers an inline expression input with autocomplete sourced from
 * the available `fieldPaths` at the insertion point (template variables,
 * iteration scope, system parameters), plus a "..." button that opens the
 * full `openExpressionDialog` (builder + code modes, live preview, custom
 * functions). Required parameters block confirmation when their expression
 * is empty.
 */
import type { JsonSchema, JsonSchemaProperty } from '../../data-contract/types.js';
import type { FieldPath } from '../../engine/schema-paths.js';
import { openExpressionDialog } from '../../ui/expression-dialog.js';

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
}

export function openParameterBindingsDialog(
  options: BindingsDialogOptions,
): Promise<BindingsDialogResult | null> {
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
    const fieldPaths = options.fieldPaths ?? [];
    const filtered = (fieldPaths: FieldPath[], paramType: string) =>
      filterFieldsByType(fieldPaths, paramType);

    for (const [name, prop] of Object.entries(properties)) {
      const row = renderRow(name, prop, required.has(name), bindings[name] ?? '');
      rowsContainer.appendChild(row.element);

      row.input.addEventListener('input', () => {
        const v = row.input.value.trim();
        if (v) bindings[name] = v;
        else delete bindings[name];
        updateSaveState();
      });
      row.advancedBtn.addEventListener('click', async () => {
        const result = await openExpressionDialog({
          initialValue: row.input.value,
          fieldPaths,
          getExampleData: options.getExampleData,
          label: `Expression for ${name}`,
          placeholder: 'e.g. recipient.name',
          enableBuilderMode: true,
          fieldPathFilter: (fp) =>
            filtered(fieldPaths, propTypeKey(prop)).some((f) => f.path === fp.path),
        });
        if (result.value === null) return;
        row.input.value = result.value;
        if (result.value.trim()) bindings[name] = result.value.trim();
        else delete bindings[name];
        updateSaveState();
      });
    }

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

interface RenderedRow {
  element: HTMLElement;
  input: HTMLInputElement;
  advancedBtn: HTMLButtonElement;
}

function renderRow(
  name: string,
  prop: JsonSchemaProperty | undefined,
  isRequired: boolean,
  initialValue: string,
): RenderedRow {
  const row = document.createElement('div');
  row.style.marginBottom = 'var(--ep-space-3)';
  const typeLabel = typeOf(prop);

  row.innerHTML = `
    <div style="display:flex; align-items:center; gap:var(--ep-space-2); margin-bottom:2px;">
      <label style="font-size: var(--ep-text-xs); font-weight:500;">${escapeHtml(name)}</label>
      <span style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground);">${typeLabel}</span>
      ${isRequired ? '<span style="font-size: var(--ep-text-xs); color: var(--ep-destructive, #dc2626);">required</span>' : ''}
    </div>
    ${prop?.description ? `<div style="font-size: var(--ep-text-xs); color: var(--ep-muted-foreground); margin-bottom:2px;">${escapeHtml(prop.description)}</div>` : ''}
    <div style="display:flex; gap: 4px; align-items: center;">
      <input type="text" class="ep-input bindings-input" style="flex: 1;" placeholder="JSONata expression — e.g. recipient.name" />
      <button type="button" class="stencil-picker-btn" data-advanced style="padding: 4px 10px;" title="Open expression editor">…</button>
    </div>
  `;

  const input = row.querySelector<HTMLInputElement>('.bindings-input')!;
  const advancedBtn = row.querySelector<HTMLButtonElement>('[data-advanced]')!;
  input.value = initialValue;

  return { element: row, input, advancedBtn };
}

/**
 * Coarse type matcher — accepts a field path if its declared type plausibly
 * fits the parameter slot. We're permissive (string accepts anything;
 * unknown/array fields show up regardless) because JSONata coerces freely.
 */
function filterFieldsByType(fieldPaths: FieldPath[], paramTypeKey: string): FieldPath[] {
  switch (paramTypeKey) {
    case 'string':
    case 'date':
    case 'datetime':
      return fieldPaths;
    case 'number':
    case 'integer':
      return fieldPaths.filter((fp) => fp.type === 'number' || fp.type === 'integer');
    case 'boolean':
      return fieldPaths.filter((fp) => fp.type === 'boolean');
    case 'array':
      return fieldPaths.filter((fp) => fp.type === 'array');
    default:
      return fieldPaths;
  }
}

function propTypeKey(prop: JsonSchemaProperty | undefined): string {
  if (!prop) return 'string';
  const t = Array.isArray(prop.type) ? prop.type[0] : prop.type;
  if (t === 'array') return 'array';
  if (t === 'string' && prop.format === 'date') return 'date';
  if (t === 'string' && prop.format === 'date-time') return 'datetime';
  return t ?? 'string';
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
