/**
 * TextBlockController — Stimulus controller for TipTap-based text editing.
 *
 * Creates a TipTap editor instance on connect and destroys it on disconnect.
 * Supports inline expression chips via the ExpressionChipNode extension.
 * Syncs content changes back to the headless editor.
 */

import { Controller } from "@hotwired/stimulus";
import { Editor } from "@tiptap/core";
import StarterKit from "@tiptap/starter-kit";
import Placeholder from "@tiptap/extension-placeholder";
import type { JSONContent } from "@tiptap/core";
import {
  buildEvaluationContext,
  evaluateJsonata,
  getExpressionCompletions,
  type TemplateEditor,
  type ScopeVariable,
} from "@epistola/headless-editor";
import { ExpressionChipNode } from "../extensions/expression-chip-node.js";
import { getEditorForElement } from "../mount.js";
import {
  applySuggestionAtCursor,
  formatExpressionPreviewValue,
} from "./expression-editor.js";

interface SuggestionRange {
  from: number;
  to: number;
}

export class TextBlockController extends Controller {
  static targets = ["editor"];
  static values = {
    blockId: String,
    content: String,
  };

  declare readonly editorTarget: HTMLElement;
  declare readonly hasEditorTarget: boolean;
  declare blockIdValue: string;
  declare contentValue: string;

  private tiptap: Editor | null = null;
  private updating = false;

  private chipPopover: HTMLElement | null = null;
  private chipInput: HTMLInputElement | null = null;
  private chipDropdown: HTMLElement | null = null;
  private chipPreview: HTMLElement | null = null;
  private chipCompletionRange: SuggestionRange = { from: 0, to: 0 };
  private activeChipPos: number | null = null;
  private activeChipIsNew = false;
  private chipPreviewRequestId = 0;

  private onEditorClickBound: ((event: Event) => void) | null = null;
  private onDocumentClickBound: ((event: Event) => void) | null = null;
  private onEditorDragEnterBound: ((event: DragEvent) => void) | null = null;
  private onEditorDragOverBound: ((event: DragEvent) => void) | null = null;
  private onEditorDropBound: ((event: DragEvent) => void) | null = null;

  connect(): void {
    if (!this.hasEditorTarget) return;

    const content = this.parseContent();

    this.tiptap = new Editor({
      element: this.editorTarget,
      editorProps: {
        handleDOMEvents: {
          dragstart: (_view, event) => this.blockNativeDragAndDrop(event),
          dragenter: (_view, event) => this.blockNativeDragAndDrop(event),
          dragover: (_view, event) => this.blockNativeDragAndDrop(event),
          drop: (_view, event) => this.blockNativeDragAndDrop(event),
        },
        handleDrop: (_view, event) => this.blockNativeDragAndDrop(event),
      },
      extensions: [
        StarterKit.configure({
          codeBlock: false,
          blockquote: false,
          heading: false,
          horizontalRule: false,
          bulletList: false,
          orderedList: false,
        }),
        ExpressionChipNode,
        Placeholder.configure({
          placeholder: "Type text here...",
        }),
      ],
      content: content ?? undefined,
      onUpdate: ({ editor }) => {
        if (this.updating) return;
        this.syncContent(editor.getJSON());
        this.openFirstNewChipIfAny();
      },
    });

    this.onEditorClickBound = (event: Event) => this.handleEditorClick(event);
    this.onDocumentClickBound = (event: Event) =>
      this.handleDocumentClick(event);
    this.onEditorDragEnterBound = (event: DragEvent) =>
      this.handleEditorDragEnter(event);
    this.onEditorDragOverBound = (event: DragEvent) =>
      this.handleEditorDragOver(event);
    this.onEditorDropBound = (event: DragEvent) => this.handleEditorDrop(event);
    this.editorTarget.addEventListener("click", this.onEditorClickBound);
    this.editorTarget.addEventListener(
      "dragenter",
      this.onEditorDragEnterBound,
      true,
    );
    this.editorTarget.addEventListener(
      "dragover",
      this.onEditorDragOverBound,
      true,
    );
    this.editorTarget.addEventListener("drop", this.onEditorDropBound, true);
    document.addEventListener("click", this.onDocumentClickBound);

    this.openFirstNewChipIfAny();
  }

  disconnect(): void {
    if (this.onEditorClickBound) {
      this.editorTarget.removeEventListener("click", this.onEditorClickBound);
      this.onEditorClickBound = null;
    }
    if (this.onEditorDragEnterBound) {
      this.editorTarget.removeEventListener(
        "dragenter",
        this.onEditorDragEnterBound,
        true,
      );
      this.onEditorDragEnterBound = null;
    }
    if (this.onEditorDragOverBound) {
      this.editorTarget.removeEventListener(
        "dragover",
        this.onEditorDragOverBound,
        true,
      );
      this.onEditorDragOverBound = null;
    }
    if (this.onEditorDropBound) {
      this.editorTarget.removeEventListener("drop", this.onEditorDropBound, true);
      this.onEditorDropBound = null;
    }
    if (this.onDocumentClickBound) {
      document.removeEventListener("click", this.onDocumentClickBound);
      this.onDocumentClickBound = null;
    }

    this.closeChipPopover(false);
    this.tiptap?.destroy();
    this.tiptap = null;
  }

  contentValueChanged(): void {
    if (!this.tiptap) return;
    if (this.tiptap.isFocused) return;

    const content = this.parseContent();
    if (content) {
      this.updating = true;
      this.tiptap.commands.setContent(content);
      this.updating = false;
    }
  }

  private parseContent(): JSONContent | null {
    if (!this.contentValue) return null;
    try {
      return JSON.parse(this.contentValue) as JSONContent;
    } catch {
      return null;
    }
  }

  private syncContent(json: JSONContent): void {
    const editor = this.getEditor();
    if (!editor) return;
    editor.updateBlock(this.blockIdValue, { content: json });
  }

  private handleEditorClick(event: Event): void {
    const target = event.target as HTMLElement | null;
    const chipEl = target?.closest(".expression-chip") as HTMLElement | null;
    if (!chipEl || !this.tiptap) return;

    event.preventDefault();
    event.stopPropagation();

    const view = this.tiptap.view;
    const pos = view.posAtDOM(chipEl, 0);
    const node = view.state.doc.nodeAt(pos);
    if (!node || node.type.name !== "expression") return;

    this.openChipPopoverAtPos(pos);
  }

  private handleDocumentClick(event: Event): void {
    if (!this.chipPopover) return;

    const target = event.target as Node;
    if (this.chipPopover.contains(target)) return;
    if (this.editorTarget.contains(target)) return;

    this.closeChipPopover(true);
  }

  private handleEditorDragOver(event: DragEvent): void {
    this.blockNativeDragAndDrop(event);
  }

  private handleEditorDrop(event: DragEvent): void {
    this.blockNativeDragAndDrop(event);
  }

  private handleEditorDragEnter(event: DragEvent): void {
    this.blockNativeDragAndDrop(event);
  }

  private blockNativeDragAndDrop(event: Event): boolean {
    event.preventDefault();
    event.stopPropagation();
    if (typeof (event as Event & { stopImmediatePropagation?: () => void })
      .stopImmediatePropagation === "function") {
      (event as Event & { stopImmediatePropagation: () => void })
        .stopImmediatePropagation();
    }

    const dragEvent = event as DragEvent;
    if (dragEvent.dataTransfer) {
      dragEvent.dataTransfer.dropEffect = "none";
    }

    return true;
  }

  private openFirstNewChipIfAny(): void {
    if (!this.tiptap) return;
    if (this.activeChipPos !== null) return;

    let chipPos: number | null = null;
    this.tiptap.state.doc.descendants((node, pos) => {
      if (node.type.name === "expression" && Boolean(node.attrs?.isNew)) {
        chipPos = pos;
        return false;
      }
      return true;
    });

    if (chipPos !== null) {
      this.openChipPopoverAtPos(chipPos);
    }
  }

  private openChipPopoverAtPos(pos: number): void {
    if (!this.tiptap) return;

    const node = this.tiptap.state.doc.nodeAt(pos);
    if (!node || node.type.name !== "expression") return;

    if (!this.chipPopover) {
      this.createChipPopover();
    }
    if (
      !this.chipPopover ||
      !this.chipInput ||
      !this.chipPreview ||
      !this.chipDropdown
    ) {
      return;
    }

    this.activeChipPos = pos;
    this.activeChipIsNew = Boolean(node.attrs?.isNew);

    this.chipInput.value = String(node.attrs?.expression ?? "");
    this.renderChipPreview("Evaluating...", false);
    this.positionChipPopover(pos);

    this.chipPopover.style.display = "block";
    this.chipInput.focus();
    this.chipInput.setSelectionRange(
      this.chipInput.value.length,
      this.chipInput.value.length,
    );

    this.updateChipDropdown();
    this.updateChipPreview();
  }

  private createChipPopover(): void {
    const popover = document.createElement("div");
    popover.className = "expression-chip-popover";
    popover.style.display = "none";

    const inputWrapper = document.createElement("div");
    inputWrapper.className = "expression-editor-input-wrapper";

    const input = document.createElement("input");
    input.type = "text";
    input.className =
      "form-control form-control-sm expression-chip-popover-input";
    input.placeholder = "customer.name";

    const dropdown = document.createElement("div");
    dropdown.className = "expression-editor-dropdown expression-chip-dropdown";
    dropdown.style.display = "none";

    inputWrapper.appendChild(input);
    inputWrapper.appendChild(dropdown);

    const preview = document.createElement("div");
    preview.className = "expression-popover-preview";

    const actions = document.createElement("div");
    actions.className = "expression-chip-popover-actions";

    const cancelBtn = document.createElement("button");
    cancelBtn.className = "btn btn-outline-secondary btn-sm";
    cancelBtn.type = "button";
    cancelBtn.textContent = "Cancel";

    const saveBtn = document.createElement("button");
    saveBtn.className = "btn btn-primary btn-sm";
    saveBtn.type = "button";
    saveBtn.textContent = "Save";

    actions.appendChild(cancelBtn);
    actions.appendChild(saveBtn);

    popover.appendChild(inputWrapper);
    popover.appendChild(preview);
    popover.appendChild(actions);
    this.element.appendChild(popover);

    input.addEventListener("input", () => {
      this.updateChipDropdown();
      this.updateChipPreview();
    });

    input.addEventListener("keydown", (event: KeyboardEvent) => {
      this.handleChipInputKeydown(event);
    });

    cancelBtn.addEventListener("click", () => {
      this.closeChipPopover(true);
    });

    saveBtn.addEventListener("click", () => {
      this.saveChipExpression();
    });

    this.chipPopover = popover;
    this.chipInput = input;
    this.chipDropdown = dropdown;
    this.chipPreview = preview;
  }

  private handleChipInputKeydown(event: KeyboardEvent): void {
    if (!this.chipDropdown || !this.chipInput) return;

    const isOpen = this.chipDropdown.style.display !== "none";
    const items = this.chipDropdown.querySelectorAll<HTMLElement>(
      ".expression-suggestion-item",
    );
    const active = this.chipDropdown.querySelector<HTMLElement>(
      ".expression-suggestion-item.active",
    );
    const activeIndex = active ? Array.from(items).indexOf(active) : -1;

    if (event.key === "Escape") {
      event.preventDefault();
      this.closeChipPopover(true);
      return;
    }

    if (event.key === "Enter") {
      event.preventDefault();
      if (isOpen && active) {
        this.selectChipSuggestion(active.dataset.value ?? "");
      } else {
        this.saveChipExpression();
      }
      return;
    }

    if (!isOpen) return;

    if (event.key === "ArrowDown") {
      event.preventDefault();
      this.highlightChipItem(
        items[activeIndex < items.length - 1 ? activeIndex + 1 : 0],
      );
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      this.highlightChipItem(
        items[activeIndex > 0 ? activeIndex - 1 : items.length - 1],
      );
      return;
    }

    if (event.key === "Tab" && active) {
      event.preventDefault();
      this.selectChipSuggestion(active.dataset.value ?? "");
    }
  }

  private updateChipDropdown(): void {
    if (!this.chipInput || !this.chipDropdown) return;

    const cursorPos =
      this.chipInput.selectionStart ?? this.chipInput.value.length;
    const beforeCursor = this.chipInput.value.slice(0, cursorPos);

    const completion = getExpressionCompletions({
      textBeforeCursor: beforeCursor,
      testData: this.getTestData(),
      scopeVars: this.getScopeVariables(),
    });

    if (!completion || completion.options.length === 0) {
      this.chipDropdown.style.display = "none";
      return;
    }

    this.chipCompletionRange = { from: completion.from, to: completion.to };

    while (this.chipDropdown.firstChild) {
      this.chipDropdown.removeChild(this.chipDropdown.firstChild);
    }

    for (const option of completion.options) {
      const item = document.createElement("div");
      item.className = "expression-suggestion-item";
      item.dataset.value = option.apply;

      const label = document.createElement("span");
      label.className = "expression-suggestion-label";
      label.textContent = option.label;

      const detail = document.createElement("span");
      detail.className = "expression-suggestion-detail";
      detail.textContent = option.detail;

      item.appendChild(label);
      item.appendChild(detail);

      item.addEventListener("mouseenter", () => this.highlightChipItem(item));
      item.addEventListener("click", (event) => {
        event.preventDefault();
        this.selectChipSuggestion(option.apply);
      });

      this.chipDropdown.appendChild(item);
    }

    this.chipDropdown.style.display = "block";
  }

  private highlightChipItem(item: HTMLElement | undefined): void {
    if (!this.chipDropdown || !item) return;

    this.chipDropdown
      .querySelectorAll(".expression-suggestion-item")
      .forEach((el) => el.classList.remove("active"));
    item.classList.add("active");
    item.scrollIntoView({ block: "nearest" });
  }

  private selectChipSuggestion(value: string): void {
    if (!this.chipInput || !this.chipDropdown) return;

    const cursorStart =
      this.chipInput.selectionStart ?? this.chipInput.value.length;
    const cursorEnd =
      this.chipInput.selectionEnd ?? this.chipInput.value.length;
    const beforeCursor = this.chipInput.value.slice(0, cursorStart);
    const afterCursor = this.chipInput.value.slice(cursorEnd);

    const next = applySuggestionAtCursor(
      beforeCursor,
      afterCursor,
      this.chipCompletionRange,
      value,
    );

    this.chipInput.value = next.text;
    this.chipInput.setSelectionRange(next.cursorPos, next.cursorPos);
    this.chipInput.focus();
    this.chipDropdown.style.display = "none";
    this.updateChipPreview();
  }

  private async updateChipPreview(): Promise<void> {
    if (!this.chipInput || !this.chipPreview) return;

    const expression = this.chipInput.value.trim();
    if (!expression) {
      this.renderChipPreview("Enter an expression", false);
      return;
    }

    const requestId = ++this.chipPreviewRequestId;
    this.renderChipPreview("Evaluating...", false);

    const result = await evaluateJsonata(
      expression,
      buildEvaluationContext(this.getTestData(), this.getScopeVariables()),
    );

    if (requestId !== this.chipPreviewRequestId) return;

    if (!result.success) {
      this.renderChipPreview(
        result.error ?? "Expression evaluation failed",
        true,
      );
      return;
    }

    this.renderChipPreview(formatExpressionPreviewValue(result.value), false);
  }

  private renderChipPreview(message: string, isError: boolean): void {
    if (!this.chipPreview) return;

    this.chipPreview.className = isError
      ? "expression-popover-preview error"
      : "expression-popover-preview success";
    this.chipPreview.textContent = message;
  }

  private saveChipExpression(): void {
    if (!this.tiptap || !this.chipInput || this.activeChipPos === null) return;

    const expression = this.chipInput.value.trim();
    const pos = this.activeChipPos;
    const node = this.tiptap.state.doc.nodeAt(pos);
    if (!node || node.type.name !== "expression") {
      this.closeChipPopover(false);
      return;
    }

    if (!expression && this.activeChipIsNew) {
      this.deleteChipAtPos(pos);
      this.closeChipPopover(false);
      return;
    }

    const attrs = {
      ...node.attrs,
      expression,
      isNew: false,
    };

    const tr = this.tiptap.state.tr.setNodeMarkup(pos, undefined, attrs);
    this.tiptap.view.dispatch(tr);
    this.closeChipPopover(false);
    this.tiptap.commands.focus();
  }

  private closeChipPopover(cancel: boolean): void {
    if (!this.tiptap || !this.chipPopover) return;

    const shouldDeleteOnCancel =
      cancel && this.activeChipIsNew && this.activeChipPos !== null;
    if (shouldDeleteOnCancel && this.activeChipPos !== null) {
      this.deleteChipAtPos(this.activeChipPos);
    }

    this.chipPopover.style.display = "none";
    if (this.chipDropdown) {
      this.chipDropdown.style.display = "none";
    }

    this.activeChipPos = null;
    this.activeChipIsNew = false;
    this.chipPreviewRequestId += 1;
  }

  private deleteChipAtPos(pos: number): void {
    if (!this.tiptap) return;
    const node = this.tiptap.state.doc.nodeAt(pos);
    if (!node || node.type.name !== "expression") return;

    const tr = this.tiptap.state.tr.delete(pos, pos + node.nodeSize);
    this.tiptap.view.dispatch(tr);
    this.tiptap.commands.focus();
  }

  private positionChipPopover(pos: number): void {
    if (!this.tiptap || !this.chipPopover) return;

    const nodeDom = this.tiptap.view.nodeDOM(pos) as HTMLElement | null;
    if (!nodeDom) return;

    const chipRect = nodeDom.getBoundingClientRect();
    const hostRect = this.element.getBoundingClientRect();

    let left = chipRect.left - hostRect.left;
    const top = chipRect.bottom - hostRect.top + 8;

    const maxLeft = Math.max(0, hostRect.width - 320);
    if (left > maxLeft) left = maxLeft;
    if (left < 0) left = 0;

    this.chipPopover.style.left = `${left}px`;
    this.chipPopover.style.top = `${top}px`;
  }

  private getTestData(): Record<string, unknown> {
    const editor = this.getEditor();
    if (!editor) return {};
    return editor.getState().testData ?? {};
  }

  private getScopeVariables(): ScopeVariable[] {
    const editor = this.getEditor();
    if (!editor) return [];
    return editor.getScopeVariables(this.blockIdValue);
  }

  private getEditor(): TemplateEditor | null {
    return getEditorForElement(this.element);
  }
}
