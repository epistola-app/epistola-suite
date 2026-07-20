// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WalkthroughLauncher } from './launcher.js';
import { TOURS } from './registry.js';
import { markChapterComplete } from './progress.js';

function clickTestId(root: ParentNode, id: string): void {
  const el = root.querySelector(`[data-testid="${id}"]`);
  if (el instanceof HTMLElement) el.click();
}

function markText(root: ParentNode, chapterId: string): string {
  return (
    root
      .querySelector(`[data-testid="walkthrough-chapter-${chapterId}"] .ep-wt-mark`)
      ?.textContent?.trim() ?? ''
  );
}

async function mount(): Promise<WalkthroughLauncher> {
  const root = document.createElement('epistola-editor');
  const el = new WalkthroughLauncher();
  root.appendChild(el);
  document.body.appendChild(root);
  await el.updateComplete;
  return el;
}

describe('WalkthroughLauncher', () => {
  beforeEach(() => {
    localStorage.clear();
    document.body.innerHTML = '';
  });
  afterEach(() => {
    localStorage.clear();
    document.body.innerHTML = '';
  });

  it('renders a Guide trigger with the menu closed', async () => {
    const el = await mount();
    expect(el.querySelector('[data-testid="walkthrough-guide-trigger"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="walkthrough-launcher"]')).toBeNull();
  });

  it('opens a menu listing every chapter', async () => {
    const el = await mount();
    clickTestId(el, 'walkthrough-guide-trigger');
    await el.updateComplete;

    expect(el.querySelector('[data-testid="walkthrough-launcher"]')).not.toBeNull();
    for (const t of TOURS) {
      expect(el.querySelector(`[data-testid="walkthrough-chapter-${t.id}"]`)).not.toBeNull();
    }
  });

  it('marks completed chapters with a check and the first incomplete as current', async () => {
    markChapterComplete(TOURS[0].id, TOURS[0].version);
    const el = await mount();
    clickTestId(el, 'walkthrough-guide-trigger');
    await el.updateComplete;

    expect(markText(el, TOURS[0].id)).toBe('✓');
    expect(markText(el, TOURS[1].id)).toBe('▶');
  });

  it('toggles the menu closed on a second trigger click', async () => {
    const el = await mount();
    clickTestId(el, 'walkthrough-guide-trigger');
    await el.updateComplete;
    clickTestId(el, 'walkthrough-guide-trigger');
    await el.updateComplete;
    expect(el.querySelector('[data-testid="walkthrough-launcher"]')).toBeNull();
  });
});
