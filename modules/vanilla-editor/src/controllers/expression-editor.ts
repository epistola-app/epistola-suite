/**
 * ExpressionEditorController — Stimulus controller for expression input with autocomplete.
 *
 * Used by conditional and loop blocks to edit JSONata expressions.
 * Provides autocomplete from test data paths and scope variables (loop items).
 *
 * Replaces the original JS controller with:
 * - TypeScript types throughout
 * - `getEditor()` module import instead of `window.__editor`
 * - Core `getScopeVariables()` API instead of hand-rolled tree walking
 * - Editor store access instead of `window.__editorTestData`
 *
 * Stimulus values:
 * - `blockId` (String) — the block ID
 * - `blockType` (String) — "conditional" or "loop"
 * - `expression` (String) — the current expression
 *
 * Stimulus targets:
 * - `input` — the expression input field
 * - `dropdown` — the autocomplete dropdown container
 * - `preview` — the preview display area
 */

import { Controller } from "@hotwired/stimulus";
import {
  extractPaths,
  parsePath,
  resolvePathType,
  getMethodsForType,
  formatTypeForDisplay,
  evaluateJsonataString,
} from "@epistola/headless-editor";
import type { ScopeVariable } from "@epistola/headless-editor";
import { getEditor } from "../mount.js";

interface Suggestion {
  label: string;
  type: string;
  detail: string;
  value: string;
  priority: number;
}

export class ExpressionEditorController extends Controller {
  static targets = ["input", "dropdown", "preview"];
  static values = {
    blockId: String,
    blockType: String,
    expression: String,
  };

  declare readonly inputTarget: HTMLInputElement;
  declare readonly hasInputTarget: boolean;
  declare readonly dropdownTarget: HTMLElement;
  declare readonly hasDropdownTarget: boolean;
  declare readonly previewTarget: HTMLElement;
  declare readonly hasPreviewTarget: boolean;
  declare blockIdValue: string;
  declare blockTypeValue: string;
  declare expressionValue: string;

  private isOpen = false;
  private value = "";
  private notifyTimer: ReturnType<typeof setTimeout> | null = null;
  private boundDocumentClick: ((e: Event) => void) | null = null;

  connect(): void {
    this.value = this.expressionValue || "";

    if (this.hasInputTarget) {
      this.inputTarget.value = this.value;
    }

    this.updatePreview();

    this.boundDocumentClick = (e: Event) => this.handleDocumentClick(e);
    document.addEventListener("click", this.boundDocumentClick);
  }

  disconnect(): void {
    if (this.boundDocumentClick) {
      document.removeEventListener("click", this.boundDocumentClick);
      this.boundDocumentClick = null;
    }
    if (this.notifyTimer) {
      clearTimeout(this.notifyTimer);
      this.notifyTimer = null;
    }
  }

  /** Stimulus value callback — sync external expression changes. */
  expressionValueChanged(): void {
    if (
      this.hasInputTarget &&
      this.inputTarget.value !== this.expressionValue
    ) {
      this.inputTarget.value = this.expressionValue || "";
      this.value = this.expressionValue || "";
      this.updatePreview();
    }
  }

  // ==========================================================================
  // Event handlers (wired via data-action attributes)
  // ==========================================================================

  handleInput(event: Event): void {
    event.stopPropagation();
    this.value = this.inputTarget.value;
    this.debouncedNotifyChange();
    this.updateDropdown();
    this.updatePreview();
  }

  handleFocus(): void {
    this.updateDropdown();
    this.updatePreview();
  }

  handleBlur(): void {
    if (this.notifyTimer) {
      clearTimeout(this.notifyTimer);
      this.notifyTimer = null;
      this.notifyChange();
    }
    setTimeout(() => {
      if (
        this.hasDropdownTarget &&
        !this.dropdownTarget.contains(document.activeElement)
      ) {
        this.closeDropdown();
      }
    }, 150);
  }

  handleKeydown(event: KeyboardEvent): void {
    if (!this.isOpen) {
      if (event.key === "Enter") {
        event.preventDefault();
        this.notifyChange();
      }
      return;
    }

    const items = this.dropdownTarget.querySelectorAll<HTMLElement>(
      ".expression-suggestion-item",
    );
    const active = this.dropdownTarget.querySelector<HTMLElement>(
      ".expression-suggestion-item.active",
    );
    const activeIndex = active ? Array.from(items).indexOf(active) : -1;

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        this.highlightItem(
          items[activeIndex < items.length - 1 ? activeIndex + 1 : 0],
        );
        break;
      case "ArrowUp":
        event.preventDefault();
        this.highlightItem(
          items[activeIndex > 0 ? activeIndex - 1 : items.length - 1],
        );
        break;
      case "Enter":
        event.preventDefault();
        if (active) {
          this.selectSuggestion(active.dataset.value ?? "");
        } else {
          this.notifyChange();
        }
        break;
      case "Escape":
        this.closeDropdown();
        break;
      case "Tab":
        if (active) {
          event.preventDefault();
          this.selectSuggestion(active.dataset.value ?? "");
        }
        break;
    }
  }

  selectSuggestionFromClick(event: Event): void {
    event.stopPropagation();
    const target = event.currentTarget as HTMLElement;
    this.selectSuggestion(target.dataset.value ?? "");
  }

  highlightFromMouse(event: Event): void {
    this.highlightItem(event.currentTarget as HTMLElement);
  }

  // ==========================================================================
  // Dropdown management
  // ==========================================================================

  private updateDropdown(): void {
    if (!this.hasInputTarget || !this.hasDropdownTarget) return;

    const text = this.getTextBeforeCursor();
    const { path, partial } = this.parsePartialExpression(text);

    const suggestions =
      path.length === 0
        ? this.getTopLevelCompletions(partial)
        : this.getPathCompletions(path, partial);

    if (suggestions.length === 0) {
      this.closeDropdown();
      return;
    }

    this.renderDropdown(suggestions);
  }

  private renderDropdown(suggestions: Suggestion[]): void {
    while (this.dropdownTarget.firstChild) {
      this.dropdownTarget.removeChild(this.dropdownTarget.firstChild);
    }

    for (const s of suggestions) {
      const item = document.createElement("div");
      item.className = "expression-suggestion-item";
      item.dataset.value = s.value;
      item.dataset.action =
        "click-&gt;expression-editor#selectSuggestionFromClick mouseenter-&gt;expression-editor#highlightFromMouse";

      const label = document.createElement("span");
      label.className = "expression-suggestion-label";
      label.textContent = s.label;
      item.appendChild(label);

      const detail = document.createElement("span");
      detail.className = "expression-suggestion-detail";
      detail.textContent = s.detail;
      item.appendChild(detail);

      this.dropdownTarget.appendChild(item);
    }

    this.isOpen = true;
    this.dropdownTarget.style.display = "block";
  }

  private closeDropdown(): void {
    this.isOpen = false;
    if (this.hasDropdownTarget) {
      this.dropdownTarget.style.display = "none";
    }
  }

  private highlightItem(item: HTMLElement | undefined): void {
    if (!this.hasDropdownTarget || !item) return;
    this.dropdownTarget
      .querySelectorAll(".expression-suggestion-item")
      .forEach((el) => {
        el.classList.remove("active");
      });
    item.classList.add("active");
    item.scrollIntoView({ block: "nearest" });
  }

  private selectSuggestion(value: string): void {
    const beforeCursor = this.getTextBeforeCursor();
    const afterCursor = this.getTextAfterCursor();

    const lastDot = beforeCursor.lastIndexOf(".");
    const lastBracket = beforeCursor.lastIndexOf("[");

    let insertPoint: number;
    if (lastDot > lastBracket) {
      insertPoint = lastDot + 1;
    } else if (lastBracket >= 0) {
      insertPoint = lastBracket;
    } else {
      insertPoint = 0;
    }

    const newBeforeCursor = beforeCursor.slice(0, insertPoint) + value;
    this.inputTarget.value = newBeforeCursor + afterCursor;

    const cursorPos = newBeforeCursor.length;
    this.inputTarget.setSelectionRange(cursorPos, cursorPos);
    this.inputTarget.focus();

    this.value = this.inputTarget.value;
    this.notifyChange();
    this.closeDropdown();
    this.updatePreview();
  }

  // ==========================================================================
  // Preview
  // ==========================================================================

  private async updatePreview(): Promise<void> {
    if (!this.hasPreviewTarget) return;

    const trimmed = this.value.trim();
    if (!trimmed) {
      this.setPreviewMessage("Enter an expression", "text-muted small");
      return;
    }

    this.setPreviewMessage("Evaluating...", "text-muted small");

    try {
      const testData = this.getTestData();
      const result = await evaluateJsonataString(trimmed, { ...testData });
      this.renderPreviewResult(true, this.formatPreviewValue(result));
    } catch (error: unknown) {
      const msg = error instanceof Error ? error.message : String(error);
      this.renderPreviewResult(false, msg);
    }
  }

  private setPreviewMessage(message: string, className: string): void {
    while (this.previewTarget.firstChild) {
      this.previewTarget.removeChild(this.previewTarget.firstChild);
    }
    const span = document.createElement("span");
    span.className = className;
    span.textContent = message;
    this.previewTarget.appendChild(span);
  }

  private renderPreviewResult(isSuccess: boolean, message: string): void {
    if (!this.hasPreviewTarget) return;

    while (this.previewTarget.firstChild) {
      this.previewTarget.removeChild(this.previewTarget.firstChild);
    }

    const wrapper = document.createElement("span");
    wrapper.className = isSuccess
      ? "expression-preview-success"
      : "expression-preview-error";

    const label = document.createElement("span");
    label.className = "expression-preview-label";
    label.textContent = isSuccess ? "Preview:" : "Error:";
    wrapper.appendChild(label);

    const code = document.createElement("code");
    code.className = "expression-preview-value";
    code.textContent = message;
    wrapper.appendChild(code);

    this.previewTarget.appendChild(wrapper);
  }

  private formatPreviewValue(value: unknown): string {
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    if (typeof value === "object") {
      const json = JSON.stringify(value);
      return json.length > 50 ? json.slice(0, 50) + "..." : json;
    }
    return String(value);
  }

  // ==========================================================================
  // Autocomplete logic
  // ==========================================================================

  private parsePartialExpression(text: string): {
    path: string[];
    partial: string;
  } {
    const trimmed = text.trim();
    if (!trimmed) return { path: [], partial: "" };

    if (trimmed.endsWith(".")) {
      return { path: parsePath(trimmed.slice(0, -1)), partial: "" };
    }

    const segments = parsePath(trimmed);
    if (segments.length === 0) return { path: [], partial: "" };

    return {
      path: segments.slice(0, -1),
      partial: segments[segments.length - 1],
    };
  }

  private getTopLevelCompletions(filter: string): Suggestion[] {
    const completions: Suggestion[] = [];
    const scopeVariables = this.getScopeVariables();
    const testData = this.getTestData();

    for (const scopeVar of scopeVariables) {
      if (
        !filter ||
        scopeVar.name.toLowerCase().startsWith(filter.toLowerCase())
      ) {
        completions.push({
          label: scopeVar.name,
          type: "variable",
          detail:
            scopeVar.type === "loop-index"
              ? "loop index"
              : `loop item from ${scopeVar.arrayPath}`,
          value: scopeVar.name,
          priority: 10,
        });
      }
    }

    const paths = extractPaths(testData);
    for (const p of paths) {
      if (!filter || p.path.toLowerCase().startsWith(filter.toLowerCase())) {
        completions.push({
          label: p.path,
          type: p.isArray ? "array" : "property",
          detail: p.type,
          value: p.path,
          priority: 5,
        });
      }
    }

    return completions.sort((a, b) => b.priority - a.priority);
  }

  private getPathCompletions(path: string[], filter: string): Suggestion[] {
    const testData = this.getTestData();
    const scopeVariables = this.getScopeVariables();
    const type = resolvePathType(path, testData, scopeVariables);

    if (type.kind === "unknown" && !filter) return [];

    const suggestions: Suggestion[] = [];

    const methods = getMethodsForType(type);
    for (const method of methods) {
      if (
        !filter ||
        method.label.toLowerCase().startsWith(filter.toLowerCase())
      ) {
        const applyValue =
          method.type === "method" ? `${method.label}()` : method.label;
        suggestions.push({
          label: method.label,
          type: method.type,
          detail: method.detail,
          value: applyValue,
          priority: method.type === "property" ? 8 : 5,
        });
      }
    }

    if (type.kind === "object") {
      for (const [key, propType] of Object.entries(type.properties)) {
        if (!filter || key.toLowerCase().startsWith(filter.toLowerCase())) {
          suggestions.push({
            label: key,
            type: "property",
            detail: formatTypeForDisplay(propType),
            value: key,
            priority: 10,
          });
        }
      }
    }

    if (type.kind === "array") {
      suggestions.push({
        label: "[0]",
        type: "property",
        detail: `access ${formatTypeForDisplay(type.elementType)}`,
        value: "[0]",
        priority: 15,
      });
    }

    return suggestions;
  }

  // ==========================================================================
  // Helpers
  // ==========================================================================

  private getTextBeforeCursor(): string {
    const pos = this.inputTarget.selectionStart ?? 0;
    return this.inputTarget.value.slice(0, pos);
  }

  private getTextAfterCursor(): string {
    const pos = this.inputTarget.selectionEnd ?? this.inputTarget.value.length;
    return this.inputTarget.value.slice(pos);
  }

  /** Get test data from the editor's store instead of window.__editorTestData. */
  private getTestData(): Record<string, unknown> {
    const editor = getEditor();
    if (!editor) return {};
    return editor.getState().testData ?? {};
  }

  /** Get scope variables using the core API instead of hand-rolled tree walking. */
  private getScopeVariables(): ScopeVariable[] {
    const editor = getEditor();
    if (!editor) return [];
    return editor.getScopeVariables(this.blockIdValue);
  }

  private debouncedNotifyChange(): void {
    if (this.notifyTimer) clearTimeout(this.notifyTimer);
    this.notifyTimer = setTimeout(() => {
      this.notifyTimer = null;
      this.notifyChange();
    }, 300);
  }

  private notifyChange(): void {
    const editor = getEditor();
    if (!editor) return;

    const blockId = this.blockIdValue;
    const blockType = this.blockTypeValue;
    const updateKey = blockType === "loop" ? "expression" : "condition";

    const block = editor.findBlock(blockId);
    if (block) {
      const existing = (block as unknown as Record<string, unknown>)[
        updateKey
      ] as Record<string, unknown> | undefined;
      editor.updateBlock(blockId, {
        [updateKey]: { ...(existing ?? {}), raw: this.value },
      });
    }
  }

  private handleDocumentClick(event: Event): void {
    if (!this.element.contains(event.target as Node)) {
      this.closeDropdown();
    }
  }
}
