import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { installHotkeys, uninstallHotkeys } from '../hotkeys';

describe('hotkeys', () => {
  let container: HTMLElement;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  afterEach(() => {
    container.remove();
  });

  it('should install without error on container with data-hotkey elements', () => {
    const btn = document.createElement('button');
    btn.dataset.hotkey = 'Mod+z';
    container.appendChild(btn);

    expect(() => installHotkeys(container)).not.toThrow();
  });

  it('should return a cleanup function', () => {
    const btn = document.createElement('button');
    btn.dataset.hotkey = 'Mod+s';
    container.appendChild(btn);

    const cleanup = installHotkeys(container);
    expect(typeof cleanup).toBe('function');
    expect(() => cleanup()).not.toThrow();
  });

  it('should handle container with no hotkey elements', () => {
    expect(() => installHotkeys(container)).not.toThrow();
  });

  it('should uninstall without error', () => {
    const btn = document.createElement('button');
    btn.dataset.hotkey = 'Escape';
    container.appendChild(btn);

    installHotkeys(container);
    expect(() => uninstallHotkeys(container)).not.toThrow();
  });
});
