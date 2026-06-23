import { describe, expect, it } from 'vitest';
import { EpistolaTemplateJsonViewer } from './EpistolaTemplateJsonViewer.js';

function templateToMarkup(template: unknown): string {
  if (!template || typeof template !== 'object' || !('strings' in template)) {
    return '';
  }
  const strings = (template as { strings: string[] }).strings;
  return strings.join('');
}

type ViewerInternals = {
  _open: boolean;
  _copyState: 'idle' | 'copied' | 'error';
  _resolveJson: () => { header: string; json: string | null };
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

function viewerWithDoc(doc: unknown = SAMPLE_DOC): EpistolaTemplateJsonViewer {
  const viewer = new EpistolaTemplateJsonViewer();
  viewer.engine = {
    doc,
    events: { on: () => () => {} },
  } as unknown as EpistolaTemplateJsonViewer['engine'];
  return viewer;
}

describe('EpistolaTemplateJsonViewer', () => {
  it('serializes the effective template document (engine.doc) with a model-version header', () => {
    const viewer = viewerWithDoc();
    const internals = viewer as unknown as ViewerInternals;

    const resolved = internals._resolveJson();

    expect(resolved.header).toBe('Effective template document (modelVersion 1)');
    expect(resolved.json).toContain('"root": "node-root"');
    expect(resolved.json).toContain('"modelVersion": 1');
    // Pretty-printed (2-space indent), matching JSON.stringify(doc, null, 2).
    expect(resolved.json).toContain('\n  "root"');
  });

  it('reports no template when the engine has no document', () => {
    const viewer = new EpistolaTemplateJsonViewer();
    const internals = viewer as unknown as ViewerInternals;

    const resolved = internals._resolveJson();

    expect(resolved.header).toBe('No template loaded');
    expect(resolved.json).toBeNull();
  });

  it('renders a labelled dialog with copy/close controls when open', () => {
    const viewer = viewerWithDoc();
    const internals = viewer as unknown as ViewerInternals;
    internals._open = true;

    const markup = templateToMarkup(viewer.render());

    expect(markup).toContain('role="dialog"');
    expect(markup).toContain('aria-label="Effective template JSON"');
    expect(markup).toContain('data-testid="template-json-popover"');
    expect(markup).toContain('data-testid="template-json-copy"');
    expect(markup).toContain('data-testid="template-json-close"');
  });

  it('defines a read-only JSON textarea in the render template', () => {
    // The textarea lives in a nested conditional template, so assert against the
    // render source (mirrors the EpistolaToolbar data-preview tests).
    const renderSource = String(new EpistolaTemplateJsonViewer().render);

    expect(renderSource).toContain('data-testid="template-json-textarea"');
    expect(renderSource).toContain('readonly');
    expect(renderSource).toContain('No template loaded.');
  });

  it('renders nothing while closed', () => {
    const viewer = viewerWithDoc();
    const markup = templateToMarkup(viewer.render());
    expect(markup).toBe('');
  });

  it('closes on Escape and prevents the default, only while open', () => {
    const viewer = viewerWithDoc();
    const internals = viewer as unknown as ViewerInternals;

    // Closed: Escape is ignored and not prevented.
    let prevented = false;
    internals._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);
    expect(prevented).toBe(false);
    expect(internals._open).toBe(false);

    // Open: Escape closes it and prevents the default.
    internals._open = true;
    internals._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true;
      },
    } as KeyboardEvent);
    expect(prevented).toBe(true);
    expect(internals._open).toBe(false);
  });

  it('close() is idempotent and clears the open flag', () => {
    const viewer = viewerWithDoc();
    const internals = viewer as unknown as ViewerInternals;
    internals._open = true;

    viewer.close();
    expect(internals._open).toBe(false);
    // Calling again is a no-op (does not throw).
    viewer.close();
    expect(internals._open).toBe(false);
  });

  it('reflects copy feedback in the button label', () => {
    const viewer = viewerWithDoc();
    const internals = viewer as unknown as ViewerInternals;

    expect(internals._copyButtonLabel()).toBe('Copy JSON');
    internals._copyState = 'copied';
    expect(internals._copyButtonLabel()).toBe('Copied');
    internals._copyState = 'error';
    expect(internals._copyButtonLabel()).toBe('Copy failed');
  });
});
