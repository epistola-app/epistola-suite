// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { describe, expect, it } from 'vitest';
import type { EditorPluginFactoryContext } from './plugins/plugin-loader.js';
import { createEditorPlugin } from './walkthrough-plugin-lib.js';

function factoryContext(): EditorPluginFactoryContext {
  return {
    descriptor: {
      id: 'editor-walkthrough',
      feature: 'editorWalkthrough',
      moduleUrl: '/editor/walkthrough-plugin.js',
      factoryExport: 'createEditorPlugin',
    },
    feature: { enabled: true },
    config: undefined,
    csrfToken: () => '',
  };
}

describe('walkthrough plugin entry', () => {
  it('contributes the Guide launcher as custom toolbar UI', () => {
    const plugin = createEditorPlugin(factoryContext());

    expect(plugin.id).toBe('editor-walkthrough');
    expect(plugin.toolbarItems).toHaveLength(1);
    expect(plugin.toolbarItems?.[0]).toMatchObject({
      kind: 'custom',
      id: 'walkthrough-guide',
    });
  });
});
