// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, expect, it } from 'vitest';
import {
  EDITOR_SHORTCUT_COMMAND_IDS,
  getEditorShortcutRegistry,
  getLeaderIdleTokensForCommandIds,
  getShortcutDisplayForCommandId,
} from './editor-runtime.js';

describe('editor shortcut runtime registry', () => {
  it('includes core and leader commands with keybindings', () => {
    const registry = getEditorShortcutRegistry();

    expect(registry.commands.length).toBeGreaterThan(0);
    expect(registry.keybindings.length).toBeGreaterThan(0);

    expect(
      registry.commands.some((command) => command.id === EDITOR_SHORTCUT_COMMAND_IDS.save),
    ).toBe(true);
    expect(
      registry.commands.some((command) => command.id === EDITOR_SHORTCUT_COMMAND_IDS.togglePreview),
    ).toBe(true);
    expect(
      registry.commands.some(
        (command) => command.id === EDITOR_SHORTCUT_COMMAND_IDS.openDataPreview,
      ),
    ).toBe(true);
    expect(
      registry.commands.some(
        (command) => command.id === EDITOR_SHORTCUT_COMMAND_IDS.toggleCleanMode,
      ),
    ).toBe(true);
  });

  it('registers the template JSON viewer as a global leader chord (Leader + J)', () => {
    const registry = getEditorShortcutRegistry();

    const command = registry.commands.find(
      (c) => c.id === EDITOR_SHORTCUT_COMMAND_IDS.openTemplateJson,
    );
    expect(command).toBeDefined();
    expect(command?.category).toBe('Leader');

    const binding = registry.keybindings.find(
      (b) => b.commandId === EDITOR_SHORTCUT_COMMAND_IDS.openTemplateJson,
    );
    expect(binding).toBeDefined();
    expect(binding?.context).toBe('global');
    expect(binding?.keys).toContain('mod+code:period j');

    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.openTemplateJson)).toBe(
      'Leader + J',
    );
    expect(
      getLeaderIdleTokensForCommandIds([EDITOR_SHORTCUT_COMMAND_IDS.openTemplateJson]),
    ).toEqual(['J']);
  });

  it('uses editor context for core shortcuts and global context for leader chords', () => {
    const registry = getEditorShortcutRegistry();

    const saveBinding = registry.keybindings.find(
      (binding) => binding.commandId === EDITOR_SHORTCUT_COMMAND_IDS.save,
    );
    expect(saveBinding).toBeDefined();
    expect(saveBinding?.context).toBe('editor');
    expect(saveBinding?.keys).toContain('mod+s');

    const previewBinding = registry.keybindings.find(
      (binding) => binding.commandId === EDITOR_SHORTCUT_COMMAND_IDS.togglePreview,
    );
    expect(previewBinding).toBeDefined();
    expect(previewBinding?.context).toBe('global');
    expect(previewBinding?.keys).toContain('mod+code:period p');

    const cleanModeBinding = registry.keybindings.find(
      (binding) => binding.commandId === EDITOR_SHORTCUT_COMMAND_IDS.toggleCleanMode,
    );
    expect(cleanModeBinding).toBeDefined();
    expect(cleanModeBinding?.context).toBe('global');
    expect(cleanModeBinding?.keys).toContain('mod+code:period c');
  });

  it('preserves leader alias keys for opening shortcuts help', () => {
    const registry = getEditorShortcutRegistry();
    const helpBinding = registry.keybindings.find(
      (binding) => binding.commandId === EDITOR_SHORTCUT_COMMAND_IDS.openShortcutsHelp,
    );

    expect(helpBinding).toBeDefined();
    expect(helpBinding?.keys).toContain('mod+code:period /');
    expect(helpBinding?.keys).toContain('mod+code:period shift+?');
  });

  it('returns leader idle tokens from command ids', () => {
    const tokens = getLeaderIdleTokensForCommandIds([
      EDITOR_SHORTCUT_COMMAND_IDS.togglePreview,
      EDITOR_SHORTCUT_COMMAND_IDS.openShortcutsHelp,
      EDITOR_SHORTCUT_COMMAND_IDS.openDataPreview,
      EDITOR_SHORTCUT_COMMAND_IDS.toggleCleanMode,
    ]);

    expect(tokens).toEqual(['P', '?', 'E', 'C']);
  });

  it('returns toolbar display labels from runtime registry bindings', () => {
    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.undo)).toBe('{cmd} + Z');
    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.redo)).toBe(
      '{cmd} + Shift + Z / {cmd} + Y',
    );
    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.openShortcutsHelp)).toBe(
      'Leader + ? or /',
    );
    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.openDataPreview)).toBe(
      'Leader + E',
    );
    expect(getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.toggleCleanMode)).toBe(
      'Leader + C',
    );
  });
});
