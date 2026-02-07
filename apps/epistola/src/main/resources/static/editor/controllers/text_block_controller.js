/**
 * TextBlockController - Stimulus controller for TextBlock with expression chips
 *
 * Handles contenteditable div with inline expression chips like {{customer.name}}.
 * Features:
 * - Detect `{{` to insert expression chip
 * - Click chip to open edit popover
 * - Evaluate chips and display values from test data
 * - Convert between contenteditable HTML and TipTap JSON
 */

import { Controller } from "@hotwired/stimulus";
import { evaluateJsonataString } from "/headless-editor/headless-editor.js";
import { log } from "/editor/utils/editor-logger.js";

export class TextBlockController extends Controller {
  static targets = ["editor", "popover", "popoverInput", "popoverPreview"];
  static values = {
    blockId: String,
    content: String,
  };

  connect() {
    this.activeChip = null;
    log.info("text-block", "connect", { blockId: this.blockIdValue });
    this.renderContent();
    this.evaluateAllChips();

    // Bind document click handler for closing popover
    this._handleDocumentClick = this._handleDocumentClick.bind(this);
    document.addEventListener("click", this._handleDocumentClick);
  }

  disconnect() {
    log.info("text-block", "disconnect", { blockId: this.blockIdValue });
    document.removeEventListener("click", this._handleDocumentClick);
    if (this._popoverKeyHandler && this.hasPopoverInputTarget) {
      this.popoverInputTarget.removeEventListener("keydown", this._popoverKeyHandler);
    }
    this.closePopover();
  }

  // =========================================================================
  // Content rendering
  // =========================================================================
  renderContent() {
    if (!this.hasEditorTarget) return;

    const contentJson = this.contentValue;
    if (!contentJson) {
      this.editorTarget.textContent = "";
      return;
    }

    try {
      const content = JSON.parse(contentJson);
      this.renderTipTapContent(content);
    } catch {
      this.editorTarget.textContent = "";
    }
  }

  renderTipTapContent(content) {
    if (!content || !content.content) {
      this.editorTarget.textContent = "";
      return;
    }

    // Clear existing content
    while (this.editorTarget.firstChild) {
      this.editorTarget.removeChild(this.editorTarget.firstChild);
    }

    // Render each paragraph/node
    for (const node of content.content) {
      if (node.type === "paragraph") {
        const p = document.createElement("div");
        p.className = "text-block-paragraph";

        if (node.content) {
          for (const child of node.content) {
            this.renderNode(p, child);
          }
        }

        this.editorTarget.appendChild(p);
      }
    }
  }

  renderNode(parent, node) {
    if (node.type === "text") {
      const text = node.text || "";
      // Check for expression patterns like {{expr}}
      const parts = this.parseTextWithExpressions(text);

      for (const part of parts) {
        if (part.type === "text") {
          parent.appendChild(document.createTextNode(part.value));
        } else if (part.type === "expression") {
          const chip = this.createExpressionChip(part.value);
          parent.appendChild(chip);
        }
      }
    } else if (node.type === "expression" || node.type === "expressionChip") {
      // TipTap expression node (attrs.expression) or legacy expressionChip
      const chip = this.createExpressionChip(node.attrs?.expression || "");
      parent.appendChild(chip);
    } else if (node.type === "hardBreak") {
      parent.appendChild(document.createElement("br"));
    }
  }

  parseTextWithExpressions(text) {
    const parts = [];
    const regex = /\{\{([^}]+)\}\}/g;
    let lastIndex = 0;
    let match;

    while ((match = regex.exec(text)) !== null) {
      // Add text before the match
      if (match.index > lastIndex) {
        parts.push({ type: "text", value: text.slice(lastIndex, match.index) });
      }
      // Add the expression
      parts.push({ type: "expression", value: match[1].trim() });
      lastIndex = regex.lastIndex;
    }

    // Add remaining text
    if (lastIndex < text.length) {
      parts.push({ type: "text", value: text.slice(lastIndex) });
    }

    return parts;
  }

  createExpressionChip(expression) {
    const chip = document.createElement("span");
    chip.className = "expression-chip";
    chip.contentEditable = "false";
    chip.dataset.expression = expression;
    chip.dataset.action = "click->text-block#handleChipClick";

    const exprSpan = document.createElement("span");
    exprSpan.className = "expression-chip-expr";
    exprSpan.textContent = expression || "...";
    chip.appendChild(exprSpan);

    const valueSpan = document.createElement("span");
    valueSpan.className = "expression-chip-value";
    valueSpan.textContent = "";
    chip.appendChild(valueSpan);

    return chip;
  }

  // =========================================================================
  // Event handlers
  // =========================================================================
  handleInput(event) {
    event.stopPropagation();
    this.saveContent();
  }

  handleKeydown(event) {
    // Detect `{{` pattern to insert expression chip
    if (event.key === "{") {
      const selection = window.getSelection();
      if (!selection.rangeCount) return;

      const range = selection.getRangeAt(0);
      const textNode = range.startContainer;

      if (textNode.nodeType === Node.TEXT_NODE) {
        const textBefore = textNode.textContent.slice(0, range.startOffset);
        if (textBefore.endsWith("{")) {
          event.preventDefault();
          this.insertExpressionChip(range, textNode);
          return;
        }
      }
    }
  }

  handleChipClick(event) {
    event.preventDefault();
    event.stopPropagation();

    const chip = event.currentTarget;
    this.openPopover(chip);
  }

  _handleDocumentClick(event) {
    if (this.hasPopoverTarget && !this.popoverTarget.contains(event.target) && !event.target.closest(".expression-chip")) {
      this.closePopover();
    }
  }

  // =========================================================================
  // Expression chip insertion
  // =========================================================================
  insertExpressionChip(range, textNode) {
    const text = textNode.textContent;
    const offset = range.startOffset;

    // Remove the first `{` that was typed before
    const beforeText = text.slice(0, offset - 1);
    const afterText = text.slice(offset);

    // Create new text node for before text
    const beforeNode = document.createTextNode(beforeText);

    // Create the chip
    const chip = this.createExpressionChip("");

    // Create new text node for after text
    const afterNode = document.createTextNode(afterText);

    // Replace the original text node
    const parent = textNode.parentNode;
    parent.insertBefore(beforeNode, textNode);
    parent.insertBefore(chip, textNode);
    parent.insertBefore(afterNode, textNode);
    parent.removeChild(textNode);

    // Open popover for the new chip
    this.openPopover(chip);
  }

  // =========================================================================
  // Popover management
  // =========================================================================
  openPopover(chip) {
    if (!this.hasPopoverTarget) return;

    this.activeChip = chip;
    const expression = chip.dataset.expression || "";

    log.info("text-block", "openPopover", { expression, blockId: this.blockIdValue });

    // Position popover near the chip
    const chipRect = chip.getBoundingClientRect();
    const containerRect = this.element.getBoundingClientRect();

    this.popoverTarget.style.display = "block";
    this.popoverTarget.style.top = `${chipRect.bottom - containerRect.top + 4}px`;
    this.popoverTarget.style.left = `${chipRect.left - containerRect.left}px`;

    // Set input value and bind key handler directly
    if (this.hasPopoverInputTarget) {
      this.popoverInputTarget.value = expression;

      // Remove old listener before adding new one
      if (this._popoverKeyHandler) {
        this.popoverInputTarget.removeEventListener("keydown", this._popoverKeyHandler);
      }
      this._popoverKeyHandler = (e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          e.stopPropagation();
          log.info("text-block", "popover Enter key pressed");
          this.savePopover();
        } else if (e.key === "Escape") {
          e.preventDefault();
          e.stopPropagation();
          this.cancelPopover();
        }
      };
      this.popoverInputTarget.addEventListener("keydown", this._popoverKeyHandler);

      this.popoverInputTarget.focus();
      this.popoverInputTarget.select();
    }

    // Update preview
    this.updatePopoverPreview();
  }

  closePopover() {
    if (this.hasPopoverTarget) {
      this.popoverTarget.style.display = "none";
    }
    this.activeChip = null;
  }

  handlePopoverInput() {
    this.updatePopoverPreview();
  }

  handlePopoverKeydown(event) {
    if (event.key === "Enter") {
      event.preventDefault();
      this.savePopover();
    } else if (event.key === "Escape") {
      event.preventDefault();
      this.closePopover();
    }
  }

  async updatePopoverPreview() {
    if (!this.hasPopoverPreviewTarget || !this.hasPopoverInputTarget) return;

    const expression = this.popoverInputTarget.value.trim();
    if (!expression) {
      this.popoverPreviewTarget.textContent = "";
      this.popoverPreviewTarget.className = "expression-popover-preview";
      return;
    }

    try {
      const testData = window.__editorTestData || {};
      const result = await evaluateJsonataString(expression, { ...testData });
      this.popoverPreviewTarget.textContent = this.formatValue(result);
      this.popoverPreviewTarget.className = "expression-popover-preview success";
    } catch (error) {
      this.popoverPreviewTarget.textContent = error.message || String(error);
      this.popoverPreviewTarget.className = "expression-popover-preview error";
    }
  }

  savePopover() {
    if (!this.activeChip || !this.hasPopoverInputTarget) {
      log.warn("text-block", "savePopover: no active chip or no input target", {
        hasChip: !!this.activeChip,
        hasInput: this.hasPopoverInputTarget,
      });
      return;
    }

    const expression = this.popoverInputTarget.value.trim();
    log.info("text-block", "savePopover", { expression, blockId: this.blockIdValue });

    if (!expression) {
      // Remove the chip if expression is empty
      this.activeChip.remove();
    } else {
      // Update chip expression
      this.activeChip.dataset.expression = expression;
      const exprSpan = this.activeChip.querySelector(".expression-chip-expr");
      if (exprSpan) {
        exprSpan.textContent = expression;
      }
      // Evaluate and update value
      this.evaluateChip(this.activeChip);
    }

    this.closePopover();
    this.saveContent();
  }

  cancelPopover() {
    // If chip has no expression, remove it
    if (this.activeChip && !this.activeChip.dataset.expression) {
      this.activeChip.remove();
      this.saveContent();
    }
    this.closePopover();
  }

  // =========================================================================
  // Chip evaluation
  // =========================================================================
  async evaluateAllChips() {
    if (!this.hasEditorTarget) return;

    const chips = this.editorTarget.querySelectorAll(".expression-chip");
    for (const chip of chips) {
      await this.evaluateChip(chip);
    }
  }

  async evaluateChip(chip) {
    const expression = chip.dataset.expression;
    const valueSpan = chip.querySelector(".expression-chip-value");

    if (!expression || !valueSpan) return;

    try {
      const testData = window.__editorTestData || {};
      const result = await evaluateJsonataString(expression, { ...testData });
      const formatted = this.formatValue(result);
      valueSpan.textContent = formatted ? ` = ${formatted}` : "";
      chip.classList.remove("error");
    } catch {
      valueSpan.textContent = " = ?";
      chip.classList.add("error");
    }
  }

  formatValue(value) {
    if (value === undefined) return "undefined";
    if (value === null) return "null";
    if (typeof value === "string") {
      return value.length > 20 ? value.slice(0, 20) + "..." : value;
    }
    if (typeof value === "object") {
      const json = JSON.stringify(value);
      return json.length > 20 ? json.slice(0, 20) + "..." : json;
    }
    return String(value);
  }

  // =========================================================================
  // Content persistence
  // =========================================================================
  saveContent() {
    if (!this.hasEditorTarget || !window.__editor) return;

    const tipTapContent = this.convertToTipTap();
    const blockId = this.blockIdValue;

    window.__editor.updateBlock(blockId, { content: tipTapContent });
  }

  convertToTipTap() {
    const content = [];
    let looseContent = [];

    const flushLoose = () => {
      if (looseContent.length > 0) {
        content.push({ type: "paragraph", content: looseContent });
        looseContent = [];
      }
    };

    for (const child of this.editorTarget.childNodes) {
      if (child.nodeType === Node.ELEMENT_NODE && child.classList.contains("text-block-paragraph")) {
        flushLoose();
        const paragraphContent = this.convertParagraphToTipTap(child);
        content.push({
          type: "paragraph",
          content: paragraphContent,
        });
      } else if (child.nodeType === Node.TEXT_NODE) {
        // Collect loose text (include even whitespace-only if it's between chips)
        const text = child.textContent;
        if (text) {
          looseContent.push({ type: "text", text });
        }
      } else if (child.nodeType === Node.ELEMENT_NODE && child.classList.contains("expression-chip")) {
        // Collect loose expression chips
        const expression = child.dataset.expression || "";
        log.debug("text-block", "convertToTipTap: loose chip found", { expression });
        looseContent.push({
          type: "expression",
          attrs: { expression, isNew: false },
        });
      }
    }

    flushLoose();

    // Handle empty editor
    if (content.length === 0) {
      log.debug("text-block", "convertToTipTap: empty content");
      return null;
    }

    log.debug("text-block", "convertToTipTap: result", { paragraphs: content.length });
    return {
      type: "doc",
      content,
    };
  }

  convertParagraphToTipTap(paragraph) {
    const content = [];

    for (const node of paragraph.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.textContent;
        if (text) {
          content.push({ type: "text", text });
        }
      } else if (node.nodeType === Node.ELEMENT_NODE && node.classList.contains("expression-chip")) {
        const expression = node.dataset.expression || "";
        content.push({
          type: "expression",
          attrs: { expression, isNew: false },
        });
      } else if (node.nodeType === Node.ELEMENT_NODE && node.tagName === "BR") {
        content.push({ type: "hardBreak" });
      }
    }

    return content;
  }

  // =========================================================================
  // Content value change callback
  // =========================================================================
  contentValueChanged() {
    // Don't re-render while user is actively editing - prevents cursor jump
    if (this.hasEditorTarget && this.editorTarget.contains(document.activeElement)) {
      return;
    }
    this.renderContent();
    this.evaluateAllChips();
  }
}
