/**
 * BlockRenderer - Renders blocks to the DOM using Bootstrap 5
 *
 * Uses morphdom for efficient DOM diffing - only updates what changed,
 * preserving focus, cursor position, and input values automatically.
 */

import morphdom from "https://cdn.jsdelivr.net/npm/morphdom@2.7/+esm";
import {
  createElement,
  getBadgeClass,
  getBadgeStyle,
  getBlockIcon,
  logCallback,
} from "./dom-helpers.js";

// morphdom is loaded globally via CDN in editor.html
/* global morphdom */
import { ExpressionEditor } from "../components/ExpressionEditor.js";

export class BlockRenderer {
  /**
   * @param {import('@epistola/headless-editor').TemplateEditor} editor
   * @param {string} containerId
   * @param {boolean} [debugMode]
   */
  constructor(editor, containerId, debugMode = false) {
    this.editor = editor;
    this.debugMode = debugMode;
    const container = document.getElementById(containerId);
    if (!container) {
      throw new Error(`Container element not found: ${containerId}`);
    }
    this.container = container;
    this.expressionEditors = new Map();
  }

  destroy() {
    for (const editor of this.expressionEditors.values()) {
      editor.destroy();
    }
    this.expressionEditors.clear();
  }

  render() {
    const state = this.editor.getState();
    const currentBlockIds = new Set();

    // Build the new DOM tree
    const newContainer = document.createElement("div");

    if (state.template.blocks.length === 0) {
      newContainer.appendChild(
        createElement("div", {
          className: "empty-state text-center text-muted p-4",
          textContent: "No blocks yet. Add one using the toolbar above.",
        }),
      );
    } else {
      for (const block of state.template.blocks) {
        currentBlockIds.add(block.id);
        newContainer.appendChild(this._renderBlock(block));
      }
    }

    // Use morphdom to efficiently patch the DOM
    morphdom(this.container, newContainer, {
      childrenOnly: true,
      onBeforeElUpdated: (fromEl, toEl) => {
        // Don't update focused inputs/textareas - let user keep typing
        if (fromEl === document.activeElement) {
          if (fromEl.tagName === "INPUT" || fromEl.tagName === "TEXTAREA") {
            return false;
          }
        }
        return true;
      },
    });

    this._cleanupDeletedEditors(Array.from(currentBlockIds));
  }

  _cleanupDeletedEditors(currentBlockIds) {
    for (const [blockId, editor] of this.expressionEditors) {
      if (!currentBlockIds.includes(blockId)) {
        editor.destroy();
        this.expressionEditors.delete(blockId);
      }
    }
  }

  renderState() {
    if (!this.debugMode) return;

    const display = document.getElementById("state-display");
    if (!display) return;

    const state = this.editor.getState();
    display.textContent = JSON.stringify(
      {
        selectedBlockId: state.selectedBlockId,
        blockCount: state.template.blocks.length,
        canUndo: this.editor.canUndo(),
        canRedo: this.editor.canRedo(),
        template: state.template,
      },
      null,
      2,
    );
  }

  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlock(block) {
    const state = this.editor.getState();
    const isSelected = state.selectedBlockId === block.id;

    const blockEl = createElement("div", {
      className: `block ${isSelected ? "selected" : ""}`,
      "data-block-id": block.id,
      "data-block-type": block.type,
      onClick: (e) => {
        this.editor.selectBlock(block.id);
        e.stopPropagation();
      },
    });

    blockEl.appendChild(this._renderBlockHeader(block));
    this._renderBlockContent(blockEl, block);

    return blockEl;
  }

  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlockHeader(block) {
    return createElement(
      "div",
      {
        className:
          "block-header d-flex justify-content-between align-items-center",
      },
      [
        createElement("div", { className: "d-flex align-items-center gap-2" }, [
          createElement("i", {
            className: "bi bi-grip-vertical drag-handle text-muted",
          }),
          createElement("i", {
            className: `bi ${getBlockIcon(block.type)} text-muted`,
          }),
          createElement(
            "span",
            {
              className: `badge ${getBadgeClass(block.type)}`,
              style: getBadgeStyle(block.type),
            },
            [block.type],
          ),
        ]),
        createElement(
          "button",
          {
            className: "btn btn-outline-danger btn-sm",
            title: "Delete block",
            onClick: (e) => {
              this.editor.deleteBlock(block.id);
              e.stopPropagation();
            },
          },
          [createElement("i", { className: "bi bi-trash" })],
        ),
      ],
    );
  }

  /** @param {HTMLElement} blockEl */
  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlockContent(blockEl, block) {
    switch (block.type) {
      case "text":
        this._renderTextContent(blockEl, block);
        break;
      case "container":
        this._renderContainerContent(blockEl, block);
        break;
      case "conditional":
        this._renderConditionalContent(blockEl, block);
        break;
      case "loop":
        this._renderLoopContent(blockEl, block);
        break;
      case "columns":
        this._renderColumnsContent(blockEl, block);
        break;
      case "table":
        this._renderTableContent(blockEl, block);
        break;
      case "pagebreak":
        this._renderPageBreakContent(blockEl);
        break;
      case "pageheader":
        this._renderPageHeaderContent(blockEl, block);
        break;
      case "pagefooter":
        this._renderPageFooterContent(blockEl, block);
        break;
    }
  }

  _renderTextContent(blockEl, block) {
    const textarea = createElement("textarea", {
      className: "form-control form-control-sm mt-2",
      placeholder: "Enter text content...",
      rows: "3",
      "data-block-id": block.id,
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        const text = e.target.value;
        const tipTapContent = text
          ? {
              type: "doc",
              content: [
                { type: "paragraph", content: [{ type: "text", text }] },
              ],
            }
          : null;
        this.editor.updateBlock(block.id, { content: tipTapContent });
      },
    });
    textarea.value = this._extractTextFromTipTap(block.content);
    blockEl.appendChild(textarea);
  }

  /**
   * Extract plain text from TipTap JSONContent structure
   * @param {object|null} content - TipTap JSONContent or null
   * @returns {string}
   */
  _extractTextFromTipTap(content) {
    if (!content || typeof content !== "object") return "";

    const extractFromNode = (node) => {
      if (!node) return "";
      if (node.type === "text" && node.text) return node.text;
      if (Array.isArray(node.content)) {
        return node.content.map(extractFromNode).join("");
      }
      return "";
    };

    return extractFromNode(content);
  }

  /**
   * Get scope variables for a block's children context
   * @param {string} blockId - The parent block ID
   * @returns {Array<{name: string, type: string, arrayPath: string}>}
   */
  _getScopeVariables(blockId) {
    const scopes = [];

    const findParentLoops = (blocks, depth = 0) => {
      if (depth > 10) return;

      for (const block of blocks) {
        if (block.type === "loop") {
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

        if (block.children && block.children.length > 0) {
          findParentLoops(block.children, depth + 1);
        }

        if (block.columns) {
          for (const col of block.columns) {
            if (col.children && col.children.length > 0) {
              findParentLoops(col.children, depth + 1);
            }
          }
        }

        if (block.rows) {
          for (const row of block.rows) {
            for (const cell of row.cells) {
              if (cell.children && cell.children.length > 0) {
                findParentLoops(cell.children, depth + 1);
              }
            }
          }
        }
      }
    };

    const state = this.editor.getState();
    findParentLoops(state.template.blocks);

    return scopes;
  }

  _renderContainerContent(blockEl, block) {
    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Drop blocks here",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderConditionalContent(blockEl, block) {
    const editorContainerId = `expr-editor-${block.id}`;
    let editorContainer = blockEl.querySelector(`#${editorContainerId}`);

    if (!editorContainer) {
      editorContainer = createElement("div", {
        id: editorContainerId,
        className: "mb-2",
      });
      blockEl.appendChild(editorContainer);
    }

    let editor = this.expressionEditors.get(block.id);
    if (!editor) {
      editor = new ExpressionEditor({
        testData: this.editor.store.getTestData() || {},
        scopeVariables: this._getScopeVariables(block.id),
        onChange: (value) => {
          this.editor.updateBlock(block.id, {
            condition: { ...block.condition, raw: value },
          });
        },
        onSave: (value) => {
          this.editor.updateBlock(block.id, {
            condition: { ...block.condition, raw: value },
          });
        },
        onCancel: () => {},
      });
      editor.mount(editorContainer);
      this.expressionEditors.set(block.id, editor);
    }

    editor.setTestData(this.editor.store.getTestData() || {});
    editor.setScopeVariables(this._getScopeVariables(block.id));
    editor.setValue(block.condition?.raw || "");
    editor.focus();

    const checkbox = createElement("input", {
      type: "checkbox",
      className: "form-check-input",
      id: `inverse-${block.id}`,
      onClick: (e) => e.stopPropagation(),
      onChange: (e) => {
        this.editor.updateBlock(block.id, { inverse: e.target.checked });
      },
    });
    checkbox.checked = block.inverse || false;

    blockEl.appendChild(
      createElement("div", { className: "form-check mt-1" }, [
        checkbox,
        createElement("label", {
          className: "form-check-label small",
          htmlFor: `inverse-${block.id}`,
          textContent: "Inverse (show when false)",
        }),
      ]),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Content when condition is true",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderLoopContent(blockEl, block) {
    const exprContainerId = `loop-expr-${block.id}`;
    let exprContainer = blockEl.querySelector(`#${exprContainerId}`);

    if (!exprContainer) {
      exprContainer = createElement("div", {
        id: exprContainerId,
        className: "mb-2",
      });
      blockEl.insertBefore(exprContainer, blockEl.querySelector(".row"));
    }

    let exprEditor = this.expressionEditors.get(block.id);
    if (!exprEditor) {
      exprEditor = new ExpressionEditor({
        testData: this.editor.store.getTestData() || {},
        scopeVariables: this._getScopeVariables(block.id),
        onChange: (value) => {
          this.editor.updateBlock(block.id, {
            expression: { ...block.expression, raw: value },
          });
        },
        onSave: (value) => {
          this.editor.updateBlock(block.id, {
            expression: { ...block.expression, raw: value },
          });
        },
        onCancel: () => {},
      });
      exprEditor.mount(exprContainer);
      this.expressionEditors.set(block.id, exprEditor);
    }

    exprEditor.setTestData(this.editor.store.getTestData() || {});
    exprEditor.setScopeVariables(this._getScopeVariables(block.id));
    exprEditor.setValue(block.expression?.raw || "");
    exprEditor.focus();

    const itemAliasInput = createElement("input", {
      type: "text",
      className: "form-control",
      placeholder: "item",
      "data-block-id": `${block.id}-item`,
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { itemAlias: e.target.value });
      },
    });
    itemAliasInput.value = block.itemAlias || "item";

    const indexAliasInput = createElement("input", {
      type: "text",
      className: "form-control",
      placeholder: "index (optional)",
      "data-block-id": `${block.id}-index`,
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, {
          indexAlias: e.target.value || undefined,
        });
      },
    });
    indexAliasInput.value = block.indexAlias || "";

    blockEl.appendChild(
      createElement("div", { className: "row g-2 mt-1" }, [
        createElement("div", { className: "col-6" }, [
          createElement("div", { className: "input-group input-group-sm" }, [
            createElement("span", { className: "input-group-text" }, ["as"]),
            itemAliasInput,
          ]),
        ]),
        createElement("div", { className: "col-6" }, [
          createElement("div", { className: "input-group input-group-sm" }, [
            createElement("span", { className: "input-group-text" }, ["index"]),
            indexAliasInput,
          ]),
        ]),
      ]),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Loop body (repeated for each item)",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderColumnsContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", { className: "d-flex gap-2 mt-2 mb-2" }, [
        createElement(
          "button",
          {
            className: "btn btn-outline-secondary btn-sm",
            title: "Add column",
            onClick: (e) => {
              e.stopPropagation();
              this.editor.addColumn(block.id);
              logCallback(`Added column to ${block.id}`);
            },
          },
          [createElement("i", { className: "bi bi-plus" }), " Add Column"],
        ),
        createElement("span", {
          className: "text-muted small align-self-center",
          textContent: `${block.columns.length}/6 columns`,
        }),
      ]),
    );

    const columnsEl = createElement("div", {
      className: "columns-layout d-flex gap-2",
      "data-parent-id": block.id,
      style: `gap: ${block.gap || 16}px;`,
    });

    for (const column of block.columns) {
      const columnEl = createElement("div", {
        className: "column-wrapper flex-fill sortable-container",
        "data-parent-id": column.id,
        "data-column-id": column.id,
        style: `flex: ${column.size};`,
      });

      columnEl.appendChild(
        createElement(
          "div",
          {
            className: "d-flex justify-content-between align-items-center mb-1",
          },
          [
            createElement("small", { className: "text-muted" }, [
              `Size: ${column.size}`,
            ]),
            createElement(
              "button",
              {
                className: "btn btn-outline-danger btn-sm py-0",
                title: "Remove column",
                onClick: (e) => {
                  e.stopPropagation();
                  this.editor.removeColumn(block.id, column.id);
                  logCallback(`Removed column ${column.id}`);
                },
              },
              [createElement("i", { className: "bi bi-x" })],
            ),
          ],
        ),
      );

      if (column.children.length === 0) {
        columnEl.appendChild(
          createElement("div", {
            className: "empty-state text-muted small text-center",
            style:
              "padding: 1rem; border: 1px dashed #dee2e6; border-radius: 4px;",
            textContent: "Drop content here",
          }),
        );
      } else {
        for (const child of column.children) {
          columnEl.appendChild(this._renderBlock(child));
        }
      }

      columnsEl.appendChild(columnEl);
    }

    blockEl.appendChild(columnsEl);
  }

  _renderTableContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", { className: "d-flex gap-2 mt-2 mb-2" }, [
        createElement(
          "button",
          {
            className: "btn btn-outline-secondary btn-sm",
            title: "Add row",
            onClick: (e) => {
              e.stopPropagation();
              this.editor.addRow(block.id);
              logCallback(`Added row to ${block.id}`);
            },
          },
          [createElement("i", { className: "bi bi-plus" }), " Add Row"],
        ),
        createElement("span", {
          className: "text-muted small align-self-center",
          textContent: `${block.rows.length} rows`,
        }),
      ]),
    );

    const tableEl = createElement("table", {
      className: "table table-bordered table-sm mb-0",
    });

    for (const row of block.rows) {
      const rowEl = createElement("tr", {
        "data-row-id": row.id,
        className: row.isHeader ? "table-light" : "",
      });

      for (const cell of row.cells) {
        const cellTag = row.isHeader ? "th" : "td";
        const cellEl = createElement(cellTag, {
          "data-cell-id": cell.id,
          "data-parent-id": cell.id,
          className: "sortable-container p-1",
          style: "vertical-align: top; min-width: 100px;",
        });

        if (cell.children.length === 0) {
          cellEl.appendChild(
            createElement("div", {
              className: "text-muted small text-center",
              style: "padding: 0.25rem;",
              textContent: "Drop content",
            }),
          );
        } else {
          for (const child of cell.children) {
            cellEl.appendChild(this._renderBlock(child));
          }
        }

        rowEl.appendChild(cellEl);
      }

      rowEl.appendChild(
        createElement(
          row.isHeader ? "th" : "td",
          {
            style: "width: 40px; vertical-align: middle;",
          },
          [
            createElement(
              "button",
              {
                className: "btn btn-outline-danger btn-sm py-0",
                title: "Remove row",
                onClick: (e) => {
                  e.stopPropagation();
                  this.editor.removeRow(block.id, row.id);
                  logCallback(`Removed row ${row.id}`);
                },
              },
              [createElement("i", { className: "bi bi-x" })],
            ),
          ],
        ),
      );

      tableEl.appendChild(rowEl);
    }

    blockEl.appendChild(tableEl);
  }

  _renderPageBreakContent(blockEl) {
    blockEl.appendChild(
      createElement(
        "div",
        {
          className: "page-break-line text-center my-2",
          style: "border-top: 2px dashed #6c757d; position: relative;",
        },
        [
          createElement("span", {
            className: "bg-white px-2 text-muted small",
            style:
              "position: absolute; top: -0.7em; left: 50%; transform: translateX(-50%);",
            textContent: "Page Break",
          }),
        ],
      ),
    );
  }

  _renderPageHeaderContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", {
        className: "alert alert-info py-1 px-2 mb-2 small",
        textContent: "Content appears at the top of every page",
      }),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Add header content",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderPageFooterContent(blockEl, block) {
    blockEl.appendChild(
      createElement("div", {
        className: "alert alert-info py-1 px-2 mb-2 small",
        textContent: "Content appears at the bottom of every page",
      }),
    );

    const childrenEl = createElement("div", {
      className: "block-children sortable-container",
      "data-parent-id": block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement("div", {
          className: "empty-state text-muted small",
          style: "padding: 0.5rem;",
          textContent: "Add footer content",
        }),
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }
}
