import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mountEditor, getEditor, setEditor } from '../mount';
import type { MountConfig } from '../types';

const EMPTY_TEMPLATE = {
  id: 'test-1',
  name: 'Test',
  blocks: [],
  styles: {},
};

describe('mountEditor', () => {
  let container: HTMLElement;

  beforeEach(() => {
    // Reset module state
    setEditor(null);
    container = document.createElement('div');
    container.id = 'test-editor';
    document.body.appendChild(container);
  });

  afterEach(() => {
    container.remove();
  });

  it('should mount with an HTMLElement container', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);

    expect(mounted).toBeDefined();
    expect(mounted.getTemplate()).toBeDefined();
    expect(mounted.getEditor()).toBeDefined();

    mounted.destroy();
  });

  it('should mount with a CSS selector container', () => {
    const config: MountConfig = { container: '#test-editor', template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);

    expect(mounted.getTemplate().id).toBe('test-1');

    mounted.destroy();
  });

  it('should throw on invalid container selector', () => {
    const config: MountConfig = { container: '#nonexistent', template: EMPTY_TEMPLATE };
    expect(() => mountEditor(config)).toThrow('Container not found');
  });

  it('should set the module-level editor on mount', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);

    expect(getEditor()).toBe(mounted.getEditor());

    mounted.destroy();
  });

  it('should clear the module-level editor on destroy', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);
    mounted.destroy();

    expect(getEditor()).toBeNull();
  });

  it('should prevent double-mounting', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);

    expect(() => mountEditor(config)).toThrow('already mounted');

    mounted.destroy();
  });

  it('should allow re-mounting after destroy', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };

    const mounted1 = mountEditor(config);
    mounted1.destroy();

    const mounted2 = mountEditor(config);
    expect(mounted2).toBeDefined();
    mounted2.destroy();
  });

  it('should return the template from getTemplate()', () => {
    const config: MountConfig = { container, template: EMPTY_TEMPLATE };
    const mounted = mountEditor(config);

    const template = mounted.getTemplate();
    expect(template.name).toBe('Test');
    expect(template.blocks).toEqual([]);

    mounted.destroy();
  });
});
