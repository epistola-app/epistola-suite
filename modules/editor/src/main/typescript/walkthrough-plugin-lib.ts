// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * @module @epistola/editor/walkthrough-plugin
 *
 * Frontend-only editor plugin entry point for the guided walkthrough.
 */
import { html } from 'lit';
import type { EditorPluginFactoryContext } from './plugins/plugin-loader.js';
import type { EditorPlugin } from './plugins/types.js';
import './plugins/walkthrough/launcher.js';

export function createEditorPlugin(_context: EditorPluginFactoryContext): EditorPlugin {
  return {
    id: 'editor-walkthrough',
    toolbarItems: [
      {
        kind: 'custom',
        id: 'walkthrough-guide',
        render: () => html`<epistola-walkthrough-launcher></epistola-walkthrough-launcher>`,
      },
    ],
    init: () => () => {},
  };
}
