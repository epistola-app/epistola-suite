// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { EpExpressionDialog } from './EpExpressionDialog.js';
import * as resolveExpression from '../engine/resolve-expression.js';
import type { FieldPath } from '../engine/schema-paths.js';

type ExpressionResult = { ok: true; value: unknown } | { ok: false; error: string };

const testFieldPaths: FieldPath[] = [
  { path: 'name', type: 'string' },
  { path: 'invoiceDate', type: 'date' },
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
    expect(warning?.textContent).toContain('loop alias');
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

  it('switches to code mode when a quick-reference row is clicked in builder mode', async () => {
    const dialog = component.querySelector<HTMLDialogElement>('dialog')!;
    vi.spyOn(dialog, 'showModal').mockImplementation(() => {});

    component.enableBuilderMode = true;
    component.fieldPaths = testFieldPaths;
    component.show();
    await component.updateComplete;

    // Should start in builder mode
    const builderPanel = component.querySelector('.expression-dialog-builder');
    expect(builderPanel?.getAttribute('style')).not.toContain('display: none');

    const rows = component.querySelectorAll('.expression-dialog-ref-row');
    const concatRow = Array.from(rows).find((el) => el.textContent?.includes('Concatenate'));
    (concatRow as HTMLElement)?.click();
    await component.updateComplete;
    await new Promise((resolve) => requestAnimationFrame(resolve));

    // Should now be in code mode with the expression inserted
    const codePanel = component.querySelector('.expression-dialog-code');
    expect(codePanel?.getAttribute('style')).not.toContain('display: none');

    const textarea = component.querySelector<HTMLTextAreaElement>('.expression-dialog-input');
    expect(textarea?.value).toContain('address.line1');

    component.close(null);
  });
});
