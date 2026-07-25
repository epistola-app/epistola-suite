// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { EditorEngine } from '../../engine/EditorEngine.js';
import { createTestDocument, testRegistry } from '../../engine/test-helpers.js';
import type { TourContext } from './registry.js';
import { startIntro, startTour } from './walkthrough.js';
import { markIntroSeen } from './progress.js';

const STYLE_ID = 'ep-driver-css';

function styleCount(): number {
  return document.querySelectorAll(`#${STYLE_ID}`).length;
}

function makeCtx(opts: { guide?: boolean; chrome?: boolean } = {}): TourContext {
  const host = document.createElement('div');
  if (opts.guide) {
    const btn = document.createElement('button');
    btn.setAttribute('data-tour', 'guide-trigger');
    host.appendChild(btn);
  }
  if (opts.chrome) {
    host.appendChild(document.createElement('epistola-toolbar'));
    host.appendChild(document.createElement('epistola-sidebar'));
    host.appendChild(document.createElement('epistola-canvas'));
  }
  document.body.appendChild(host);
  return { host, engine: new EditorEngine(createTestDocument(), testRegistry()) };
}

function reset(): void {
  localStorage.clear();
  document.body.innerHTML = '';
  document.getElementById(STYLE_ID)?.remove();
}

describe('startIntro', () => {
  beforeEach(reset);
  afterEach(reset);

  it('spotlights the Guide button (injecting styles once) on first run', async () => {
    const ctx = makeCtx({ guide: true });
    await startIntro(ctx);
    expect(styleCount()).toBe(1);
  });

  it('no-ops once the intro has been seen', async () => {
    markIntroSeen();
    const ctx = makeCtx({ guide: true });
    await startIntro(ctx);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('no-ops when the Guide button is absent', async () => {
    const ctx = makeCtx({ guide: false });
    await startIntro(ctx);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });
});

describe('startTour', () => {
  beforeEach(reset);
  afterEach(reset);

  it('runs a known chapter, injecting the driver styles once', async () => {
    const ctx = makeCtx({ chrome: true });
    await startTour(ctx, 'orientation');
    expect(styleCount()).toBe(1);
  });

  it('does nothing for an unknown chapter id', async () => {
    const ctx = makeCtx({ chrome: true });
    await startTour(ctx, 'does-not-exist');
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });
});
