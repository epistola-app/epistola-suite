import { EDITOR_SHORTCUTS_CONFIG } from '../shortcuts-config.js';
import {
  EDITOR_SHORTCUT_COMMAND_IDS,
  getEditorShortcutRegistry,
  getShortcutDisplayForCommandId,
} from './editor-runtime.js';
import { getInsertDialogShortcutRegistry } from './insert-dialog-runtime.js';
import { getResizeShortcutRegistry } from './resize-runtime.js';
import { getTextShortcutRegistry } from './text-runtime.js';
import type {
  CommandCategory,
  CommandDefinition,
  CommandId,
  ShortcutRegistryDefinition,
} from './foundation.js';

export const SHORTCUT_HELPER_ONE_COLUMN_MAX_ITEMS = 7;
export const SHORTCUT_HELPER_TWO_COLUMN_MIN_ITEMS = 8;

type ShortcutHelperGroupId =
  | 'leader-key'
  | 'leader-commands'
  | 'core'
  | 'text'
  | 'insert'
  | 'resize';

type ShortcutHelperGroupLayout = 'one-column' | 'two-column';

export interface ShortcutHelperProjectionItem {
  id: string;
  commandId?: CommandId;
  keys: string;
  action: string;
  matchStrokes: readonly string[];
  active: boolean;
}

export interface ShortcutHelperProjectionGroup {
  id: ShortcutHelperGroupId;
  title: string;
  layout: ShortcutHelperGroupLayout;
  fullWidth: boolean;
  items: readonly ShortcutHelperProjectionItem[];
}

export interface ShortcutHelperProjection {
  groups: readonly ShortcutHelperProjectionGroup[];
  footerTip: string;
}

export interface ShortcutHelperProjectionOptions {
  query?: string;
  activeStrokes?: readonly string[];
}

interface GroupDefinition {
  id: ShortcutHelperGroupId;
  title: string;
  fullWidth: boolean;
  projectItems: () => ShortcutRowSeed[];
}

interface ShortcutRowSeed {
  id: string;
  order: number;
  commandId?: CommandId;
  keys: string;
  action: string;
  matchStrokes: readonly string[];
}

interface CommandProjectionSeed {
  id: string;
  order: number;
  commandId: CommandId;
  action: string;
  displays: Set<string>;
  matchStrokes: Set<string>;
}

function normalizeSearchText(value: string): string {
  return value.trim().toLowerCase();
}

function normalizeShortcutSequence(sequence: string): string {
  return sequence
    .trim()
    .toLowerCase()
    .replace(/\s*\+\s*/g, '+')
    .replace(/\s+/g, ' ');
}

function normalizeStrokeToken(token: string): string {
  const parts = token
    .trim()
    .toLowerCase()
    .split('+')
    .filter((part) => part.length > 0)
    .map((part) => (part.startsWith('code:') ? part.slice('code:'.length) : part));

  return parts.join('+');
}

function extractMatchStrokesFromKeySequences(keys: readonly string[]): string[] {
  const strokes = new Set<string>();

  for (const key of keys) {
    const normalized = normalizeShortcutSequence(key);
    if (!normalized) {
      continue;
    }

    for (const token of normalized.split(' ')) {
      const stroke = normalizeStrokeToken(token);
      if (!stroke) {
        continue;
      }
      strokes.add(stroke);
    }
  }

  return [...strokes];
}

function buildCommandRowsFromRegistry<TContext>(
  registry: ShortcutRegistryDefinition<TContext>,
  options: { category: CommandCategory; idPrefix: string },
): ShortcutRowSeed[] {
  const commandById = new Map<CommandId, CommandDefinition<TContext>>();
  for (const command of registry.commands) {
    if (command.category !== options.category) {
      continue;
    }
    commandById.set(command.id, command);
  }

  const commandRowsById = new Map<CommandId, CommandProjectionSeed>();
  for (const [bindingIndex, binding] of registry.keybindings.entries()) {
    const command = commandById.get(binding.commandId);
    if (!command) {
      continue;
    }

    let row = commandRowsById.get(binding.commandId);
    if (!row) {
      row = {
        id: `${options.idPrefix}-${binding.commandId}`,
        order: bindingIndex,
        commandId: binding.commandId,
        action: command.label,
        displays: new Set<string>(),
        matchStrokes: new Set<string>(),
      };
      commandRowsById.set(binding.commandId, row);
    }

    if (binding.display && binding.display.length > 0) {
      row.displays.add(binding.display);
    }

    for (const stroke of extractMatchStrokesFromKeySequences(binding.keys)) {
      row.matchStrokes.add(stroke);
    }
  }

  return [...commandRowsById.values()]
    .sort((left, right) => left.order - right.order)
    .map((row) => {
      const keys = [...row.displays].join(' / ');
      return {
        id: row.id,
        order: row.order,
        commandId: row.commandId,
        keys,
        action: row.action,
        matchStrokes: [...row.matchStrokes],
      };
    });
}

function buildLeaderKeyRow(): ShortcutRowSeed {
  const activation = EDITOR_SHORTCUTS_CONFIG.leader.activation;
  const leaderStroke = `${activation.requiresModifier ? 'mod+' : ''}${activation.code.toLowerCase()}`;

  return {
    id: 'leader-key-activation',
    order: 0,
    keys: activation.helpKeys,
    action: activation.action,
    matchStrokes: [leaderStroke],
  };
}

const GROUP_DEFINITIONS: readonly GroupDefinition[] = [
  {
    id: 'leader-key',
    title: 'Leader Key',
    fullWidth: true,
    projectItems: () => [buildLeaderKeyRow()],
  },
  {
    id: 'leader-commands',
    title: 'Leader Commands',
    fullWidth: false,
    projectItems: () =>
      buildCommandRowsFromRegistry(getEditorShortcutRegistry(), {
        category: 'Leader',
        idPrefix: 'leader',
      }),
  },
  {
    id: 'core',
    title: 'Core',
    fullWidth: false,
    projectItems: () =>
      buildCommandRowsFromRegistry(getEditorShortcutRegistry(), {
        category: 'Core',
        idPrefix: 'core',
      }),
  },
  {
    id: 'text',
    title: 'Text',
    fullWidth: false,
    projectItems: () =>
      buildCommandRowsFromRegistry(getTextShortcutRegistry(), {
        category: 'Text',
        idPrefix: 'text',
      }),
  },
  {
    id: 'insert',
    title: 'Insert',
    fullWidth: false,
    projectItems: () =>
      buildCommandRowsFromRegistry(getInsertDialogShortcutRegistry(), {
        category: 'Insert',
        idPrefix: 'insert',
      }),
  },
  {
    id: 'resize',
    title: 'Resize',
    fullWidth: false,
    projectItems: () =>
      buildCommandRowsFromRegistry(getResizeShortcutRegistry(), {
        category: 'Resize',
        idPrefix: 'resize',
      }),
  },
];

function buildGroupLayout(itemCount: number): ShortcutHelperGroupLayout {
  if (itemCount >= SHORTCUT_HELPER_TWO_COLUMN_MIN_ITEMS) {
    return 'two-column';
  }
  return 'one-column';
}

function shouldIncludeItem(seed: ShortcutRowSeed, query: string): boolean {
  if (!query) {
    return true;
  }

  const searchable = `${seed.keys} ${seed.keys.replaceAll('{cmd}', 'ctrl/cmd')} ${seed.action}`;
  return normalizeSearchText(searchable).includes(query);
}

function toProjectionItem(
  seed: ShortcutRowSeed,
  activeStrokes: Set<string>,
): ShortcutHelperProjectionItem {
  const isActive = seed.matchStrokes.some((stroke) =>
    activeStrokes.has(normalizeStrokeToken(stroke)),
  );
  return {
    id: seed.id,
    commandId: seed.commandId,
    keys: seed.keys,
    action: seed.action,
    matchStrokes: seed.matchStrokes,
    active: isActive,
  };
}

export function buildShortcutHelperProjection(
  options: ShortcutHelperProjectionOptions = {},
): ShortcutHelperProjection {
  const query = normalizeSearchText(options.query ?? '');
  const activeStrokes = new Set(
    (options.activeStrokes ?? []).map((stroke) => normalizeStrokeToken(stroke)),
  );

  const groups: ShortcutHelperProjectionGroup[] = [];
  for (const groupDefinition of GROUP_DEFINITIONS) {
    const visibleItems = groupDefinition
      .projectItems()
      .filter((item) => shouldIncludeItem(item, query))
      .map((item) => toProjectionItem(item, activeStrokes));

    if (visibleItems.length === 0) {
      continue;
    }

    groups.push({
      id: groupDefinition.id,
      title: groupDefinition.title,
      layout: buildGroupLayout(visibleItems.length),
      fullWidth: groupDefinition.fullWidth,
      items: visibleItems,
    });
  }

  return {
    groups,
    footerTip: getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.openShortcutsHelp),
  };
}

export function collectHelperProjectionDisplays(
  group: ShortcutHelperProjectionGroup,
): readonly string[] {
  return group.items.map((item) => item.keys);
}

export function collectHelperProjectionBindings(
  group: ShortcutHelperProjectionGroup,
): readonly string[] {
  return [...new Set(group.items.flatMap((item) => item.matchStrokes))];
}

export type { ShortcutHelperGroupLayout };
