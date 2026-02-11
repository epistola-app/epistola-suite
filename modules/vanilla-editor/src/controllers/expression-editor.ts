/**
 * ExpressionEditorController — Stimulus controller for expression input with autocomplete.
 *
 * Used by conditional and loop blocks to edit JSONata expressions.
 * Uses shared completion/type/context helpers from @epistola/headless-editor.
 */

import { Controller } from "@hotwired/stimulus";
import {
  buildEvaluationContext,
  evaluateJsonata,
  getExpressionCompletions,
} from "@epistola/headless-editor";
import type {
  ScopeVariable,
  ExpressionCompletionItem,
  EvaluationResult,
} from "@epistola/headless-editor";
import { getEditor } from "../mount.js";

interface RenderableSuggestion {
  item: ExpressionCompletionItem;
  value: string;
}

interface CompletionRange {
  from: number;
  to: number;
}

export function formatExpressionPreviewValue(value: unknown): string {
  if (value === undefined) return "undefined";
  if (value === null) return "null";
  if (typeof value === "object") {
    const json = JSON.stringify(value);
    return json.length > 50 ? `${json.slice(0, 50)}...` : json;
  }
  return String(value);
}

export function coerceConditionalResult(value: unknown): boolean {
  if (Array.isArray(value) && value.length === 0) return false;
  return Boolean(value);
}

export function getBlockTypeWarning(
  blockType: string,
  value: unknown,
): string | null {
  if (blockType === "loop" && !Array.isArray(value)) {
    return "Expected an array for loop expression";
  }

  if (blockType === "conditional") {
    if (typeof value === "boolean") return null;
    return `Condition coerces to ${coerceConditionalResult(value)}`;
  }

  return null;
}

export function applySuggestionAtCursor(
  beforeCursor: string,
  afterCursor: string,
  range: CompletionRange,
  value: string,
): { text: string; cursorPos: number } {
  const safeFrom = Math.max(0, Math.min(range.from, beforeCursor.length));
  const newBeforeCursor = beforeCursor.slice(0, safeFrom) + value;
  return {
    text: `${newBeforeCursor}${afterCursor}`,
    cursorPos: newBeforeCursor.length,
  };
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
  private completionRange: CompletionRange = { from: 0, to: 0 };
  private previewRequestId = 0;

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

  private updateDropdown(): void {
    if (!this.hasInputTarget || !this.hasDropdownTarget) return;

    const beforeCursor = this.getTextBeforeCursor();
    const completion = getExpressionCompletions({
      textBeforeCursor: beforeCursor,
      testData: this.getTestData(),
      scopeVars: this.getScopeVariables(),
    });

    if (!completion || completion.options.length === 0) {
      this.closeDropdown();
      return;
    }

    this.completionRange = { from: completion.from, to: completion.to };
    const suggestions: RenderableSuggestion[] = completion.options.map((item) => ({
      item,
      value: item.apply,
    }));
    this.renderDropdown(suggestions);
  }

  private renderDropdown(suggestions: RenderableSuggestion[]): void {
    while (this.dropdownTarget.firstChild) {
      this.dropdownTarget.removeChild(this.dropdownTarget.firstChild);
    }

    for (const suggestion of suggestions) {
      const item = document.createElement("div");
      item.className = "expression-suggestion-item";
      item.dataset.value = suggestion.value;
      item.dataset.action =
        "click-&gt;expression-editor#selectSuggestionFromClick mouseenter-&gt;expression-editor#highlightFromMouse";

      const label = document.createElement("span");
      label.className = "expression-suggestion-label";
      label.textContent = suggestion.item.label;
      item.appendChild(label);

      const detail = document.createElement("span");
      detail.className = "expression-suggestion-detail";
      detail.textContent = suggestion.item.detail;
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
    const next = applySuggestionAtCursor(
      beforeCursor,
      afterCursor,
      this.completionRange,
      value,
    );

    this.inputTarget.value = next.text;
    this.inputTarget.setSelectionRange(next.cursorPos, next.cursorPos);
    this.inputTarget.focus();

    this.value = this.inputTarget.value;
    this.notifyChange();
    this.closeDropdown();
    this.updatePreview();
  }

  private async updatePreview(): Promise<void> {
    if (!this.hasPreviewTarget) return;

    const trimmed = this.value.trim();
    if (!trimmed) {
      this.setPreviewMessage("Enter an expression", "text-muted small");
      return;
    }

    const requestId = ++this.previewRequestId;
    this.setPreviewMessage("Evaluating...", "text-muted small");

    const result = await this.evaluate(trimmed);
    if (requestId !== this.previewRequestId) return;

    if (!result.success) {
      this.renderPreviewResult(false, result.error ?? "Expression evaluation failed");
      return;
    }

    const warning = getBlockTypeWarning(this.blockTypeValue, result.value);
    this.renderPreviewResult(true, formatExpressionPreviewValue(result.value), warning);
  }

  private async evaluate(expression: string): Promise<EvaluationResult> {
    try {
      const context = buildEvaluationContext(
        this.getTestData(),
        this.getScopeVariables(),
      );
      return await evaluateJsonata(expression, context);
    } catch (error: unknown) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
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

  private renderPreviewResult(
    isSuccess: boolean,
    message: string,
    warning?: string | null,
  ): void {
    if (!this.hasPreviewTarget) return;

    while (this.previewTarget.firstChild) {
      this.previewTarget.removeChild(this.previewTarget.firstChild);
    }

    const wrapper = document.createElement("div");
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

    if (warning && isSuccess) {
      const warningEl = document.createElement("div");
      warningEl.className = "expression-preview-warning";
      warningEl.textContent = warning;
      this.previewTarget.appendChild(warningEl);
    }
  }

  private getTextBeforeCursor(): string {
    const pos = this.inputTarget.selectionStart ?? 0;
    return this.inputTarget.value.slice(0, pos);
  }

  private getTextAfterCursor(): string {
    const pos = this.inputTarget.selectionEnd ?? this.inputTarget.value.length;
    return this.inputTarget.value.slice(pos);
  }

  private getTestData(): Record<string, unknown> {
    const editor = getEditor();
    if (!editor) return {};
    return editor.getState().testData ?? {};
  }

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
