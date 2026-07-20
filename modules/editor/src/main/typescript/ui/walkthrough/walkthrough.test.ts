// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { startIntro, startTour } from './walkthrough.js';
import { markIntroSeen } from './progress.js';

const STYLE_ID = 'ep-driver-css';

function styleCount(): number {
  return document.querySelectorAll(`#${STYLE_ID}`).length;
}

function makeHost(opts: { guide?: boolean; chrome?: boolean } = {}): HTMLElement {
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
  return host;
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
    const host = makeHost({ guide: true });
    await startIntro(host);
    expect(styleCount()).toBe(1);
  });

  it('no-ops once the intro has been seen', async () => {
    markIntroSeen();
    const host = makeHost({ guide: true });
    await startIntro(host);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('no-ops when the Guide button is absent', async () => {
    const host = makeHost({ guide: false });
    await startIntro(host);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });
});

describe('startTour', () => {
  beforeEach(reset);
  afterEach(reset);

  it('runs a known chapter, injecting the driver styles once', async () => {
    const host = makeHost({ chrome: true });
    await startTour(host, 'orientation');
    expect(styleCount()).toBe(1);
  });

  it('does nothing for an unknown chapter id', async () => {
    const host = makeHost({ chrome: true });
    await startTour(host, 'does-not-exist');
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });
});
