/**
 * BlockRenderer - Renders blocks to the DOM using Bootstrap 5
 *
 * Handles rendering all 9 block types with their specific UI structures.
 */

import { createElement, clearElement, getBadgeClass, getBadgeStyle, getBlockIcon, logCallback } from './dom-helpers.js';

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
  }

  render() {
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
      this.container.appendChild(this._renderBlock(block));
    }
  }

  renderState() {
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

  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlock(block) {
    const state = this.editor.getState();
    const isSelected = state.selectedBlockId === block.id;

    const blockEl = createElement('div', {
      className: `block ${isSelected ? 'selected' : ''}`,
      'data-block-id': block.id,
      'data-block-type': block.type,
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
        onClick: (e) => {
          this.editor.deleteBlock(block.id);
          e.stopPropagation();
        },
      }, [createElement('i', { className: 'bi bi-trash' })]),
    ]);
  }

  /** @param {HTMLElement} blockEl */
  /** @param {import('@epistola/headless-editor').Block} block */
  _renderBlockContent(blockEl, block) {
    switch (block.type) {
      case 'text':        this._renderTextContent(blockEl, block); break;
      case 'container':   this._renderContainerContent(blockEl, block); break;
      case 'conditional': this._renderConditionalContent(blockEl, block); break;
      case 'loop':        this._renderLoopContent(blockEl, block); break;
      case 'columns':     this._renderColumnsContent(blockEl, block); break;
      case 'table':       this._renderTableContent(blockEl, block); break;
      case 'pagebreak':   this._renderPageBreakContent(blockEl); break;
      case 'pageheader':  this._renderPageHeaderContent(blockEl, block); break;
      case 'pagefooter':  this._renderPageFooterContent(blockEl, block); break;
    }
  }

  _renderTextContent(blockEl, block) {
    const textarea = createElement('textarea', {
      className: 'form-control form-control-sm mt-2',
      placeholder: 'Enter text content...',
      rows: '3',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { content: e.target.value });
      },
    });
    textarea.value = block.content || '';
    blockEl.appendChild(textarea);
  }

  _renderContainerContent(blockEl, block) {
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
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderConditionalContent(blockEl, block) {
    const conditionInput = createElement('input', {
      type: 'text',
      className: 'form-control',
      placeholder: 'e.g., customer.active',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, {
          condition: { ...block.condition, raw: e.target.value },
        });
      },
    });
    conditionInput.value = block.condition?.raw || '';

    blockEl.appendChild(createElement('div', { className: 'input-group input-group-sm mt-2' }, [
      createElement('span', { className: 'input-group-text' }, ['Condition']),
      conditionInput,
    ]));

    const checkbox = createElement('input', {
      type: 'checkbox',
      className: 'form-check-input',
      id: `inverse-${block.id}`,
      onClick: (e) => e.stopPropagation(),
      onChange: (e) => {
        this.editor.updateBlock(block.id, { inverse: e.target.checked });
      },
    });
    checkbox.checked = block.inverse || false;

    blockEl.appendChild(createElement('div', { className: 'form-check mt-1' }, [
      checkbox,
      createElement('label', {
        className: 'form-check-label small',
        htmlFor: `inverse-${block.id}`,
        textContent: 'Inverse (show when false)',
      }),
    ]));

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
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderLoopContent(blockEl, block) {
    const exprInput = createElement('input', {
      type: 'text',
      className: 'form-control',
      placeholder: 'e.g., items',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, {
          expression: { ...block.expression, raw: e.target.value },
        });
      },
    });
    exprInput.value = block.expression?.raw || '';

    blockEl.appendChild(createElement('div', { className: 'input-group input-group-sm mt-2' }, [
      createElement('span', { className: 'input-group-text' }, ['Array']),
      exprInput,
    ]));

    const itemAliasInput = createElement('input', {
      type: 'text',
      className: 'form-control',
      placeholder: 'item',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { itemAlias: e.target.value });
      },
    });
    itemAliasInput.value = block.itemAlias || 'item';

    const indexAliasInput = createElement('input', {
      type: 'text',
      className: 'form-control',
      placeholder: 'index (optional)',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { indexAlias: e.target.value || undefined });
      },
    });
    indexAliasInput.value = block.indexAlias || '';

    blockEl.appendChild(createElement('div', { className: 'row g-2 mt-1' }, [
      createElement('div', { className: 'col-6' }, [
        createElement('div', { className: 'input-group input-group-sm' }, [
          createElement('span', { className: 'input-group-text' }, ['as']),
          itemAliasInput,
        ]),
      ]),
      createElement('div', { className: 'col-6' }, [
        createElement('div', { className: 'input-group input-group-sm' }, [
          createElement('span', { className: 'input-group-text' }, ['index']),
          indexAliasInput,
        ]),
      ]),
    ]));

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
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderColumnsContent(blockEl, block) {
    blockEl.appendChild(createElement('div', { className: 'd-flex gap-2 mt-2 mb-2' }, [
      createElement('button', {
        className: 'btn btn-outline-secondary btn-sm',
        title: 'Add column',
        onClick: (e) => {
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
    ]));

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

      columnEl.appendChild(createElement('div', {
        className: 'd-flex justify-content-between align-items-center mb-1',
      }, [
        createElement('small', { className: 'text-muted' }, [`Size: ${column.size}`]),
        createElement('button', {
          className: 'btn btn-outline-danger btn-sm py-0',
          title: 'Remove column',
          onClick: (e) => {
            e.stopPropagation();
            this.editor.removeColumn(block.id, column.id);
            logCallback(`Removed column ${column.id}`);
          },
        }, [createElement('i', { className: 'bi bi-x' })]),
      ]));

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
          columnEl.appendChild(this._renderBlock(child));
        }
      }

      columnsEl.appendChild(columnEl);
    }

    blockEl.appendChild(columnsEl);
  }

  _renderTableContent(blockEl, block) {
    blockEl.appendChild(createElement('div', { className: 'd-flex gap-2 mt-2 mb-2' }, [
      createElement('button', {
        className: 'btn btn-outline-secondary btn-sm',
        title: 'Add row',
        onClick: (e) => {
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
    ]));

    const tableEl = createElement('table', { className: 'table table-bordered table-sm mb-0' });

    for (const row of block.rows) {
      const rowEl = createElement('tr', {
        'data-row-id': row.id,
        className: row.isHeader ? 'table-light' : '',
      });

      for (const cell of row.cells) {
        const cellTag = row.isHeader ? 'th' : 'td';
        const cellEl = createElement(cellTag, {
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
            cellEl.appendChild(this._renderBlock(child));
          }
        }

        rowEl.appendChild(cellEl);
      }

      rowEl.appendChild(createElement(row.isHeader ? 'th' : 'td', {
        style: 'width: 40px; vertical-align: middle;',
      }, [
        createElement('button', {
          className: 'btn btn-outline-danger btn-sm py-0',
          title: 'Remove row',
          onClick: (e) => {
            e.stopPropagation();
            this.editor.removeRow(block.id, row.id);
            logCallback(`Removed row ${row.id}`);
          },
        }, [createElement('i', { className: 'bi bi-x' })]),
      ]));

      tableEl.appendChild(rowEl);
    }

    blockEl.appendChild(tableEl);
  }

  _renderPageBreakContent(blockEl) {
    blockEl.appendChild(createElement('div', {
      className: 'page-break-line text-center my-2',
      style: 'border-top: 2px dashed #6c757d; position: relative;',
    }, [
      createElement('span', {
        className: 'bg-white px-2 text-muted small',
        style: 'position: absolute; top: -0.7em; left: 50%; transform: translateX(-50%);',
        textContent: 'Page Break',
      }),
    ]));
  }

  _renderPageHeaderContent(blockEl, block) {
    blockEl.appendChild(createElement('div', {
      className: 'alert alert-info py-1 px-2 mb-2 small',
      textContent: 'Content appears at the top of every page',
    }));

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
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }

  _renderPageFooterContent(blockEl, block) {
    blockEl.appendChild(createElement('div', {
      className: 'alert alert-info py-1 px-2 mb-2 small',
      textContent: 'Content appears at the bottom of every page',
    }));

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
        childrenEl.appendChild(this._renderBlock(child));
      }
    }
    blockEl.appendChild(childrenEl);
  }
}
