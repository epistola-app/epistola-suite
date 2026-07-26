// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

// @vitest-environment happy-dom

import { html } from 'lit';
import { describe, expect, it, vi } from 'vitest';
import { EditorEngine } from '../engine/EditorEngine.js';
import { createTestDocument, testRegistry } from '../engine/test-helpers.js';
import type { PluginContext } from '../plugins/types.js';
import { EpistolaToolbar } from './EpistolaToolbar.js';

function templateToMarkup(template: unknown): string {
  if (!template || typeof template !== 'object' || !('strings' in template)) {
    return '';
  }
  const strings = (template as { strings: string[] }).strings;
  return strings.join('');
}

function pluginContext(): PluginContext {
  const engine = new EditorEngine(createTestDocument(), testRegistry());
  return {
    engine,
    doc: engine.doc,
    selectedNodeId: null,
    selectNode: vi.fn(),
  };
}

describe('EpistolaToolbar shortcut popover accessibility', () => {
  it('renders shortcut trigger accessibility attributes', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _renderShortcuts: () => unknown;
    };

    const template = toolbarAny._renderShortcuts();
    const markup = templateToMarkup(template);

    expect(markup).toContain('aria-label="Keyboard shortcuts"');
    expect(markup).toContain('aria-haspopup="dialog"');
    expect(markup).toContain('aria-controls=');
  });

  it('renders the keyboard-shortcuts trigger independently of data examples', () => {
    // The shortcuts trigger is the discovery surface for editor shortcuts
    // (incl. Leader + J), so it must render even when the template has no
    // data examples and _renderExampleSelector is skipped entirely.
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as { _renderShortcuts: () => unknown };

    const markup = templateToMarkup(toolbarAny._renderShortcuts());

    expect(markup).toContain('data-testid="shortcuts-trigger"');
  });

  it('defines popover dialog and filter input labels in render template', () => {
    const toolbar = new EpistolaToolbar();
    const renderSource = String(
      (toolbar as unknown as { _renderShortcuts: () => unknown })._renderShortcuts,
    );

    expect(renderSource).toContain('role="dialog"');
    expect(renderSource).toContain('aria-label="Filter keyboard shortcuts"');
  });

  it('renders the JSON inspector element in the toolbar regardless of data examples', () => {
    // The inspector (data + template views) is a dedicated component the toolbar
    // hosts; it must be present even with no examples so the template view is
    // reachable (Leader + J).
    const toolbar = new EpistolaToolbar();
    const markup = templateToMarkup(toolbar.render());
    expect(markup).toContain('<epistola-json-inspector');
  });

  it('renders custom plugin items in declaration order', async () => {
    const toolbar = new EpistolaToolbar();
    toolbar.pluginContext = pluginContext();
    toolbar.pluginItems = [
      {
        kind: 'custom',
        id: 'first',
        render: () => html`<span data-plugin-item="first"></span>`,
      },
      {
        kind: 'custom',
        id: 'second',
        render: () => html`<span data-plugin-item="second"></span>`,
      },
    ];

    document.body.append(toolbar);
    await toolbar.updateComplete;

    expect(
      Array.from(toolbar.querySelectorAll('[data-plugin-item]')).map((element) =>
        element.getAttribute('data-plugin-item'),
      ),
    ).toEqual(['first', 'second']);
    toolbar.remove();
  });

  it('invokes a standard plugin action with the latest context', async () => {
    const toolbar = new EpistolaToolbar();
    const context = pluginContext();
    const onClick = vi.fn();
    toolbar.pluginContext = context;
    toolbar.pluginItems = [
      {
        kind: 'action',
        id: 'generate',
        label: 'Generate',
        icon: 'sparkles',
        onClick,
      },
    ];

    document.body.append(toolbar);
    await toolbar.updateComplete;
    toolbar.querySelector<HTMLButtonElement>('button[aria-label="Generate"]')?.click();

    expect(onClick).toHaveBeenCalledWith(context);
    toolbar.remove();
  });

  it('closes shortcut popover on Escape and prevents default', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _shortcutsOpen: boolean;
      _onWindowKeydown: (e: KeyboardEvent) => void;
    };
    toolbarAny._shortcutsOpen = true;

    let prevented = false;
    toolbarAny._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);

    expect(prevented).toBe(true);
    expect(toolbarAny._shortcutsOpen).toBe(false);
  });

  it('delegates inspector open requests to the json-inspector child', () => {
    const toolbar = new EpistolaToolbar();
    const calls: string[] = [];
    const fakeInspector = {
      openData: () => calls.push('data'),
      openTemplate: () => calls.push('template'),
    };
    // Stub the child lookup the toolbar uses to reach its inspector.
    (toolbar as unknown as { _inspector: () => unknown })._inspector = () => fakeInspector;

    toolbar.openDataPreview();
    toolbar.openTemplateJson();

    expect(calls).toEqual(['data', 'template']);
  });
});
