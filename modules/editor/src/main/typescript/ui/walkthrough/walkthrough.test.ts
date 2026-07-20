// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { maybeStartEditorWalkthrough } from './walkthrough.js';
import { TOURS } from './registry.js';
import { markChapterComplete } from './progress.js';

const STYLE_ID = 'ep-driver-css';

function makeHost(withChrome: boolean): HTMLElement {
  const host = document.createElement('div');
  if (withChrome) {
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

describe('maybeStartEditorWalkthrough', () => {
  beforeEach(reset);
  afterEach(reset);

  it('starts the walkthrough (injecting driver styles once) when a chapter is incomplete', async () => {
    const host = makeHost(true);
    await maybeStartEditorWalkthrough(host);
    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);
  });

  it('does nothing when every chapter is already complete', async () => {
    for (const t of TOURS) markChapterComplete(t.id, t.version);
    const host = makeHost(true);
    await maybeStartEditorWalkthrough(host);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('does nothing when the editor chrome is absent', async () => {
    const host = makeHost(false);
    await maybeStartEditorWalkthrough(host);
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('does not inject a second style tag on a repeat start', async () => {
    const host = makeHost(true);
    await maybeStartEditorWalkthrough(host);
    await maybeStartEditorWalkthrough(host);
    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);
  });
});
