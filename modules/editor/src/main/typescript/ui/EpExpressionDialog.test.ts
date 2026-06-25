// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EpExpressionDialog } from './EpExpressionDialog.js';
import * as resolveExpression from '../engine/resolve-expression.js';
import type { FieldPath } from '../engine/schema-paths.js';

type ExpressionResult = { ok: true; value: unknown } | { ok: false; error: string };

const testFieldPaths: FieldPath[] = [
  { path: 'name', type: 'string' },
  { path: 'invoiceDate', type: 'date' },
  { path: 'createdAt', type: 'datetime' },
  { path: 'customer.birthDate', type: 'date' },
  { path: 'total', type: 'number' },
  { path: 'items', type: 'array' },
  { path: 'item.name', type: 'string', scope: 'item' },
  { path: 'item.date', type: 'date', scope: 'item' },
  { path: '$pageNumber', type: 'number', system: true },
];

describe('EpExpressionDialog', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  // -----------------------------------------------------------------------
  // Property initialization
  // -----------------------------------------------------------------------

  it('initializes with default property values', () => {
    expect(component.initialValue).toBe('');
    expect(component.label).toBe('Expression');
    expect(component.placeholder).toBe('e.g. customer.name');
    expect(component.enableBuilderMode).toBe(false);
    expect(component.fieldPaths).toEqual([]);
  });

  it('reflects initialValue into internal expression state after show()', async () => {
    const fresh = new EpExpressionDialog();
    fresh.initialValue = 'customer.name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const input = fresh.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(input?.value).toBe('customer.name');
    fresh.close(null);
    fresh.remove();
  });

  it('renders the custom label', async () => {
    component.label = 'Loop Expression';
    await component.updateComplete;

    const label = component.querySelector('.expression-dialog-field-label');
    expect(label?.textContent?.trim()).toBe('Loop Expression');
  });

  // -----------------------------------------------------------------------
  // Dialog lifecycle
  // -----------------------------------------------------------------------

  it('show() calls showModal() on the dialog element and returns a promise', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    const showModalSpy = vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    const promise = component.show();

    expect(showModalSpy).toHaveBeenCalledOnce();
    expect(promise).toBeInstanceOf(Promise);

    // Clean up: close the dialog so the promise resolves
    component.close(null);
    await promise;
  });

  it('close() resolves the pending promise with the given value and removes the element', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = component.show();
    component.close('customer.name');

    const result = await promise;
    expect(result.value).toBe('customer.name');
    expect(document.body.contains(component)).toBe(false);
  });

  // -----------------------------------------------------------------------
  // Close interactions
  // -----------------------------------------------------------------------

  it('closes with null when cancel button is clicked', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = component.show();
    const cancelBtn = component.querySelector<HTMLButtonElement>('.expression-dialog-btn.cancel')!;
    cancelBtn.click();

    const result = await promise;
    expect(result.value).toBeNull();
  });

  it('closes with null when Escape is pressed', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = component.show();
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true });
    dialog.dispatchEvent(event);

    const result = await promise;
    expect(result.value).toBeNull();
  });

  it('closes with null when backdrop is clicked', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = component.show();
    const event = new MouseEvent('click', { bubbles: true });
    // Simulate clicking the dialog element itself (backdrop)
    dialog.dispatchEvent(event);

    const result = await promise;
    expect(result.value).toBeNull();
  });

  // -----------------------------------------------------------------------
  // Form submission
  // -----------------------------------------------------------------------

  it('closes with trimmed expression on form submit', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = '  customer.name  ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    const form = component.querySelector<HTMLFormElement>('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true }));

    const result = await promise;
    expect(result.value).toBe('customer.name');
  });

  it('closes with null on submit when expression is empty or whitespace-only', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = '   ';
    input.dispatchEvent(new Event('input', { bubbles: true }));

    const promise = component.show();
    await component.updateComplete;

    const form = component.querySelector<HTMLFormElement>('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true }));

    const result = await promise;
    expect(result.value).toBeNull();
  });

  // -----------------------------------------------------------------------
  // Validation styling
  // -----------------------------------------------------------------------

  it('adds "valid" class when expression is valid', async () => {
    vi.spyOn(resolveExpression, 'isValidExpression').mockReturnValue(true);

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'customer.name';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    const updatedInput = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    expect(updatedInput.classList.contains('valid')).toBe(true);
    expect(updatedInput.classList.contains('invalid')).toBe(false);
  });

  it('adds "invalid" class when expression is invalid', async () => {
    vi.spyOn(resolveExpression, 'isValidExpression').mockReturnValue(false);

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = '!!!';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    const updatedInput = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    expect(updatedInput.classList.contains('invalid')).toBe(true);
    expect(updatedInput.classList.contains('valid')).toBe(false);
  });

  it('has no validation class when expression is empty', () => {
    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    expect(input.classList.contains('valid')).toBe(false);
    expect(input.classList.contains('invalid')).toBe(false);
  });

  // -----------------------------------------------------------------------
  // Focus management
  // -----------------------------------------------------------------------

  it('focuses the input after show()', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    const focusSpy = vi.spyOn(input, 'focus').mockImplementation(() => {});
    const selectSpy = vi.spyOn(input, 'select').mockImplementation(() => {});

    component.show();

    // Wait for requestAnimationFrame
    await new Promise((resolve) => requestAnimationFrame(resolve));

    expect(focusSpy).toHaveBeenCalledOnce();
    expect(selectSpy).toHaveBeenCalledOnce();

    component.close(null);
  });
});

// ---------------------------------------------------------------------------
// Builder mode tests
// ---------------------------------------------------------------------------

describe('EpExpressionDialog builder mode', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    component.enableBuilderMode = true;
    component.fieldPaths = testFieldPaths;
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  it('renders mode toggle when enableBuilderMode is true', async () => {
    await component.updateComplete;

    const toggle = component.querySelector('.expression-dialog-mode-toggle');
    expect(toggle).not.toBeNull();
    expect(toggle?.textContent).toContain('Builder');
    expect(toggle?.textContent).toContain('Code');
  });

  it('defaults to builder mode for empty initial value after show', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const builderPanel = component.querySelector('[data-mode-panel="builder"]');
    expect(builderPanel?.getAttribute('style')).not.toContain('display: none');
    component.close(null);
  });

  it('defaults to builder mode for a simple field path after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const builderPanel = fresh.querySelector('[data-mode-panel="builder"]');
    expect(builderPanel?.getAttribute('style')).not.toContain('display: none');
    fresh.close(null);
    fresh.remove();
  });

  it('defaults to code mode for complex expression after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name & " " & total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const codePanel = fresh.querySelector('[data-mode-panel="code"]');
    expect(codePanel?.getAttribute('style')).not.toContain('display: none');
    fresh.close(null);
    fresh.remove();
  });

  it('populates builder field select from initialValue after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'invoiceDate';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const fieldSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    expect(fieldSelect?.value).toBe('invoiceDate');
    fresh.close(null);
    fresh.remove();
  });

  it('shows format dropdown for date fields in builder mode after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatContainer = fresh.querySelector<HTMLElement>('.expression-dialog-builder-format');
    expect(formatContainer?.getAttribute('style')).not.toContain('display: none');

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    expect(formatSelect?.value).toBe('dd-MM-yyyy');
    fresh.close(null);
    fresh.remove();
  });

  it('omits date-time format patterns for a plain date field', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    const values = Array.from(formatSelect?.options ?? []).map((o) => o.value);
    expect(values).toContain('dd-MM-yyyy');
    expect(values).not.toContain('dd-MM-yyyy HH:mm');
    expect(values).not.toContain('yyyy-MM-dd HH:mm');
    fresh.close(null);
    fresh.remove();
  });

  it('offers date-time format patterns for a datetime field', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(createdAt, 'dd-MM-yyyy HH:mm')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    const values = Array.from(formatSelect?.options ?? []).map((o) => o.value);
    // Both the date-only and the date-time patterns are offered.
    expect(values).toContain('dd-MM-yyyy');
    expect(values).toContain('dd-MM-yyyy HH:mm');
    // The date-time pattern round-trips into builder state.
    expect(fresh['_builderFormatPattern']).toBe('dd-MM-yyyy HH:mm');
    fresh.close(null);
    fresh.remove();
  });

  it('opens in code mode (not builder) for a time pattern on a plain date field', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    // invoiceDate is a `date` field; a time-of-day pattern is invalid for it.
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy HH:mm')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // The invalid time pattern must NOT survive in builder state; the dialog
    // falls back to code mode rather than showing a dropdown that can't
    // represent it.
    const codePanel = fresh.querySelector('[data-mode-panel="code"]');
    expect(codePanel?.getAttribute('style')).not.toContain('display: none');
    expect(fresh['_builderFormatPattern']).toBe('');
    fresh.close(null);
    fresh.remove();
  });

  it('hides format dropdown for non-date fields in builder mode', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatContainer = fresh.querySelector<HTMLElement>('.expression-dialog-builder-format');
    expect(formatContainer?.getAttribute('style')).toContain('display: none');
    fresh.close(null);
    fresh.remove();
  });

  it('switches from builder to code mode', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const codeBtn = component.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]');
    codeBtn?.click();
    await component.updateComplete;

    expect(component['_mode']).toBe('code');
    const codePanel = component.querySelector('[data-mode-panel="code"]');
    expect(codePanel?.getAttribute('style')).not.toContain('display: none');
    component.close(null);
  });

  it('switches from code to builder mode for simple expression', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Start in builder mode because it's a simple expression
    expect(fresh['_mode']).toBe('builder');

    // Switch to code
    const codeBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]');
    codeBtn?.click();
    await fresh.updateComplete;
    expect(fresh['_mode']).toBe('code');

    // Switch back to builder
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    builderBtn?.click();
    await fresh.updateComplete;
    expect(fresh['_mode']).toBe('builder');
    fresh.close(null);
    fresh.remove();
  });

  it('shows persistent warning when switching to builder with complex expression', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name & " " & total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Start in code mode
    expect(fresh['_mode']).toBe('code');

    // Try to switch to builder
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    builderBtn?.click();
    await fresh.updateComplete;

    // Should stay in code mode and show warning
    expect(fresh['_mode']).toBe('code');
    const warning = fresh.querySelector('.expression-dialog-mode-warning');
    expect(warning?.textContent).toContain('too complex for Builder mode');
    fresh.close(null);
    fresh.remove();
  });

  it('shows stale field reference warning', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'oldField';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Try to switch to builder
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    builderBtn?.click();
    await fresh.updateComplete;

    const warning = fresh.querySelector('.expression-dialog-mode-warning');
    expect(warning?.textContent).toContain('not found');
    expect(warning?.textContent).toContain('scope may have changed');
    fresh.close(null);
    fresh.remove();
  });

  it('clears warning when switching back to code', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name & " " & total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Trigger warning
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    builderBtn?.click();
    await fresh.updateComplete;

    expect(fresh.querySelector('.expression-dialog-mode-warning')).not.toBeNull();

    // Switch to code - warning should clear
    const codeBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]');
    codeBtn?.click();
    await fresh.updateComplete;

    expect(fresh.querySelector('.expression-dialog-mode-warning')).toBeNull();
    fresh.close(null);
    fresh.remove();
  });

  it('disables builder toggle for non-parseable expressions', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name & " " & total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    expect(builderBtn?.classList.contains('disabled')).toBe(true);
    fresh.close(null);
    fresh.remove();
  });

  it('auto-dismisses mode warning after 3 seconds', async () => {
    vi.useFakeTimers();
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name & " " & total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    builderBtn?.click();
    await fresh.updateComplete;

    expect(fresh.querySelector('.expression-dialog-mode-warning')).not.toBeNull();

    vi.advanceTimersByTime(3000);
    await fresh.updateComplete;

    expect(fresh.querySelector('.expression-dialog-mode-warning')).toBeNull();

    fresh.close(null);
    fresh.remove();
    vi.useRealTimers();
  });

  it('syncs builder selection to code expression', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const fieldSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    fieldSelect!.value = 'invoiceDate';
    fieldSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    // Now select a date format
    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    formatSelect!.value = 'dd-MM-yyyy';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    // The internal expression should be synced
    expect(fresh['_expression']).toBe("$formatDate(invoiceDate, 'dd-MM-yyyy')");
    fresh.close(null);
    fresh.remove();
  });

  it('submits builder-built expression on save', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(dialog, 'close').mockImplementation(() => {});

    const promise = fresh.show();
    await fresh.updateComplete;

    // Change field in builder
    const fieldSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    fieldSelect!.value = 'total';
    fieldSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const form = fresh.querySelector<HTMLFormElement>('form')!;
    form.dispatchEvent(new Event('submit', { bubbles: true }));

    const result = await promise;
    expect(result.value).toBe('total');
    fresh.remove();
  });

  it('hides field picker and quick reference in builder mode, showing a Code hint instead', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    // Builder mode: the code-only tools are not in the DOM at all, so they
    // can't silently capture a pick that Save (which reads builder state)
    // would discard.
    expect(component['_mode']).toBe('builder');
    expect(component.querySelector('.expression-dialog-paths')).toBeNull();
    expect(component.querySelector('.expression-dialog-reference')).toBeNull();

    const hint = component.querySelector('.expression-dialog-builder-hint');
    expect(hint).not.toBeNull();
    expect(hint?.textContent).toContain('Code');

    // The hint's Code link switches to code mode and reveals the tools.
    const link = component.querySelector<HTMLButtonElement>('.expression-dialog-builder-hint-link');
    link?.click();
    await component.updateComplete;
    await new Promise((resolve) => requestAnimationFrame(resolve));

    expect(component['_mode']).toBe('code');
    expect(component.querySelector('.expression-dialog-builder-hint')).toBeNull();
    expect(component.querySelector('.expression-dialog-paths')).not.toBeNull();
    expect(component.querySelector('.expression-dialog-reference')).not.toBeNull();

    component.close(null);
  });

  it('disables Builder for a custom (non-preset) date format and opens in Code', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'yyyy')"; // 'yyyy' is not a preset
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Not builder-representable → opens in Code with Builder disabled.
    expect(fresh['_mode']).toBe('code');
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    expect(builderBtn?.classList.contains('disabled')).toBe(true);

    // Attempting to switch shows a format-specific warning and stays in Code.
    builderBtn?.click();
    await fresh.updateComplete;
    expect(fresh['_mode']).toBe('code');
    const warning = fresh.querySelector('.expression-dialog-mode-warning');
    expect(warning?.textContent).toContain('date format');

    fresh.close(null);
    fresh.remove();
  });

  it('keeps Builder available for a preset date format', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy')"; // a preset
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    expect(fresh['_mode']).toBe('builder');
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    expect(builderBtn?.classList.contains('disabled')).toBe(false);
    fresh.close(null);
    fresh.remove();
  });

  it('shows format dropdown for number fields in builder mode after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatLocaleNumber(total, '#,##0.00')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatContainer = fresh.querySelector<HTMLElement>('.expression-dialog-builder-format');
    expect(formatContainer?.getAttribute('style')).not.toContain('display: none');

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    expect(formatSelect?.value).toBe('#,##0.00');
    fresh.close(null);
    fresh.remove();
  });

  it('wraps expression with $formatLocaleNumber on number format selection', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'name';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Pick a number field, then a number format preset.
    const fieldSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    fieldSelect!.value = 'total';
    fieldSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    );
    formatSelect!.value = '#,##0.00';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    expect(fresh['_expression']).toBe("$formatLocaleNumber(total, '#,##0.00')");
    fresh.close(null);
    fresh.remove();
  });

  it('clears a stale date pattern when switching to a number field', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Switch from the date field to a number field: the date pattern must not
    // leak into a $formatLocaleNumber call.
    const fieldSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-field-select');
    fieldSelect!.value = 'total';
    fieldSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    expect(fresh['_builderFormatPattern']).toBe('');
    expect(fresh['_expression']).toBe('total');
    fresh.close(null);
    fresh.remove();
  });

  it('disables Builder for a custom (non-preset) number format and opens in Code', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatLocaleNumber(total, '#0.000')"; // not a preset
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    expect(fresh['_mode']).toBe('code');
    const builderBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="builder"]');
    expect(builderBtn?.classList.contains('disabled')).toBe(true);

    builderBtn?.click();
    await fresh.updateComplete;
    expect(fresh['_mode']).toBe('code');
    const warning = fresh.querySelector('.expression-dialog-mode-warning');
    expect(warning?.textContent).toContain('number format');

    fresh.close(null);
    fresh.remove();
  });
});

// ---------------------------------------------------------------------------
// Code mode date format tests
// ---------------------------------------------------------------------------

describe('EpExpressionDialog code mode date format', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    component.fieldPaths = testFieldPaths;
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  it('shows date format dropdown for date field expressions after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'invoiceDate';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatRow = fresh.querySelector<HTMLElement>('.expression-dialog-format-row');
    expect(formatRow?.getAttribute('style')).not.toContain('display: none');
    fresh.close(null);
    fresh.remove();
  });

  it('hides date format dropdown for non-date fields', async () => {
    await component.updateComplete;

    const formatRow = component.querySelector<HTMLElement>('.expression-dialog-format-row');
    expect(formatRow?.getAttribute('style')).toContain('display: none');
  });

  it('populates date format select from $formatDate expression after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'yyyy-MM-dd')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-format-select');
    expect(formatSelect?.value).toBe('yyyy-MM-dd');
    fresh.close(null);
    fresh.remove();
  });

  it('wraps expression with $formatDate on date format selection', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'invoiceDate';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-format-select');
    formatSelect!.value = 'dd/MM/yyyy';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const textarea = fresh.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toBe("$formatDate(invoiceDate, 'dd/MM/yyyy')");
    fresh.close(null);
    fresh.remove();
  });

  it('unwraps $formatDate when selecting "No formatting"', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatDate(invoiceDate, 'dd-MM-yyyy')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-format-select');
    formatSelect!.value = '';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const textarea = fresh.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toBe('invoiceDate');
    fresh.close(null);
    fresh.remove();
  });

  it('does not show the code-box date format row when builder mode is enabled', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'invoiceDate'; // a date field
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Builder mode owns date formatting (its own Format dropdown). The code
    // box must not duplicate it — even after switching to code mode.
    const codeBtn = fresh.querySelector<HTMLButtonElement>('.mode-btn[data-mode="code"]');
    codeBtn?.click();
    await fresh.updateComplete;
    expect(fresh['_mode']).toBe('code');

    const formatRow = fresh.querySelector<HTMLElement>('.expression-dialog-format-row');
    expect(formatRow?.getAttribute('style')).toContain('display: none');
    fresh.close(null);
    fresh.remove();
  });

  it('shows a number format dropdown for number field expressions after show', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatRow = fresh.querySelector<HTMLElement>('.expression-dialog-format-row');
    expect(formatRow?.getAttribute('style')).not.toContain('display: none');
    const label = fresh.querySelector('.expression-dialog-format-label');
    expect(label?.textContent?.trim()).toBe('Number format');
    fresh.close(null);
    fresh.remove();
  });

  it('wraps expression with $formatLocaleNumber on number format selection', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-format-select');
    formatSelect!.value = '#,##0.00';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const textarea = fresh.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toBe("$formatLocaleNumber(total, '#,##0.00')");
    fresh.close(null);
    fresh.remove();
  });

  it('unwraps $formatLocaleNumber when selecting "No formatting"', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = "$formatLocaleNumber(total, '#,##0.00')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>('.expression-dialog-format-select');
    expect(formatSelect?.value).toBe('#,##0.00');
    formatSelect!.value = '';
    formatSelect!.dispatchEvent(new Event('change', { bubbles: true }));
    await fresh.updateComplete;

    const textarea = fresh.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toBe('total');
    fresh.close(null);
    fresh.remove();
  });
});

// ---------------------------------------------------------------------------
// Preview panel tests
// ---------------------------------------------------------------------------

describe('EpExpressionDialog preview panel', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    component.fieldPaths = testFieldPaths;
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  it('shows empty state when no expression is entered', async () => {
    await component.updateComplete;

    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Start typing to see a preview');
    expect(preview?.classList.contains('empty')).toBe(true);
  });

  it('shows no-data state when getExampleData is not provided', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'customer.name';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    // Wait for debounce
    await new Promise((resolve) => setTimeout(resolve, 300));
    await component.updateComplete;

    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('No data example selected');
    expect(preview?.classList.contains('no-data')).toBe(true);
    component.close(null);
  });

  it('shows loading state immediately and then success on evaluation', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.getExampleData = () => ({ customer: { name: 'Ada' } });
    vi.spyOn(resolveExpression, 'tryEvaluateExpression').mockResolvedValue({
      ok: true,
      value: 'Ada',
    } as ExpressionResult);

    component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'customer.name';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    // Immediately after input, should be loading
    let preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.classList.contains('loading')).toBe(true);
    expect(preview?.textContent).toContain('Evaluating');

    // Wait for debounce + async evaluation
    await new Promise((resolve) => setTimeout(resolve, 350));
    await component.updateComplete;

    preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Preview: Ada');
    expect(preview?.classList.contains('success')).toBe(true);
    component.close(null);
  });

  it('shows error state on evaluation failure', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.getExampleData = () => ({ customer: { name: 'Ada' } });
    vi.spyOn(resolveExpression, 'tryEvaluateExpression').mockResolvedValue({
      ok: false,
      error: 'Path not found',
    } as ExpressionResult);

    component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'unknown.path';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    // Wait for debounce + async evaluation
    await new Promise((resolve) => setTimeout(resolve, 350));
    await component.updateComplete;

    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Path not found');
    expect(preview?.classList.contains('error')).toBe(true);
    component.close(null);
  });

  it('runs resultValidator on successful evaluation', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.getExampleData = () => ({ items: 'not-an-array' });
    component.resultValidator = (value: unknown) =>
      Array.isArray(value) ? null : 'Must be an array';
    vi.spyOn(resolveExpression, 'tryEvaluateExpression').mockResolvedValue({
      ok: true,
      value: 'not-an-array',
    } as ExpressionResult);

    component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;
    input.value = 'items';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    await new Promise((resolve) => setTimeout(resolve, 350));
    await component.updateComplete;

    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Must be an array');
    expect(preview?.classList.contains('error')).toBe(true);
    component.close(null);
  });

  it('discards stale preview results when a newer expression is entered', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.getExampleData = () => ({ customer: { name: 'Ada' } });

    let callCount = 0;
    vi.spyOn(resolveExpression, 'tryEvaluateExpression').mockImplementation(async () => {
      callCount++;
      if (callCount === 1) {
        // First call is slow
        await new Promise((resolve) => setTimeout(resolve, 500));
        return { ok: true, value: 'Slow' } as ExpressionResult;
      }
      return { ok: true, value: 'Fast' } as ExpressionResult;
    });

    component.show();
    await component.updateComplete;

    const input = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input')!;

    // First input — triggers slow evaluation
    input.value = 'slow.expr';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    // Wait a bit but not long enough for the first evaluation to complete
    await new Promise((resolve) => setTimeout(resolve, 300));

    // Second input — triggers fast evaluation, cancels first
    input.value = 'fast.expr';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    // Wait for the fast evaluation to complete
    await new Promise((resolve) => setTimeout(resolve, 350));
    await component.updateComplete;

    // Should show the fast result, not the slow one
    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Fast');
    component.close(null);
  });

  it('triggers preview on initial show when initialValue is set', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.initialValue = 'customer.name';
    fresh.getExampleData = () => ({ customer: { name: 'Ada' } });
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    vi.spyOn(resolveExpression, 'tryEvaluateExpression').mockResolvedValue({
      ok: true,
      value: 'Ada',
    } as ExpressionResult);

    fresh.show();
    await fresh.updateComplete;

    // Wait for debounce + evaluation
    await new Promise((resolve) => setTimeout(resolve, 350));
    await fresh.updateComplete;

    const preview = fresh.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('Preview: Ada');
    fresh.close(null);
    fresh.remove();
  });

  it('has aria-live="polite" on the preview panel', async () => {
    await component.updateComplete;

    const preview = component.querySelector('.expression-dialog-preview');
    expect(preview?.getAttribute('aria-live')).toBe('polite');
    expect(preview?.getAttribute('aria-atomic')).toBe('true');
  });
});

// ---------------------------------------------------------------------------
// Field paths + quick reference tests
// ---------------------------------------------------------------------------

describe('EpExpressionDialog field paths', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    component.fieldPaths = testFieldPaths;
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  it('renders field paths grouped by section', async () => {
    await component.updateComplete;

    const list = component.querySelector('.expression-dialog-paths-list');
    expect(list).not.toBeNull();
    expect(list?.textContent).toContain('Template variables');
    expect(list?.textContent).toContain('name');
    expect(list?.textContent).toContain('System parameters');
    expect(list?.textContent).toContain('$pageNumber');
  });

  it('shows empty message when no field paths', async () => {
    component.remove();
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = [];
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    expect(fresh.querySelector('.expression-dialog-paths-empty')?.textContent).toContain(
      'No fields available',
    );
    fresh.remove();
  });

  it('filters field paths on filter input', async () => {
    await component.updateComplete;

    const filterInput = component.querySelector<HTMLInputElement>('.expression-dialog-filter');
    filterInput!.value = 'page';
    filterInput!.dispatchEvent(new Event('input', { bubbles: true }));
    await component.updateComplete;

    const items = component.querySelectorAll('.expression-dialog-path-item');
    expect(items.length).toBe(1);
    expect(items[0]?.textContent).toContain('$pageNumber');
  });

  it('inserts field path into expression on click', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const item = component.querySelector('.expression-dialog-path-item');
    (item as HTMLElement)?.click();
    await component.updateComplete;

    const textarea = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toBe('name');
    component.close(null);
  });

  it('marks disabled items with aria-disabled', async () => {
    component.pathDisabled = (fp) => (fp.type === 'array' ? 'Arrays not allowed' : null);
    await component.updateComplete;

    const items = component.querySelectorAll('.expression-dialog-path-item');
    const arrayItem = Array.from(items).find((el) => el.textContent?.includes('items'));
    expect(arrayItem?.getAttribute('aria-disabled')).toBe('true');
    expect(arrayItem?.classList.contains('disabled')).toBe(true);
  });

  it('marks highlighted items', async () => {
    component.fieldPathFilter = (fp) => fp.type === 'array';
    await component.updateComplete;

    const items = component.querySelectorAll('.expression-dialog-path-item');
    const arrayItem = Array.from(items).find((el) => el.textContent?.includes('items'));
    expect(arrayItem?.classList.contains('highlighted')).toBe(true);
  });

  it('makes field path items keyboard accessible', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const item = component.querySelector('.expression-dialog-path-item');
    expect(item?.getAttribute('tabindex')).toBe('0');
    expect(item?.getAttribute('role')).toBe('button');
    component.close(null);
  });
});

describe('EpExpressionDialog quick reference', () => {
  let component: EpExpressionDialog;

  beforeEach(() => {
    component = new EpExpressionDialog();
    component.fieldPaths = testFieldPaths;
    document.body.appendChild(component);
  });

  afterEach(() => {
    component.remove();
  });

  it('renders quick reference entries', async () => {
    await component.updateComplete;

    const refList = component.querySelector('.expression-dialog-ref-list');
    expect(refList).not.toBeNull();
    expect(refList?.textContent).toContain('Access a field');
    expect(refList?.textContent).toContain('customer.name');
  });

  it('is collapsed by default and preserves open state across re-renders', async () => {
    await component.updateComplete;

    const details = component.querySelector<HTMLDetailsElement>('.expression-dialog-reference')!;
    expect(details.open).toBe(false);
    expect(component['_refExpanded']).toBe(false);

    // User expands it — the native toggle must sync component state.
    details.open = true;
    details.dispatchEvent(new Event('toggle'));
    await component.updateComplete;
    expect(component['_refExpanded']).toBe(true);

    // An unrelated re-render must not snap the disclosure shut.
    component.label = 'Changed';
    await component.updateComplete;
    const after = component.querySelector<HTMLDetailsElement>('.expression-dialog-reference')!;
    expect(after.open).toBe(true);
  });

  it('inserts quick reference code on click', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    component.show();
    await component.updateComplete;

    const rows = component.querySelectorAll('.expression-dialog-ref-row');
    const concatRow = Array.from(rows).find((el) => el.textContent?.includes('Concatenate'));
    (concatRow as HTMLElement)?.click();
    await component.updateComplete;

    const textarea = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toContain('address.line1');
    component.close(null);
  });

  it('makes quick reference rows keyboard accessible', async () => {
    await component.updateComplete;

    const row = component.querySelector('.expression-dialog-ref-row');
    expect(row?.getAttribute('tabindex')).toBe('0');
    expect(row?.getAttribute('role')).toBe('button');
  });

  it('is not rendered in builder mode (code-only tool)', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.enableBuilderMode = true;
    component.fieldPaths = testFieldPaths;
    component.show();
    await component.updateComplete;

    // Should start in builder mode
    expect(component['_mode']).toBe('builder');
    const builderPanel = component.querySelector('.expression-dialog-builder');
    expect(builderPanel?.getAttribute('style')).not.toContain('display: none');

    // The quick reference is a code-mode tool and must not be in the DOM in
    // builder mode — otherwise a click would write code-mode state that Save
    // (reading builder state) silently discards.
    expect(component.querySelector('.expression-dialog-reference')).toBeNull();
    expect(component.querySelectorAll('.expression-dialog-ref-row').length).toBe(0);

    component.close(null);
  });
});

// ---------------------------------------------------------------------------
// Locale awareness (number format examples + preview)
// ---------------------------------------------------------------------------

describe('EpExpressionDialog locale awareness', () => {
  beforeEach(() => {
    // Earlier describes spy on `tryEvaluateExpression` without restoring it;
    // the preview test below needs the real implementation to format with the
    // locale, so clear any leaked spies first.
    vi.restoreAllMocks();
  });

  afterEach(() => {
    document.querySelectorAll('ep-expression-dialog').forEach((el) => el.remove());
  });

  it('renders number format example labels with the session locale separators', async () => {
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.locale = 'nl-NL';
    fresh.initialValue = 'total'; // a number field → builder shows the Format dropdown
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    )!;
    const amountOption = Array.from(formatSelect.options).find((o) => o.value === '#,##0.00')!;
    // Dutch culture: '.' grouping, ',' decimal — exactly what the user asked for.
    expect(amountOption.textContent?.trim()).toBe('Decimal, grouped (1.234,50)');
  });

  it('localizes the month name in date format example labels (nl-NL)', async () => {
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.locale = 'nl-NL';
    fresh.initialValue = 'invoiceDate'; // a date field → builder shows the Format dropdown
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    )!;
    const monthNameOption = Array.from(formatSelect.options).find(
      (o) => o.value === 'd MMMM yyyy',
    )!;
    // Dutch month name (lowercase per CLDR), not English 'January'.
    expect(monthNameOption.textContent?.trim()).toBe('d MMMM yyyy (15 januari 2024)');
    // A numeric pattern is locale-agnostic by design — same in every culture.
    const numericOption = Array.from(formatSelect.options).find((o) => o.value === 'dd-MM-yyyy')!;
    expect(numericOption.textContent?.trim()).toBe('dd-MM-yyyy (15-01-2024)');
  });

  it('uses en-US separators in the example labels when the locale is en-US', async () => {
    const fresh = new EpExpressionDialog();
    fresh.enableBuilderMode = true;
    fresh.fieldPaths = testFieldPaths;
    fresh.locale = 'en-US';
    fresh.initialValue = 'total';
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    const formatSelect = fresh.querySelector<HTMLSelectElement>(
      '.expression-dialog-builder-format-select',
    )!;
    const amountOption = Array.from(formatSelect.options).find((o) => o.value === '#,##0.00')!;
    expect(amountOption.textContent?.trim()).toBe('Decimal, grouped (1,234.50)');
  });

  it('formats the live preview with the session locale', async () => {
    const fresh = new EpExpressionDialog();
    fresh.fieldPaths = testFieldPaths;
    fresh.locale = 'nl-NL';
    fresh.getExampleData = () => ({ total: 1234.5 });
    fresh.initialValue = "$formatLocaleNumber(total, '#,##0.00')";
    document.body.appendChild(fresh);
    await fresh.updateComplete;

    const dialog = fresh.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});
    fresh.show();
    await fresh.updateComplete;

    // Wait for the debounce (250ms) + async evaluation.
    await new Promise((resolve) => setTimeout(resolve, 350));
    await fresh.updateComplete;

    const preview = fresh.querySelector('.expression-dialog-preview');
    expect(preview?.textContent).toContain('1.234,50');
  });
});
