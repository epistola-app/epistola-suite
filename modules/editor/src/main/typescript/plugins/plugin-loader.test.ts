import { describe, expect, it, vi } from 'vitest';
import { loadEditorPlugins, type EditorPluginDescriptor } from './plugin-loader.js';
import type { EditorPlugin } from './types.js';

const aiDescriptor: EditorPluginDescriptor = {
  id: 'ai',
  feature: 'aiChat',
  moduleUrl: '/editor/ai-plugin.js',
  stylesheetUrl: '/editor/ai-plugin.css',
  factoryExport: 'createEditorPlugin',
};

describe('loadEditorPlugins', () => {
  it('does not import a module for a disabled feature', async () => {
    const importModule = vi.fn();

    const plugins = await loadEditorPlugins([aiDescriptor], {
      features: { aiChat: { enabled: false } },
      importModule,
    });

    expect(plugins).toEqual([]);
    expect(importModule).not.toHaveBeenCalled();
  });

  it('imports an enabled lazy plugin and passes feature metadata to the factory', async () => {
    const plugin: EditorPlugin = { id: 'ai', init: () => () => {} };
    const factory = vi.fn(() => plugin);
    const importModule = vi.fn(async () => ({ createEditorPlugin: factory }));

    const plugins = await loadEditorPlugins([aiDescriptor], {
      features: {
        aiChat: { enabled: true, badge: { label: 'Alpha', className: 'badge-alpha' } },
      },
      importModule,
      csrfToken: () => 'csrf-token',
    });

    expect(importModule).toHaveBeenCalledWith('/editor/ai-plugin.js');
    expect(factory).toHaveBeenCalledWith({
      descriptor: aiDescriptor,
      feature: { enabled: true, badge: { label: 'Alpha', className: 'badge-alpha' } },
      config: undefined,
      csrfToken: expect.any(Function),
    });
    expect(plugins).toEqual([plugin]);
  });

  it('loads an enabled plugin stylesheet once', async () => {
    const appended: Array<{ rel?: string; href?: string; dataset: Record<string, string> }> = [];
    const documentRef = {
      head: {
        querySelector: vi.fn().mockReturnValueOnce(null).mockReturnValueOnce({ rel: 'stylesheet' }),
        appendChild: vi.fn((link) => appended.push(link)),
      },
      createElement: vi.fn(() => ({ dataset: {} })),
    } as unknown as Document;

    const importModule = vi.fn(async () => ({
      createEditorPlugin: () => ({ id: 'ai', init: () => () => {} }),
    }));

    await loadEditorPlugins([aiDescriptor, aiDescriptor], {
      features: { aiChat: { enabled: true } },
      importModule,
      document: documentRef,
    });

    expect(appended).toEqual([
      {
        rel: 'stylesheet',
        href: '/editor/ai-plugin.css',
        dataset: { editorPluginStyle: 'ai' },
      },
    ]);
  });

  it('ignores factories that return null', async () => {
    const plugins = await loadEditorPlugins(
      [{ id: 'quality', feature: 'quality', factoryExport: 'createQualityEditorPlugin' }],
      {
        features: { quality: { enabled: true } },
        bundledModule: { createQualityEditorPlugin: () => null },
      },
    );

    expect(plugins).toEqual([]);
  });
});
