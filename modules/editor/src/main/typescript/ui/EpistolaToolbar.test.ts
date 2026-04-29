import { describe, expect, it } from 'vitest';
import { EpistolaToolbar } from './EpistolaToolbar.js';

function templateToMarkup(template: unknown): string {
  if (!template || typeof template !== 'object' || !('strings' in template)) {
    return '';
  }
  const strings = (template as { strings: string[] }).strings;
  return strings.join('');
}

describe('EpistolaToolbar shortcut popover accessibility', () => {
  it('renders shortcut trigger accessibility attributes', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _renderExampleSelector: (examples: object[]) => unknown;
    };

    const template = toolbarAny._renderExampleSelector([{}]);
    const markup = templateToMarkup(template);

    expect(markup).toContain('aria-label="Keyboard shortcuts"');
    expect(markup).toContain('aria-haspopup="dialog"');
    expect(markup).toContain('aria-controls=');
  });

  it('defines popover dialog and filter input labels in render template', () => {
    const toolbar = new EpistolaToolbar();
    const renderSource = String(
      (toolbar as unknown as { _renderExampleSelector: (examples: object[]) => unknown })
        ._renderExampleSelector,
    );

    expect(renderSource).toContain('role="dialog"');
    expect(renderSource).toContain('aria-label="Filter keyboard shortcuts"');
  });

  it('defines data preview copy, drag hint, and textarea in render template', () => {
    const toolbar = new EpistolaToolbar();
    const renderSource = String(
      (toolbar as unknown as { _renderExampleSelector: (examples: object[]) => unknown })
        ._renderExampleSelector,
    );

    expect(renderSource).toContain('data-example-copy');
    expect(renderSource).toContain('data-example-drag-handle');
    expect(renderSource).toContain('Drag to move');
    expect(renderSource).toContain('Pin to keep this viewer open and movable');
    expect(renderSource).toContain('Current data example JSON');
  });

  it('renders current-data trigger accessibility attributes', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _renderExampleSelector: (examples: object[]) => unknown;
    };

    const template = toolbarAny._renderExampleSelector([{}]);
    const markup = templateToMarkup(template);

    expect(markup).toContain('aria-label="Current data example"');
    expect(markup).toContain('data-testid="data-example-trigger"');
    expect(markup).toContain('aria-haspopup="dialog"');
  });

  it('emits open-data-contract from the toolbar action', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _handleOpenDataContract: () => void;
    };
    let opened = false;
    toolbar.addEventListener('open-data-contract', () => {
      opened = true;
    });

    toolbarAny._handleOpenDataContract();

    expect(opened).toBe(true);
  });

  it('renders data contract toolbar action when enabled', () => {
    const toolbar = new EpistolaToolbar();
    toolbar.hasDataContract = true;

    const rendered = toolbar.render() as unknown as {
      values?: unknown[];
    };
    const nested = JSON.stringify(rendered.values ?? []);

    expect(nested).toContain('toolbar-data-contract-trigger');
    expect(nested).toContain('Data Contract');
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

  it('closes current-data popover on Escape and prevents default', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _dataPreviewOpen: boolean;
      _dataPreviewPinned: boolean;
      _onWindowKeydown: (e: KeyboardEvent) => void;
    };
    toolbarAny._dataPreviewOpen = true;
    toolbarAny._dataPreviewPinned = false;

    let prevented = false;
    toolbarAny._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);

    expect(prevented).toBe(true);
    expect(toolbarAny._dataPreviewOpen).toBe(false);
  });

  it('keeps pinned current-data popover open on Escape', () => {
    const toolbar = new EpistolaToolbar();
    const toolbarAny = toolbar as unknown as {
      _dataPreviewOpen: boolean;
      _dataPreviewPinned: boolean;
      _onWindowKeydown: (e: KeyboardEvent) => void;
    };
    toolbarAny._dataPreviewOpen = true;
    toolbarAny._dataPreviewPinned = true;

    let prevented = false;
    toolbarAny._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);

    expect(prevented).toBe(false);
    expect(toolbarAny._dataPreviewOpen).toBe(true);
  });

  it('builds current example preview content from the active payload', () => {
    const toolbar = new EpistolaToolbar();
    toolbar.engine = {
      dataExamples: [{ id: 'ex-1', name: 'Customer Sample', data: { customer: { name: 'Ada' } } }],
      getExampleData: () => ({ customer: { name: 'Ada' } }),
    } as unknown as EpistolaToolbar['engine'];

    const toolbarAny = toolbar as unknown as {
      _resolveCurrentExamplePreview: () => { header: string; json: string | null };
    };
    const preview = toolbarAny._resolveCurrentExamplePreview();

    expect(preview.header).toBe('Customer Sample (1/1)');
    expect(preview.json).toContain('"customer"');
    expect(preview.json).toContain('"Ada"');
  });
});
