/**
 * Reusable expression dialog for editing JSONata expressions.
 *
 * Extracted from ExpressionNodeView to be shared between:
 * - Inline expression chips (ProseMirror nodes)
 * - Inspector expression fields (conditional, loop)
 *
 * Features:
 * - Field path autocomplete with filtering
 * - Instant JSONata validation (green/red border)
 * - Debounced live preview against example data
 * - JSONata quick reference panel
 */

import type { FieldPath } from "../engine/schema-paths.js";
import {
  tryEvaluateExpression,
  formatForPreview,
  isValidExpression,
} from "../engine/resolve-expression.js";

/** Common JSONata patterns for the quick reference panel. */
const JSONATA_QUICK_REFERENCE: { code: string; desc: string }[] = [
  { code: "customer.name", desc: "Access a field" },
  { code: 'address.line1 & ", " & address.city', desc: "Concatenate strings" },
  { code: 'age >= 18 ? "Adult" : "Minor"', desc: "Conditional" },
  { code: "$sum(items.price)", desc: "Sum numbers" },
  { code: "$count(items)", desc: "Count array items" },
  { code: '$join(tags, ", ")', desc: "Join array to string" },
  { code: "$uppercase(name)", desc: "Uppercase text" },
  { code: "$lowercase(name)", desc: "Lowercase text" },
  { code: "$now()", desc: "Current timestamp" },
  { code: "$substring(name, 0, 10)", desc: "Substring" },
  { code: "items[price > 100]", desc: "Filter array" },
  { code: "$number(value)", desc: "Convert to number" },
  { code: "$formatDate(date, 'dd-MM-yyyy')", desc: "Format a date" },
];

/** Date format presets for the format dropdown. */
const DATE_FORMAT_PRESETS: { value: string; label: string }[] = [
  { value: '', label: 'No formatting' },
  { value: 'dd-MM-yyyy', label: 'dd-MM-yyyy (15-01-2024)' },
  { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd (2024-01-15)' },
  { value: 'dd/MM/yyyy', label: 'dd/MM/yyyy (15/01/2024)' },
  { value: 'MM/dd/yyyy', label: 'MM/dd/yyyy (01/15/2024)' },
  { value: 'd MMMM yyyy', label: 'd MMMM yyyy (15 January 2024)' },
  { value: 'dd-MM-yyyy HH:mm', label: 'dd-MM-yyyy HH:mm (15-01-2024 14:30)' },
  { value: 'yyyy-MM-dd HH:mm', label: 'yyyy-MM-dd HH:mm (2024-01-15 14:30)' },
]

/** Regex to parse `$formatDate(fieldPath, 'pattern')` expressions. */
const FORMAT_DATE_REGEX = /^\$formatDate\(\s*([^,]+?)\s*,\s*'([^']+)'\s*\)$/

/** Extract field path and format pattern from a `$formatDate(...)` expression. */
export function parseFormatDateExpression(expr: string): { fieldPath: string; pattern: string } | null {
  const match = expr.match(FORMAT_DATE_REGEX)
  if (!match) return null
  return { fieldPath: match[1], pattern: match[2] }
}

/** Wrap a field path with `$formatDate(...)`. */
export function wrapFormatDate(fieldPath: string, pattern: string): string {
  return `$formatDate(${fieldPath}, '${pattern}')`
}

export interface ExpressionDialogOptions {
  initialValue: string;
  fieldPaths: FieldPath[];
  getExampleData?: () => Record<string, unknown> | undefined;
  label?: string;
  placeholder?: string;
  /** Optional filter to highlight certain field paths (e.g., array fields for loops). */
  fieldPathFilter?: (fp: FieldPath) => boolean;
  /**
   * Optional validator for the evaluated result.
   * Return an error message string if the result is invalid, or null if valid.
   * Called after successful evaluation — not called on parse errors or missing data.
   */
  resultValidator?: (value: unknown) => string | null;
}

export interface ExpressionDialogResult {
  /** The expression value, or null if the user cancelled. */
  value: string | null;
}

/**
 * Open a modal dialog for editing a JSONata expression.
 * Returns a promise that resolves when the dialog closes.
 */
export function openExpressionDialog(
  options: ExpressionDialogOptions,
): Promise<ExpressionDialogResult> {
  return new Promise((resolve) => {
    const {
      initialValue,
      fieldPaths,
      getExampleData,
      label = "Expression",
      placeholder = "e.g. customer.name",
      fieldPathFilter,
      resultValidator,
    } = options;

    let previewTimer: ReturnType<typeof setTimeout> | null = null;
    let previewGeneration = 0;

    const dialog = document.createElement("dialog");
    dialog.className = "expression-dialog";

    dialog.innerHTML = `
      <form method="dialog" class="expression-dialog-form">
        <label class="expression-dialog-label">${escapeHtml(label)}</label>
        <input
          type="text"
          class="expression-dialog-input"
          value="${escapeAttr(initialValue)}"
          placeholder="${escapeAttr(placeholder)}"
          autocomplete="off"
        />
        <div class="expression-dialog-format-row" style="display:none">
          <label class="expression-dialog-format-label">Date format</label>
          <select class="expression-dialog-format-select">
            ${DATE_FORMAT_PRESETS.map(p => `<option value="${escapeAttr(p.value)}">${escapeHtml(p.label)}</option>`).join('')}
          </select>
        </div>
        <div class="expression-dialog-preview" style="display:none"></div>
        <div class="expression-dialog-paths"></div>
        <details class="expression-dialog-reference">
          <summary class="expression-dialog-ref-summary">JSONata Quick Reference</summary>
          <div class="expression-dialog-ref-list"></div>
        </details>
        <div class="expression-dialog-actions">
          <button type="button" class="expression-dialog-btn cancel">Cancel</button>
          <button type="submit" class="expression-dialog-btn save">Save</button>
        </div>
      </form>
    `;

    const input = dialog.querySelector<HTMLInputElement>(".expression-dialog-input")!;
    const cancelBtn = dialog.querySelector(".cancel")!;
    const pathsContainer = dialog.querySelector<HTMLElement>(".expression-dialog-paths")!;
    const previewEl = dialog.querySelector<HTMLElement>(".expression-dialog-preview")!;
    const refList = dialog.querySelector<HTMLElement>(".expression-dialog-ref-list")!;
    const formatRow = dialog.querySelector<HTMLElement>(".expression-dialog-format-row")!;
    const formatSelect = dialog.querySelector<HTMLSelectElement>(".expression-dialog-format-select")!;

    // --- Date format dropdown ---
    const dateFieldPaths = new Set(fieldPaths.filter((fp) => fp.type === "date").map((fp) => fp.path));

    /** Get the bare field path from the current input (unwrapping $formatDate if present). */
    const getBarePath = (val: string): string => {
      const parsed = parseFormatDateExpression(val);
      return parsed ? parsed.fieldPath : val;
    };

    /** Show/hide the format dropdown based on whether the expression is a date field. */
    const updateFormatVisibility = () => {
      const barePath = getBarePath(input.value.trim());
      const isDateField = dateFieldPaths.has(barePath);
      formatRow.style.display = isDateField ? "" : "none";
      if (!isDateField) {
        formatSelect.value = "";
      }
    };

    // If opening with an existing $formatDate(...) expression, pre-select the format
    const initialParsed = parseFormatDateExpression(initialValue);
    if (initialParsed && dateFieldPaths.has(initialParsed.fieldPath)) {
      formatSelect.value = initialParsed.pattern;
    }

    // When format selection changes, rewrite the input expression
    formatSelect.addEventListener("change", () => {
      const barePath = getBarePath(input.value.trim());
      const pattern = formatSelect.value;
      if (pattern) {
        input.value = wrapFormatDate(barePath, pattern);
      } else {
        input.value = barePath;
      }
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });

    // --- Field paths ---
    renderFieldPaths(pathsContainer, input, fieldPaths, fieldPathFilter);

    // --- Quick reference ---
    renderQuickReference(refList, input);

    // --- Validation + preview ---
    const applyValidation = () => {
      const val = input.value.trim();
      input.classList.remove("valid", "invalid");
      if (val) {
        input.classList.add(isValidExpression(val) ? "valid" : "invalid");
      }
    };

    const cancelPreviewTimer = () => {
      if (previewTimer !== null) {
        clearTimeout(previewTimer);
        previewTimer = null;
      }
    };

    const schedulePreview = () => {
      cancelPreviewTimer();
      const val = input.value.trim();
      if (!val) {
        previewEl.style.display = "none";
        return;
      }
      previewTimer = setTimeout(() => {
        updatePreview(
          val,
          previewEl,
          getExampleData,
          () => ++previewGeneration,
          () => previewGeneration,
          resultValidator,
        );
      }, 250);
    };

    input.addEventListener("input", () => {
      applyValidation();
      updateFormatVisibility();
      schedulePreview();
    });

    // --- Close helpers ---
    const close = (value: string | null) => {
      cancelPreviewTimer();
      previewGeneration++; // discard in-flight previews
      dialog.close();
      dialog.remove();
      resolve({ value });
    };

    // Cancel
    cancelBtn.addEventListener("click", () => close(null));

    // Escape
    dialog.addEventListener("keydown", (e) => {
      if (e.key === "Escape") {
        e.preventDefault();
        close(null);
      }
    });

    // Submit
    dialog.querySelector("form")!.addEventListener("submit", (e) => {
      e.preventDefault();
      const trimmed = input.value.trim();
      close(trimmed || null);
    });

    // Backdrop click
    dialog.addEventListener("click", (e) => {
      if (e.target === dialog) close(null);
    });

    // Show
    document.body.appendChild(dialog);
    dialog.showModal();
    input.focus();
    input.select();

    // Initial validation + preview + format visibility
    if (initialValue) {
      applyValidation();
      updateFormatVisibility();
      updatePreview(
        initialValue,
        previewEl,
        getExampleData,
        () => ++previewGeneration,
        () => previewGeneration,
        resultValidator,
      );
    }
  });
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

function renderFieldPaths(
  container: HTMLElement,
  input: HTMLInputElement,
  fieldPaths: FieldPath[],
  fieldPathFilter?: (fp: FieldPath) => boolean,
): void {
  if (fieldPaths.length === 0) return;

  const dataFields = fieldPaths.filter((fp) => !fp.system);
  const systemFields = fieldPaths.filter((fp) => fp.system);

  const header = document.createElement("div");
  header.className = "expression-dialog-paths-header";

  const headerLabel = document.createElement("span");
  headerLabel.textContent = "Available fields";

  const filterInput = document.createElement("input");
  filterInput.type = "text";
  filterInput.className = "expression-dialog-filter";
  filterInput.placeholder = "Filter...";

  header.appendChild(headerLabel);
  header.appendChild(filterInput);
  container.appendChild(header);

  const list = document.createElement("ul");
  list.className = "expression-dialog-paths-list";

  const items: { li: HTMLLIElement; path: string }[] = [];

  // Render data fields
  for (const fp of dataFields) {
    const li = createFieldPathItem(fp, input, fieldPathFilter);
    list.appendChild(li);
    items.push({ li, path: fp.path });
  }

  // Render system parameters in a separate section
  if (systemFields.length > 0) {
    const sysHeader = document.createElement("li");
    sysHeader.className = "expression-dialog-section-header";
    sysHeader.textContent = "System parameters";
    list.appendChild(sysHeader);

    for (const fp of systemFields) {
      const li = createFieldPathItem(fp, input, fieldPathFilter);
      li.classList.add("system");
      if (fp.description) {
        li.title = fp.description;
      }
      list.appendChild(li);
      items.push({ li, path: fp.path });
    }
  }

  // Filter field paths on typing
  filterInput.addEventListener("input", () => {
    const query = filterInput.value.toLowerCase();
    for (const item of items) {
      item.li.style.display = item.path.toLowerCase().includes(query) ? "" : "none";
    }
    // Show/hide the system section header based on whether any system items match
    const sysHeaderEl = list.querySelector<HTMLElement>(".expression-dialog-section-header");
    if (sysHeaderEl) {
      const hasVisibleSystemItem = items.some(
        (item) => item.li.classList.contains("system") && item.li.style.display !== "none",
      );
      sysHeaderEl.style.display = hasVisibleSystemItem || !query ? "" : "none";
    }
  });

  container.appendChild(list);
}

function createFieldPathItem(
  fp: FieldPath,
  input: HTMLInputElement,
  fieldPathFilter?: (fp: FieldPath) => boolean,
): HTMLLIElement {
  const li = document.createElement("li");
  li.className = "expression-dialog-path-item";

  if (fieldPathFilter?.(fp)) {
    li.classList.add("highlighted");
  }

  const pathSpan = document.createElement("span");
  pathSpan.className = "expression-dialog-path-name";
  pathSpan.textContent = fp.path;

  const typeSpan = document.createElement("span");
  typeSpan.className = "expression-dialog-path-type";
  typeSpan.textContent = fp.type;

  li.appendChild(pathSpan);
  li.appendChild(typeSpan);

  li.addEventListener("click", () => {
    input.value = fp.path;
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.focus();
  });

  return li;
}

function renderQuickReference(container: HTMLElement, input: HTMLInputElement): void {
  for (const entry of JSONATA_QUICK_REFERENCE) {
    const row = document.createElement("div");
    row.className = "expression-dialog-ref-row";

    const code = document.createElement("code");
    code.className = "expression-dialog-ref-code";
    code.textContent = entry.code;

    const desc = document.createElement("span");
    desc.className = "expression-dialog-ref-desc";
    desc.textContent = entry.desc;

    row.appendChild(code);
    row.appendChild(desc);

    row.addEventListener("click", () => {
      input.value = entry.code;
      input.dispatchEvent(new Event("input", { bubbles: true }));
      input.focus();
    });

    container.appendChild(row);
  }
}

function updatePreview(
  expression: string,
  previewEl: HTMLElement,
  getExampleData: (() => Record<string, unknown> | undefined) | undefined,
  incrementGeneration: () => number,
  getGeneration: () => number,
  resultValidator?: (value: unknown) => string | null,
): void {
  const data = getExampleData?.();
  if (!data) {
    previewEl.style.display = "";
    previewEl.className = "expression-dialog-preview no-data";
    previewEl.textContent = "No data example selected";
    return;
  }

  const generation = incrementGeneration();
  tryEvaluateExpression(expression, data).then((result) => {
    if (generation !== getGeneration()) return; // stale

    previewEl.style.display = "";
    if (result.ok) {
      // Run result validator if provided (e.g., loop expressions must be arrays)
      const validationError = resultValidator?.(result.value);
      if (validationError) {
        previewEl.className = "expression-dialog-preview error";
        previewEl.textContent = validationError;
      } else {
        previewEl.className = "expression-dialog-preview success";
        previewEl.textContent = `Preview: ${formatForPreview(result.value)}`;
      }
    } else {
      previewEl.className = "expression-dialog-preview error";
      previewEl.textContent = result.error;
    }
  });
}

function escapeAttr(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

function escapeHtml(str: string): string {
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
