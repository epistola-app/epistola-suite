// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { maybeStartEditorWalkthrough } from './walkthrough.js';

const SEEN_KEY = 'ep:editor-walkthrough:v1';
const STYLE_ID = 'ep-driver-css';

function makeHost(withToolbar: boolean): HTMLElement {
  const host = document.createElement('div');
  if (withToolbar) host.appendChild(document.createElement('epistola-toolbar'));
  document.body.appendChild(host);
  return host;
}

describe('maybeStartEditorWalkthrough', () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.innerHTML = '';
    document.getElementById(STYLE_ID)?.remove();
  });

  afterEach(() => {
    localStorage.clear();
    document.body.innerHTML = '';
    document.getElementById(STYLE_ID)?.remove();
  });

  it('does nothing when the tour has already been seen', async () => {
    localStorage.setItem(SEEN_KEY, 'true');
    const host = makeHost(true);

    await maybeStartEditorWalkthrough(host);

    // Guard hit before the driver.js dynamic import, so no styles are injected.
    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('does nothing when the toolbar is absent', async () => {
    const host = makeHost(false);

    await maybeStartEditorWalkthrough(host);

    expect(document.getElementById(STYLE_ID)).toBeNull();
  });

  it('injects the driver styles once when starting for a first-time user', async () => {
    const host = makeHost(true);

    await maybeStartEditorWalkthrough(host);

    // One <style> is injected. (Its content is the inlined driver.css; under
    // Vitest the `?inline` import resolves empty, so we assert presence, not text.)
    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);
  });

  it('does not inject a second style tag on a repeat start', async () => {
    const host = makeHost(true);

    await maybeStartEditorWalkthrough(host);
    // Clear the seen flag so the guard does not short-circuit the second call.
    localStorage.removeItem(SEEN_KEY);
    await maybeStartEditorWalkthrough(host);

    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);
  });
});
