/**
 * ExpressionEditorController - Stimulus controller for expression input with autocomplete
 *
 * Used by both Conditional and Loop blocks to edit JSONata expressions.
 * Provides autocomplete from test data paths and scope variables (loop items).
 */

import { Controller } from "@hotwired/stimulus";
import {
  extractPaths,
  parsePath,
  resolvePathType,
  getMethodsForType,
  formatTypeForDisplay,
  evaluateJsonataString,
} from "/headless-editor/headless-editor.js";
import { log } from "/editor/utils/editor-logger.js";

export class ExpressionEditorController extends Controller {
  static targets = ["input", "dropdown", "preview"];
  static values = {
    blockId: String,
    blockType: String,
    expression: String,
  };

  connect() {
    this.isOpen = false;
    this.value = this.expressionValue || "";
    this._notifyTimer = null;

    log.info("expr-editor", "connect", { blockId: this.blockIdValue, blockType: this.blockTypeValue, expression: this.value });

    // Set initial input value
    if (this.hasInputTarget) {
      this.inputTarget.value = this.value;
    }

    // Initialize preview
    this.updatePreview();

    // Bind document click handler for closing dropdown
    this._handleDocumentClick = this._handleDocumentClick.bind(this);
    document.addEventListener("click", this._handleDocumentClick);
  }

  disconnect() {
    log.info("expr-editor", "disconnect", { blockId: this.blockIdValue });
    document.removeEventListener("click", this._handleDocumentClick);
    if (this._notifyTimer) {
      clearTimeout(this._notifyTimer);
      this._notifyTimer = null;
    }
  }

  // =========================================================================
  // Value changed callback
  // =========================================================================
  expressionValueChanged() {
    if (this.hasInputTarget && this.inputTarget.value !== this.expressionValue) {
      this.inputTarget.value = this.expressionValue || "";
      this.value = this.expressionValue || "";
      this.updatePreview();
    }
  }

  // =========================================================================
  // Event handlers
  // =========================================================================
  handleInput(event) {
    event.stopPropagation();
    this.value = this.inputTarget.value;
    // Debounce notifyChange to avoid re-render disrupting dropdown/preview
    this.debouncedNotifyChange();
    this.updateDropdown();
    this.updatePreview();
  }

  handleFocus() {
    this.updateDropdown();
    this.updatePreview();
  }

  handleBlur() {
    // Flush any pending debounced change so value is saved
    if (this._notifyTimer) {
      clearTimeout(this._notifyTimer);
      this._notifyTimer = null;
      this.notifyChange();
    }
    // Delay closing to allow dropdown clicks
    setTimeout(() => {
      if (this.hasDropdownTarget && !this.dropdownTarget.contains(document.activeElement)) {
        this.closeDropdown();
      }
    }, 150);
  }

  handleKeydown(event) {
    if (!this.isOpen) {
      if (event.key === "Enter") {
        event.preventDefault();
        this.notifyChange();
      }
      return;
    }

    const items = this.dropdownTarget.querySelectorAll(".expression-suggestion-item");
    const active = this.dropdownTarget.querySelector(".expression-suggestion-item.active");
    const activeIndex = active ? Array.from(items).indexOf(active) : -1;

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        this.highlightItem(items[activeIndex < items.length - 1 ? activeIndex + 1 : 0]);
        break;
      case "ArrowUp":
        event.preventDefault();
        this.highlightItem(items[activeIndex > 0 ? activeIndex - 1 : items.length - 1]);
        break;
      case "Enter":
        event.preventDefault();
        if (active) {
          this.selectSuggestion(active.dataset.value);
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
          this.selectSuggestion(active.dataset.value);
        }
        break;
    }
  }

  _handleDocumentClick(event) {
    if (!this.element.contains(event.target)) {
      this.closeDropdown();
    }
  }

  // =========================================================================
  // Dropdown management
  // =========================================================================
  updateDropdown() {
    if (!this.hasInputTarget || !this.hasDropdownTarget) return;

    const text = this.getTextBeforeCursor();
    const { path, partial } = this.parsePartialExpression(text);

    let suggestions = [];
    if (path.length === 0) {
      suggestions = this.getTopLevelCompletions(partial);
    } else {
      suggestions = this.getPathCompletions(path, partial);
    }

    if (suggestions.length === 0) {
      this.closeDropdown();
      return;
    }

    this.renderDropdown(suggestions);
  }

  renderDropdown(suggestions) {
    // Clear existing content safely
    while (this.dropdownTarget.firstChild) {
      this.dropdownTarget.removeChild(this.dropdownTarget.firstChild);
    }

    for (const s of suggestions) {
      const item = document.createElement("div");
      item.className = "expression-suggestion-item";
      item.dataset.value = s.value;
      item.dataset.action = "click->expression-editor#selectSuggestionFromClick mouseenter->expression-editor#highlightFromMouse";

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

  closeDropdown() {
    this.isOpen = false;
    if (this.hasDropdownTarget) {
      this.dropdownTarget.style.display = "none";
    }
  }

  highlightItem(item) {
    if (!this.hasDropdownTarget) return;
    this.dropdownTarget.querySelectorAll(".expression-suggestion-item").forEach((el) => {
      el.classList.remove("active");
    });
    if (item) {
      item.classList.add("active");
      item.scrollIntoView({ block: "nearest" });
    }
  }

  highlightFromMouse(event) {
    this.highlightItem(event.currentTarget);
  }

  selectSuggestionFromClick(event) {
    event.stopPropagation();
    const value = event.currentTarget.dataset.value;
    this.selectSuggestion(value);
  }

  selectSuggestion(value) {
    const beforeCursor = this.getTextBeforeCursor();
    const afterCursor = this.getTextAfterCursor();

    const lastDot = beforeCursor.lastIndexOf(".");
    const lastBracket = beforeCursor.lastIndexOf("[");

    let insertPoint;
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

  // =========================================================================
  // Preview management
  // =========================================================================
  async updatePreview() {
    if (!this.hasPreviewTarget) return;

    const trimmed = this.value.trim();
    if (!trimmed) {
      this._clearAndSetPreviewMessage("Enter an expression", "text-muted small");
      return;
    }

    this._clearAndSetPreviewMessage("Evaluating...", "text-muted small");

    try {
      const testData = this.getTestData();
      const result = await evaluateJsonataString(trimmed, { ...testData });
      this.renderPreviewResult(true, this.formatPreviewValue(result));
    } catch (error) {
      this.renderPreviewResult(false, error.message || String(error));
    }
  }

  _clearAndSetPreviewMessage(message, className) {
    while (this.previewTarget.firstChild) {
      this.previewTarget.removeChild(this.previewTarget.firstChild);
    }
    const span = document.createElement("span");
    span.className = className;
    span.textContent = message;
    this.previewTarget.appendChild(span);
  }

  renderPreviewResult(isSuccess, message) {
    if (!this.hasPreviewTarget) return;

    // Clear safely
    while (this.previewTarget.firstChild) {
      this.previewTarget.removeChild(this.previewTarget.firstChild);
    }

    const wrapper = document.createElement("span");
    wrapper.className = isSuccess ? "expression-preview-success" : "expression-preview-error";

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

  formatPreviewValue(value) {
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    if (typeof value === "object") {
      const json = JSON.stringify(value);
      return json.length > 50 ? json.slice(0, 50) + "..." : json;
    }
    return String(value);
  }

  // =========================================================================
  // Autocomplete logic
  // =========================================================================
  parsePartialExpression(text) {
    const trimmed = text.trim();
    if (!trimmed) {
      return { path: [], partial: "" };
    }

    if (trimmed.endsWith(".")) {
      return { path: parsePath(trimmed.slice(0, -1)), partial: "" };
    }

    const segments = parsePath(trimmed);
    if (segments.length === 0) {
      return { path: [], partial: "" };
    }

    const partial = segments[segments.length - 1];
    const path = segments.slice(0, -1);

    return { path, partial };
  }

  getTopLevelCompletions(filter) {
    const completions = [];
    const scopeVariables = this.getScopeVariables();
    const testData = this.getTestData();

    // Add scope variables (loop items, indices)
    for (const scopeVar of scopeVariables) {
      if (!filter || scopeVar.name.toLowerCase().startsWith(filter.toLowerCase())) {
        completions.push({
          label: scopeVar.name,
          type: "variable",
          detail: scopeVar.type === "loop-index" ? "loop index" : `loop item from ${scopeVar.arrayPath}`,
          value: scopeVar.name,
          priority: 10,
        });
      }
    }

    // Add paths from test data
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

  getPathCompletions(path, filter) {
    const testData = this.getTestData();
    const scopeVariables = this.getScopeVariables();
    const type = resolvePathType(path, testData, scopeVariables);

    if (type.kind === "unknown" && !filter) {
      return [];
    }

    const suggestions = [];

    // Add type-specific methods
    const methods = getMethodsForType(type);
    for (const method of methods) {
      if (!filter || method.label.toLowerCase().startsWith(filter.toLowerCase())) {
        const applyValue = method.type === "method" ? `${method.label}()` : method.label;
        suggestions.push({
          label: method.label,
          type: method.type,
          detail: method.detail,
          value: applyValue,
          priority: method.type === "property" ? 8 : 5,
        });
      }
    }

    // Add object properties
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

    // Add array index access
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

  // =========================================================================
  // Helpers
  // =========================================================================
  getTextBeforeCursor() {
    const pos = this.inputTarget.selectionStart;
    return this.inputTarget.value.slice(0, pos);
  }

  getTextAfterCursor() {
    const pos = this.inputTarget.selectionEnd;
    return this.inputTarget.value.slice(pos);
  }

  getTestData() {
    return window.__editorTestData || {};
  }

  getScopeVariables() {
    if (!window.__editor) return [];

    const editor = window.__editor;
    const blockId = this.blockIdValue;
    const scopes = [];

    const findParentLoops = (blocks, targetId, depth = 0) => {
      if (depth > 10) return false;

      for (const block of blocks) {
        if (block.id === targetId) {
          return true;
        }

        let foundInChildren = false;

        if (block.children && block.children.length > 0) {
          foundInChildren = findParentLoops(block.children, targetId, depth + 1);
        }

        if (!foundInChildren && block.columns) {
          for (const col of block.columns) {
            if (col.children && col.children.length > 0) {
              foundInChildren = findParentLoops(col.children, targetId, depth + 1);
              if (foundInChildren) break;
            }
          }
        }

        if (!foundInChildren && block.rows) {
          for (const row of block.rows) {
            for (const cell of row.cells) {
              if (cell.children && cell.children.length > 0) {
                foundInChildren = findParentLoops(cell.children, targetId, depth + 1);
                if (foundInChildren) break;
              }
            }
            if (foundInChildren) break;
          }
        }

        if (foundInChildren && block.type === "loop") {
          scopes.push({
            name: block.itemAlias || "item",
            type: "loop-item",
            arrayPath: block.expression?.raw || "",
          });
          if (block.indexAlias) {
            scopes.push({
              name: block.indexAlias,
              type: "loop-index",
              arrayPath: block.expression?.raw || "",
            });
          }
        }

        if (foundInChildren) return true;
      }

      return false;
    };

    const state = editor.getState();
    findParentLoops(state.template.blocks, blockId);

    return scopes;
  }

  debouncedNotifyChange() {
    if (this._notifyTimer) {
      clearTimeout(this._notifyTimer);
    }
    this._notifyTimer = setTimeout(() => {
      this._notifyTimer = null;
      this.notifyChange();
    }, 300);
  }

  notifyChange() {
    if (!window.__editor) return;

    const blockId = this.blockIdValue;
    const blockType = this.blockTypeValue;
    const updateKey = blockType === "loop" ? "expression" : "condition";

    const block = window.__editor.findBlock(blockId);
    if (block) {
      window.__editor.updateBlock(blockId, {
        [updateKey]: { ...(block[updateKey] || {}), raw: this.value },
      });
    }
  }
}
