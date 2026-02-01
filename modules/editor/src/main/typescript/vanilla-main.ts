/**
 * Vanilla Editor Development Entry Point
 *
 * This file is the entry point for development/testing of the vanilla editor.
 * It mounts the editor to the DOM and sets up debugging.
 */

import { mountEditor } from './vanilla-lib.js';
import { logCallback } from './adapters/vanilla/dom-helpers.js';
import type { Template } from './core/types.js';

// Sample template for development
const sampleTemplate: Template = {
  id: 'sample-1',
  name: 'Sample Template',
  blocks: [
    {
      id: 'text-1',
      type: 'text',
      content: 'Hello, this is a sample text block. Edit me!',
    },
    {
      id: 'container-1',
      type: 'container',
      children: [
        {
          id: 'nested-text-1',
          type: 'text',
          content: 'This text is inside a container.',
        },
      ],
    },
  ],
};

// Wait for DOM to be ready
document.addEventListener('DOMContentLoaded', () => {
  const container = document.getElementById('block-list');
  if (!container) {
    console.error('Block list container not found');
    return;
  }

  logCallback('Initializing editor...');

  // Mount the editor
  const editor = mountEditor({
    container: 'block-list',
    template: sampleTemplate,
    debug: true,
    onChange: (template) => {
      logCallback(`Template updated: ${template.blocks.length} blocks`);
    },
    onError: (error) => {
      logCallback(`Error: ${error.message}`);
      console.error(error);
    },
  });

  // Expose editor to window for debugging
  (window as unknown as { editor: typeof editor }).editor = editor;

  logCallback('Editor initialized. Access via window.editor');
  logCallback('Press Ctrl+Z to undo, Ctrl+Shift+Z to redo');
  logCallback('Delete/Backspace removes selected block');
});
