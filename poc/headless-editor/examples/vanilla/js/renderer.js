import { createElement, getBadgeClass, getBadgeStyle, logCallback } from './dom-helpers.js';

/**
 * BlockRenderer - Handles rendering blocks to the DOM
 *
 * This class is responsible for:
 * - Rendering the block tree structure
 * - Handling block selection visual states
 * - Rendering different block types (text, container, columns, column)
 * - Delegating events to the editor
 */
export class BlockRenderer {
  /**
   * @param {TemplateEditor} editor - The template editor instance
   * @param {string} containerId - ID of the root container element
   */
  constructor(editor, containerId) {
    this.editor = editor;
    this.container = document.getElementById(containerId);
    if (!this.container) {
      throw new Error(`Container element not found: ${containerId}`);
    }
  }

  /**
   * Renders all blocks to the DOM
   *
   * Clears the container and re-renders the entire block tree.
   * Called whenever the template changes.
   */
  render() {
    // Clear container
    while (this.container.firstChild) {
      this.container.removeChild(this.container.firstChild);
    }

    const state = this.editor.getState();

    if (state.template.blocks.length === 0) {
      this.container.appendChild(
        createElement('div', {
          className: 'empty-state',
          textContent: 'No blocks yet. Add one above.',
        })
      );
      return;
    }

    for (const block of state.template.blocks) {
      this.container.appendChild(this.renderBlock(block));
    }
  }

  /**
   * Renders the state display panel
   *
   * Shows current template state, selection, undo/redo status
   */
  renderState() {
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
   *
   * @param {Block} block - Block to render
   * @returns {Element} The rendered block element
   */
  renderBlock(block) {
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

    // Header with drag handle and delete button
    const header = createElement(
      'div',
      { className: 'block-header' },
      [
        createElement(
          'div',
          { className: 'd-flex align-items-center gap-2' },
          [
            createElement('i', {
              className: 'bi bi-grip-vertical drag-handle text-muted',
            }),
            createElement(
              'span',
              {
                className: `badge ${getBadgeClass(block.type)}`,
                style: getBadgeStyle(block.type),
              },
              [block.type]
            ),
          ]
        ),
        createElement(
          'button',
          {
            className: 'btn btn-outline-danger btn-sm',
            onClick: (e) => {
              this.editor.deleteBlock(block.id);
              e.stopPropagation();
            },
          },
          [createElement('i', { className: 'bi bi-trash' })]
        ),
      ]
    );
    blockEl.appendChild(header);

    // Render based on block type
    this.renderBlockContent(blockEl, block);

    return blockEl;
  }

  /**
   * Renders block-specific content based on type
   *
   * @param {Element} blockEl - The block container element
   * @param {Block} block - Block data
   */
  renderBlockContent(blockEl, block) {
    switch (block.type) {
      case 'text':
        this.renderTextContent(blockEl, block);
        break;
      case 'container':
        this.renderContainerContent(blockEl, block);
        break;
      case 'columns':
        this.renderColumnsContent(blockEl, block);
        break;
      case 'column':
        this.renderColumnContent(blockEl, block);
        break;
    }
  }

  /**
   * Renders text block content (textarea)
   *
   * @param {Element} blockEl - The block container element
   * @param {Block} block - Block data
   */
  renderTextContent(blockEl, block) {
    const textarea = createElement('textarea', {
      className: 'form-control form-control-sm',
      placeholder: 'Enter text...',
      rows: '2',
      onClick: (e) => e.stopPropagation(),
      onInput: (e) => {
        this.editor.updateBlock(block.id, { content: e.target.value });
      },
    });
    textarea.value = block.content || '';
    blockEl.appendChild(textarea);
  }

  /**
   * Renders container block children
   *
   * @param {Element} blockEl - The block container element
   * @param {Block} block - Block data
   */
  renderContainerContent(blockEl, block) {
    const childrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      childrenEl.appendChild(
        createElement('div', {
          className: 'empty-state',
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
   * Renders columns block layout (horizontal)
   *
   * @param {Element} blockEl - The block container element
   * @param {Block} block - Block data
   */
  renderColumnsContent(blockEl, block) {
    const columnsEl = createElement('div', {
      className: 'columns-layout sortable-container',
      'data-parent-id': block.id,
    });

    if (block.children.length === 0) {
      columnsEl.appendChild(
        createElement('div', {
          className: 'empty-state',
          style: 'padding: 0.5rem; flex: 1;',
          textContent: 'Add columns (max 4)',
        })
      );
    } else {
      for (const child of block.children) {
        columnsEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(columnsEl);
  }

  /**
   * Renders column block children
   *
   * @param {Element} blockEl - The block container element
   * @param {Block} block - Block data
   */
  renderColumnContent(blockEl, block) {
    const columnChildrenEl = createElement('div', {
      className: 'block-children sortable-container',
      'data-parent-id': block.id,
      style: 'margin-left: 0; padding-left: 0.5rem; border-left-color: #6f42c1;',
    });

    if (block.children.length === 0) {
      columnChildrenEl.appendChild(
        createElement('div', {
          className: 'empty-state',
          style: 'padding: 0.5rem;',
          textContent: 'Drop content here',
        })
      );
    } else {
      for (const child of block.children) {
        columnChildrenEl.appendChild(this.renderBlock(child));
      }
    }
    blockEl.appendChild(columnChildrenEl);
  }
}
