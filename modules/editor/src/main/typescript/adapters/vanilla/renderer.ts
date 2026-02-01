/**
 * BlockRenderer - Renders blocks to the DOM using Bootstrap 5
 *
 * Handles rendering all 9 block types with their specific UI structures.
 */

import type { TemplateEditor } from '../../core/editor.js';
import type {
  Block,
  TextBlock,
  ContainerBlock,
  ConditionalBlock,
  LoopBlock,
  ColumnsBlock,
  TableBlock,
  PageBreakBlock,
  PageHeaderBlock,
  PageFooterBlock,
} from '../../core/types.js';
import { createElement, clearElement, getBadgeClass, getBadgeStyle, getBlockIcon, logCallback } from './dom-helpers.js';

/**
 * BlockRenderer - Handles rendering blocks to the DOM
 */
export class BlockRenderer {
  private editor: TemplateEditor;
  private container: HTMLElement;
  private debugMode: boolean;

  constructor(editor: TemplateEditor, containerId: string, debugMode = false) {
    this.editor = editor;
    this.debugMode = debugMode;
    const container = document.getElementById(containerId);
    if (!container) {
      throw new Error(`Container element not found: ${containerId}`);
    }
    this.container = container;
  }

  /**
   * Renders all blocks to the DOM
   */
  render(): void {
    clearElement(this.container);

    const state = this.editor.getState();

    if (state.template.blocks.length === 0) {
      this.container.appendChild(
        createElement('div', {
          className: 'empty-state text-center text-muted p-4',
          textContent: 'No blocks yet. Add one using the toolbar above.',
        })
      );
      return;
    }

    for (const block of state.template.blocks) {
      this.container.appendChild(this.renderBlock(block));
    }
  }

  /**
   * Renders the state display panel (for debugging)
   */
  renderState(): void {
    if (!this.debugMode) return;

    const display = document.getElementById('state-display');
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
      2
    );
  }

  /**
   * Renders a single block recursively
   */
  renderBlock(block: Block): HTMLElement {
    const state = this.editor.getState();
    const isSelected = state.selectedBlockId === block.id;

    const blockEl = createElement('div', {
      className: `block ${isSelected ? 'selected' : ''}`,
      'data-block-id': block.id,
      'data-block-type': block.type,
      onClick: (e: Event) => {
        this.editor.selectBlock(block.id);
        e.stopPropagation();
      },
    });

    // Header with drag handle and controls
    const header = this.renderBlockHeader(block);
    blockEl.appendChild(header);

    // Render type-specific content
    this.renderBlockContent(blockEl, block);

    return blockEl;
  }

  /**
   * Renders block header with drag handle, type badge, and delete button
   */
  private renderBlockHeader(block: Block): HTMLElement {
    return createElement('div', { className: 'block-header d-flex justify-content-between align-items-center' }, [
      createElement('div', { className: 'd-flex align-items-center gap-2' }, [
        createElement('i', { className: 'bi bi-grip-vertical drag-handle text-muted' }),
        createElement('i', { className: `bi ${getBlockIcon(block.type)} text-muted` }),
        createElement(
          'span',
          {
            className: `badge ${getBadgeClass(block.type)}`,
            style: getBadgeStyle(block.type),
          },
          [block.type]
        ),
      ]),
      createElement('button', {
        className: 'btn btn-outline-danger btn-sm',
        title: 'Delete block',
        onClick: (e: Event) => {
          this.editor.deleteBlock(block.id);
          e.stopPropagation();
        },
      }, [createElement('i', { className: 'bi bi-trash' })]),
    ]);
  }

  /**
   * Renders block-specific content based on type
   */
  private renderBlockContent(blockEl: HTMLElement, block: Block): void {
    switch (block.type) {
      case 'text':
        this.renderTextContent(blockEl, block);
        break;
      case 'container':
        this.renderContainerContent(blockEl, block);
        break;
      case 'conditional':
        this.renderConditionalContent(blockEl, block);
        break;
      case 'loop':
        this.renderLoopContent(blockEl, block);
        break;
      case 'columns':
        this.renderColumnsContent(blockEl, block);
        break;
      case 'table':
        this.renderTableContent(blockEl, block);
        break;
      case 'pagebreak':
        this.renderPageBreakContent(blockEl, block);
        break;
      case 'pageheader':
        this.renderPageHeaderContent(blockEl, block);
        break;
      case 'pagefooter':
        this.renderPageFooterContent(blockEl, block);
        break;
    }
  }

  /**
   * Renders text block content (textarea)
   */
  private renderTextContent(blockEl: HTMLElement, block: TextBlock): void {
    const textarea = createElement('textarea', {
      className: 'form-control form-control-sm mt-2',
      placeholder: 'Enter text content...',
      rows: '3',
      onClick: (e: Event) => e.stopPropagation(),
      onInput: (e: Event) => {
        const target = e.target as HTMLTextAreaElement;
        this.editor.updateBlock(block.id, { content: target.value });
      },
    }) as HTMLTextAreaElement;
    textarea.value = block.content || '';
    blockEl.appendChild(textarea);
  }

  /**
   * Renders container block children
   */
  private renderContainerContent(blockEl: HTMLElement, block: ContainerBlock): void {
    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state text-muted small',
          style: 'padding: 0.5rem;',
          textContent: 'Drop blocks here',
        })
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  /**
   * Renders conditional block with expression input
   */
  private renderConditionalContent(blockEl: HTMLElement, block: ConditionalBlock): void {
    // Expression input
    const exprGroup = createElement('div', { className: 'input-group input-group-sm mt-2' }, [
      createElement('span', { className: 'input-group-text' }, ['Condition']),
      (() => {
        const input = createElement('input', {
          type: 'text',
          className: 'form-control',
          placeholder: 'e.g., customer.active',
          onClick: (e: Event) => e.stopPropagation(),
          onInput: (e: Event) => {
            const target = e.target as HTMLInputElement;
            this.editor.updateBlock(block.id, {
              condition: { ...block.condition, raw: target.value },
            });
          },
        }) as HTMLInputElement;
        input.value = block.condition?.raw || '';
        return input;
      })(),
    ]);
    blockEl.appendChild(exprGroup);

    // Inverse toggle
    const inverseToggle = createElement('div', { className: 'form-check mt-1' }, [
      (() => {
        const checkbox = createElement('input', {
          type: 'checkbox',
          className: 'form-check-input',
          id: `inverse-${block.id}`,
          onClick: (e: Event) => e.stopPropagation(),
          onChange: (e: Event) => {
            const target = e.target as HTMLInputElement;
            this.editor.updateBlock(block.id, { inverse: target.checked });
          },
        }) as HTMLInputElement;
        checkbox.checked = block.inverse || false;
        return checkbox;
      })(),
      createElement('label', {
        className: 'form-check-label small',
        htmlFor: `inverse-${block.id}`,
        textContent: 'Inverse (show when false)',
      }),
    ]);
    blockEl.appendChild(inverseToggle);

    // Children
    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state text-muted small',
          style: 'padding: 0.5rem;',
          textContent: 'Content when condition is true',
        })
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  /**
   * Renders loop block with expression and alias inputs
   */
  private renderLoopContent(blockEl: HTMLElement, block: LoopBlock): void {
    // Expression input
    const exprGroup = createElement('div', { className: 'input-group input-group-sm mt-2' }, [
      createElement('span', { className: 'input-group-text' }, ['Array']),
      (() => {
        const input = createElement('input', {
          type: 'text',
          className: 'form-control',
          placeholder: 'e.g., items',
          onClick: (e: Event) => e.stopPropagation(),
          onInput: (e: Event) => {
            const target = e.target as HTMLInputElement;
            this.editor.updateBlock(block.id, {
              expression: { ...block.expression, raw: target.value },
            });
          },
        }) as HTMLInputElement;
        input.value = block.expression?.raw || '';
        return input;
      })(),
    ]);
    blockEl.appendChild(exprGroup);

    // Alias inputs
    const aliasGroup = createElement('div', { className: 'row g-2 mt-1' }, [
      createElement('div', { className: 'col-6' }, [
        createElement('div', { className: 'input-group input-group-sm' }, [
          createElement('span', { className: 'input-group-text' }, ['as']),
          (() => {
            const input = createElement('input', {
              type: 'text',
              className: 'form-control',
              placeholder: 'item',
              onClick: (e: Event) => e.stopPropagation(),
              onInput: (e: Event) => {
                const target = e.target as HTMLInputElement;
                this.editor.updateBlock(block.id, { itemAlias: target.value });
              },
            }) as HTMLInputElement;
            input.value = block.itemAlias || 'item';
            return input;
          })(),
        ]),
      ]),
      createElement('div', { className: 'col-6' }, [
        createElement('div', { className: 'input-group input-group-sm' }, [
          createElement('span', { className: 'input-group-text' }, ['index']),
          (() => {
            const input = createElement('input', {
              type: 'text',
              className: 'form-control',
              placeholder: 'index (optional)',
              onClick: (e: Event) => e.stopPropagation(),
              onInput: (e: Event) => {
                const target = e.target as HTMLInputElement;
                this.editor.updateBlock(block.id, {
                  indexAlias: target.value || undefined,
                });
              },
            }) as HTMLInputElement;
            input.value = block.indexAlias || '';
            return input;
          })(),
        ]),
      ]),
    ]);
    blockEl.appendChild(aliasGroup);

    // Children
    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state text-muted small',
          style: 'padding: 0.5rem;',
          textContent: 'Loop body (repeated for each item)',
        })
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  /**
   * Renders columns block layout
   */
  private renderColumnsContent(blockEl: HTMLElement, block: ColumnsBlock): void {
    // Column controls
    const controls = createElement('div', { className: 'd-flex gap-2 mt-2 mb-2' }, [
      createElement('button', {
        className: 'btn btn-outline-secondary btn-sm',
        title: 'Add column',
        onClick: (e: Event) => {
          e.stopPropagation();
          this.editor.addColumn(block.id);
          logCallback(`Added column to ${block.id}`);
        },
      }, [
        createElement('i', { className: 'bi bi-plus' }),
        ' Add Column',
      ]),
      createElement('span', {
        className: 'text-muted small align-self-center',
        textContent: `${block.columns.length}/6 columns`,
      }),
    ]);
    blockEl.appendChild(controls);

    // Columns layout
    const columnsEl = createElement('div', {
      className: 'columns-layout d-flex gap-2',
      'data-parent-id': block.id,
      style: `gap: ${block.gap || 16}px;`,
    });

    for (const column of block.columns) {
      const columnEl = createElement('div', {
        className: 'column-wrapper flex-fill sortable-container',
        'data-parent-id': column.id,
        'data-column-id': column.id,
        style: `flex: ${column.size};`,
      });

      // Column header with size and remove button
      const colHeader = createElement('div', {
        className: 'd-flex justify-content-between align-items-center mb-1',
      }, [
        createElement('small', { className: 'text-muted' }, [`Size: ${column.size}`]),
        createElement('button', {
          className: 'btn btn-outline-danger btn-sm py-0',
          title: 'Remove column',
          onClick: (e: Event) => {
            e.stopPropagation();
            this.editor.removeColumn(block.id, column.id);
            logCallback(`Removed column ${column.id}`);
          },
        }, [createElement('i', { className: 'bi bi-x' })]),
      ]);
      columnEl.appendChild(colHeader);

      // Column content
      if (column.children.length === 0) {
        columnEl.appendChild(
          createElement('div', {
            className: 'empty-state text-muted small text-center',
            style: 'padding: 1rem; border: 1px dashed #dee2e6; border-radius: 4px;',
            textContent: 'Drop content here',
          })
        );
      } else {
        for (const child of column.children) {
          columnEl.appendChild(this.renderBlock(child));
        }
      }

      columnsEl.appendChild(columnEl);
    }

    blockEl.appendChild(columnsEl);
  }

  /**
   * Renders table block with rows and cells
   */
  private renderTableContent(blockEl: HTMLElement, block: TableBlock): void {
    // Table controls
    const controls = createElement('div', { className: 'd-flex gap-2 mt-2 mb-2' }, [
      createElement('button', {
        className: 'btn btn-outline-secondary btn-sm',
        title: 'Add row',
        onClick: (e: Event) => {
          e.stopPropagation();
          this.editor.addRow(block.id);
          logCallback(`Added row to ${block.id}`);
        },
      }, [
        createElement('i', { className: 'bi bi-plus' }),
        ' Add Row',
      ]),
      createElement('span', {
        className: 'text-muted small align-self-center',
        textContent: `${block.rows.length} rows`,
      }),
    ]);
    blockEl.appendChild(controls);

    // Table
    const tableEl = createElement('table', {
      className: 'table table-bordered table-sm mb-0',
    });

    for (const row of block.rows) {
      const rowEl = createElement('tr', {
        'data-row-id': row.id,
        className: row.isHeader ? 'table-light' : '',
      });

      for (const cell of row.cells) {
        const CellTag = row.isHeader ? 'th' : 'td';
        const cellEl = createElement(CellTag, {
          'data-cell-id': cell.id,
          'data-parent-id': cell.id,
          className: 'sortable-container p-1',
          style: 'vertical-align: top; min-width: 100px;',
        });

        if (cell.children.length === 0) {
          cellEl.appendChild(
            createElement('div', {
              className: 'text-muted small text-center',
              style: 'padding: 0.25rem;',
              textContent: 'Drop content',
            })
          );
        } else {
          for (const child of cell.children) {
            cellEl.appendChild(this.renderBlock(child));
          }
        }

        rowEl.appendChild(cellEl);
      }

      // Row remove button
      const actionCell = createElement(row.isHeader ? 'th' : 'td', {
        style: 'width: 40px; vertical-align: middle;',
      }, [
        createElement('button', {
          className: 'btn btn-outline-danger btn-sm py-0',
          title: 'Remove row',
          onClick: (e: Event) => {
            e.stopPropagation();
            this.editor.removeRow(block.id, row.id);
            logCallback(`Removed row ${row.id}`);
          },
        }, [createElement('i', { className: 'bi bi-x' })]),
      ]);
      rowEl.appendChild(actionCell);

      tableEl.appendChild(rowEl);
    }

    blockEl.appendChild(tableEl);
  }

  /**
   * Renders page break block
   */
  private renderPageBreakContent(blockEl: HTMLElement, _block: PageBreakBlock): void {
    const breakLine = createElement('div', {
      className: 'page-break-line text-center my-2',
      style: 'border-top: 2px dashed #6c757d; position: relative;',
    }, [
      createElement('span', {
        className: 'bg-white px-2 text-muted small',
        style: 'position: absolute; top: -0.7em; left: 50%; transform: translateX(-50%);',
        textContent: 'Page Break',
      }),
    ]);
    blockEl.appendChild(breakLine);
  }

  /**
   * Renders page header block
   */
  private renderPageHeaderContent(blockEl: HTMLElement, block: PageHeaderBlock): void {
    const headerNote = createElement('div', {
      className: 'alert alert-info py-1 px-2 mb-2 small',
      textContent: 'Content appears at the top of every page',
    });
    blockEl.appendChild(headerNote);

    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state text-muted small',
          style: 'padding: 0.5rem;',
          textContent: 'Add header content',
        })
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  /**
   * Renders page footer block
   */
  private renderPageFooterContent(blockEl: HTMLElement, block: PageFooterBlock): void {
    const footerNote = createElement('div', {
      className: 'alert alert-info py-1 px-2 mb-2 small',
      textContent: 'Content appears at the bottom of every page',
    });
    blockEl.appendChild(footerNote);

    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state text-muted small',
          style: 'padding: 0.5rem;',
          textContent: 'Add footer content',
        })
      );
    } else {
      for (const child of block.children) {
        childrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }
}
