import { describe, expect, it } from 'vitest';
import { getEditorShortcutRegistry } from './editor-runtime.js';
import { defineShortcutRegistry } from './foundation.js';
import {
  assertValidMergedShortcutRegistries,
  assertValidPluginShortcutContribution,
  definePluginShortcutContribution,
  formatPluginShortcutIssues,
  mergeShortcutRegistries,
  validatePluginShortcutContribution,
} from './plugin-registry.js';

describe('plugin shortcut contribution validation', () => {
  it('accepts valid plugin namespaces and merges with core registry', () => {
    const contribution = definePluginShortcutContribution({
      pluginId: 'ai',
      commands: [
        {
          id: 'plugin.ai.open-panel',
          label: 'Open AI panel',
          category: 'Plugin',
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: 'plugin.ai.open-panel',
          context: 'global',
          keys: ['mod+space g'],
          when: () => true,
          display: 'Leader + G',
        },
      ],
    });

    const validation = validatePluginShortcutContribution(contribution);
    expect(validation.valid).toBe(true);

    expect(() => {
      assertValidPluginShortcutContribution(contribution);
    }).not.toThrow();

    const merged = mergeShortcutRegistries([
      getEditorShortcutRegistry(),
      defineShortcutRegistry(contribution),
    ]);
    expect(merged.commands.some((command) => command.id === 'plugin.ai.open-panel')).toBe(true);
  });

  it('rejects plugin commands outside plugin.<id> namespace', () => {
    const validation = validatePluginShortcutContribution({
      pluginId: 'ai',
      commands: [
        {
          id: 'editor.preview.toggle',
          label: 'Wrong namespace',
          category: 'Plugin',
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: 'editor.preview.toggle',
          context: 'global',
          keys: ['mod+shift+p'],
          display: 'Ctrl/Cmd + Shift + P',
        },
      ],
    } as unknown as Parameters<typeof validatePluginShortcutContribution>[0]);

    expect(validation.valid).toBe(false);
    expect(validation.issues.map((issue) => issue.code)).toContain('invalid-plugin-command-id');
    expect(validation.issues.map((issue) => issue.code)).toContain(
      'invalid-plugin-binding-command-id',
    );
  });

  it('rejects missing command references in plugin keybindings', () => {
    const validation = validatePluginShortcutContribution({
      pluginId: 'analytics',
      commands: [
        {
          id: 'plugin.analytics.open-panel',
          label: 'Open analytics',
          category: 'Plugin',
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: 'plugin.analytics.export',
          context: 'global',
          keys: ['mod+e'],
          display: 'Ctrl/Cmd + E',
        },
      ],
    });

    expect(validation.valid).toBe(false);
    expect(validation.issues.map((issue) => issue.code)).toContain('missing-command-reference');
  });

  it('rejects duplicate command IDs between core and plugin registries', () => {
    const pluginRegistry = defineShortcutRegistry({
      commands: [
        {
          id: 'editor.document.save',
          label: 'Plugin save override',
          category: 'Plugin',
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: 'editor.document.save',
          context: 'global',
          keys: ['mod+shift+s'],
          display: 'Ctrl/Cmd + Shift + S',
        },
      ],
    });

    expect(() => {
      assertValidMergedShortcutRegistries([getEditorShortcutRegistry(), pluginRegistry]);
    }).toThrow('duplicate-command-id');
  });

  it('formats plugin validation issues as a readable list', () => {
    const formatted = formatPluginShortcutIssues([
      {
        code: 'invalid-plugin-id',
        message: 'Invalid plugin id',
      },
      {
        code: 'missing-command-reference',
        message: 'Missing command reference',
      },
    ]);

    expect(formatted).toContain('1. [invalid-plugin-id] Invalid plugin id');
    expect(formatted).toContain('2. [missing-command-reference] Missing command reference');
  });
});
