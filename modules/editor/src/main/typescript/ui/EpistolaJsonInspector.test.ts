// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import { EpistolaJsonInspector } from './EpistolaJsonInspector.js';

function templateToMarkup(template: unknown): string {
  if (!template || typeof template !== 'object' || !('strings' in template)) {
    return '';
  }
  const strings = (template as { strings: string[] }).strings;
  return strings.join('');
}

type Internals = {
  _open: boolean;
  _pinned: boolean;
  _view: 'data' | 'template';
  _copyState: 'idle' | 'copied' | 'error';
  _effectiveView: () => 'data' | 'template';
  _setView: (view: 'data' | 'template', e: Event) => void;
  _resolveContent: () => { header: string; json: string | null };
  _resolveTemplateJson: () => { header: string; json: string | null };
  _resolveExampleJson: () => { header: string; json: string | null };
  _copyButtonLabel: () => string;
  _onWindowKeydown: (e: KeyboardEvent) => void;
};

const SAMPLE_DOC = {
  modelVersion: 1,
  root: 'node-root',
  nodes: { 'node-root': { id: 'node-root', type: 'page', slots: [] } },
  slots: {},
  themeRef: { type: 'inherit' },
};

function inspectorWith(opts: {
  doc?: unknown;
  examples?: unknown[];
  exampleData?: unknown;
}): EpistolaJsonInspector {
  const inspector = new EpistolaJsonInspector();
  inspector.engine = {
    doc: opts.doc,
    dataExamples: opts.examples,
    currentExampleIndex: 0,
    getExampleData: () => opts.exampleData,
    events: { on: () => () => {} },
  } as unknown as EpistolaJsonInspector['engine'];
  return inspector;
}

describe('EpistolaJsonInspector', () => {
  it('serializes the effective template document with a model-version header', () => {
    const internals = inspectorWith({ doc: SAMPLE_DOC }) as unknown as Internals;
    const resolved = internals._resolveTemplateJson();

    expect(resolved.header).toBe('Effective template document (modelVersion 1)');
    expect(resolved.json).toContain('"root": "node-root"');
    expect(resolved.json).toContain('\n  "root"'); // pretty-printed, 2-space indent
  });

  it('reports no template when the engine has no document', () => {
    const internals = inspectorWith({ doc: undefined }) as unknown as Internals;
    const resolved = internals._resolveTemplateJson();

    expect(resolved.header).toBe('No template loaded');
    expect(resolved.json).toBeNull();
  });

  it('builds the data-example content from the active payload', () => {
    const internals = inspectorWith({
      examples: [{ id: 'ex-1', name: 'Customer Sample' }],
      exampleData: { customer: { name: 'Ada' } },
    }) as unknown as Internals;

    const preview = internals._resolveExampleJson();

    expect(preview.header).toBe('Customer Sample (1/1)');
    expect(preview.json).toContain('"customer"');
    expect(preview.json).toContain('"Ada"');
  });

  it('defaults to the template view and ignores the data view when there are no examples', () => {
    const internals = inspectorWith({ doc: SAMPLE_DOC }) as unknown as Internals;

    // Prefer 'data', but with no examples the effective view degrades to 'template'.
    internals._view = 'data';
    expect(internals._effectiveView()).toBe('template');
    expect(internals._resolveContent().header).toContain('Effective template document');

    // Switching to 'data' has no effect without examples — the view stays template.
    internals._setView('data', new Event('click'));
    expect(internals._effectiveView()).toBe('template');
  });

  it('shows the data view when examples exist and the data view is selected', () => {
    const internals = inspectorWith({
      doc: SAMPLE_DOC,
      examples: [{ name: 'Customer Sample' }],
      exampleData: { customer: { name: 'Ada' } },
    }) as unknown as Internals;

    internals._view = 'data';
    expect(internals._effectiveView()).toBe('data');
    expect(internals._resolveContent().header).toBe('Customer Sample (1/1)');

    internals._setView('template', new Event('click'));
    expect(internals._effectiveView()).toBe('template');
  });

  it('closes on Escape when open and unpinned, but stays open when pinned', () => {
    const internals = inspectorWith({ doc: SAMPLE_DOC }) as unknown as Internals;

    internals._open = true;
    internals._pinned = false;
    let prevented = false;
    internals._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);
    expect(prevented).toBe(true);
    expect(internals._open).toBe(false);

    // Pinned: Escape is ignored.
    internals._open = true;
    internals._pinned = true;
    prevented = false;
    internals._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);
    expect(prevented).toBe(false);
    expect(internals._open).toBe(true);
  });

  it('reflects copy feedback in the button label', () => {
    const internals = inspectorWith({ doc: SAMPLE_DOC }) as unknown as Internals;

    expect(internals._copyButtonLabel()).toBe('Copy JSON');
    internals._copyState = 'copied';
    expect(internals._copyButtonLabel()).toBe('Copied');
    internals._copyState = 'error';
    expect(internals._copyButtonLabel()).toBe('Copy failed');
  });

  it('always renders the trigger; popover markup defines the view tabs and JSON area', () => {
    const inspector = inspectorWith({ doc: SAMPLE_DOC });
    const markup = templateToMarkup(inspector.render());
    expect(markup).toContain('data-testid="inspector-trigger"');

    // Popover internals live in a nested conditional template; assert via source.
    const renderSource = String(inspector.render);
    expect(renderSource).toContain('data-testid="inspector-popover"');
    expect(renderSource).toContain('data-testid="inspector-tab-template"');
    expect(renderSource).toContain('data-testid="inspector-tab-data"');
    expect(renderSource).toContain('data-testid="inspector-json"');
  });
});
