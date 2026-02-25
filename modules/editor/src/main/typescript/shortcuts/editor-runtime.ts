import {
  EDITOR_SHORTCUTS_CONFIG,
  type CoreShortcutConfig,
  type CoreShortcutId,
  type LeaderShortcutCommandConfig,
  type LeaderShortcutCommandId,
} from '../shortcuts-config.js'
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type CommandId,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from './foundation.js'
import { toShortcutStrokesFromBindings } from './key-strokes.js'

const CORE_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.core.map((shortcut) => [shortcut.id, shortcut] as const),
)

const LEADER_SHORTCUTS_BY_ID = new Map(
  EDITOR_SHORTCUTS_CONFIG.leader.commands.map((shortcut) => [shortcut.id, shortcut] as const),
)

const LEADER_ACTIVATION_STROKE = `${EDITOR_SHORTCUTS_CONFIG.leader.activation.requiresModifier ? 'mod+' : ''}code:${EDITOR_SHORTCUTS_CONFIG.leader.activation.code.toLowerCase()}`

export const EDITOR_SHORTCUT_COMMAND_IDS = {
  save: 'editor.document.save',
  undo: 'editor.history.undo',
  redo: 'editor.history.redo',
  deleteSelectedBlock: 'editor.block.delete-selected',
  deselectSelectedBlock: 'editor.block.deselect',
  togglePreview: 'editor.preview.toggle',
  duplicateSelectedBlock: 'editor.block.duplicate',
  openInsertDialog: 'insertDialog.open',
  openShortcutsHelp: 'editor.shortcuts.open-help',
  focusBlocksPanel: 'editor.panel.blocks.focus',
  focusStructurePanel: 'editor.panel.structure.focus',
  focusInspectorPanel: 'editor.panel.inspector.focus',
  focusResizeHandle: 'resize.handle.focus',
  moveSelectedBlockUp: 'editor.block.move-up',
  moveSelectedBlockDown: 'editor.block.move-down',
} as const

export type EditorShortcutCommandId =
  (typeof EDITOR_SHORTCUT_COMMAND_IDS)[keyof typeof EDITOR_SHORTCUT_COMMAND_IDS]

export interface EditorShortcutRuntimeContext {
  save: () => void
  undo: () => void
  redo: () => void
  canDeleteSelectedBlock: boolean
  deleteSelectedBlock: () => boolean
  canDeselectSelectedBlock: boolean
  deselectSelectedBlock: () => boolean
  togglePreview: () => void
  duplicateSelectedBlock: () => boolean
  openInsertDialog: () => boolean
  openShortcutsHelp: () => boolean
  focusBlocksPanel: () => boolean
  focusStructurePanel: () => boolean
  focusInspectorPanel: () => boolean
  focusResizeHandle: () => boolean
  moveSelectedBlockUp: () => boolean
  moveSelectedBlockDown: () => boolean
}

interface LeaderRuntimeDefinition {
  commandId: EditorShortcutCommandId
  legacyId: LeaderShortcutCommandId
  run: (context: EditorShortcutRuntimeContext) => boolean | void
  failureMessage: string
}

const LEADER_RUNTIME_DEFINITIONS: readonly LeaderRuntimeDefinition[] = [
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.togglePreview,
    legacyId: 'toggle-preview',
    run: (context) => {
      context.togglePreview()
    },
    failureMessage: 'Cannot toggle preview',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.duplicateSelectedBlock,
    legacyId: 'duplicate-selected-block',
    run: (context) => context.duplicateSelectedBlock(),
    failureMessage: 'Select a block to duplicate',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.openInsertDialog,
    legacyId: 'open-insert-dialog',
    run: (context) => context.openInsertDialog(),
    failureMessage: 'Cannot open insert dialog',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.openShortcutsHelp,
    legacyId: 'open-shortcuts-help',
    run: (context) => context.openShortcutsHelp(),
    failureMessage: 'Cannot open shortcuts help',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.focusBlocksPanel,
    legacyId: 'focus-blocks-panel',
    run: (context) => context.focusBlocksPanel(),
    failureMessage: 'Blocks panel unavailable',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.focusStructurePanel,
    legacyId: 'focus-structure-panel',
    run: (context) => context.focusStructurePanel(),
    failureMessage: 'Structure panel unavailable',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.focusInspectorPanel,
    legacyId: 'focus-inspector-panel',
    run: (context) => context.focusInspectorPanel(),
    failureMessage: 'Inspector panel unavailable',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.focusResizeHandle,
    legacyId: 'focus-resize-handle',
    run: (context) => context.focusResizeHandle(),
    failureMessage: 'Resize handle unavailable',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.moveSelectedBlockUp,
    legacyId: 'move-selected-block-up',
    run: (context) => context.moveSelectedBlockUp(),
    failureMessage: 'Cannot move block up',
  },
  {
    commandId: EDITOR_SHORTCUT_COMMAND_IDS.moveSelectedBlockDown,
    legacyId: 'move-selected-block-down',
    run: (context) => context.moveSelectedBlockDown(),
    failureMessage: 'Cannot move block down',
  },
]

const LEADER_IDLE_TOKEN_BY_COMMAND_ID = new Map<CommandId, string>()

function getCoreShortcut(shortcutId: CoreShortcutId): CoreShortcutConfig {
  const shortcut = CORE_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing core shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

function getLeaderShortcut(shortcutId: LeaderShortcutCommandId): LeaderShortcutCommandConfig {
  const shortcut = LEADER_SHORTCUTS_BY_ID.get(shortcutId)
  if (!shortcut) {
    throw new Error(`Missing leader shortcut config for "${shortcutId}"`)
  }
  return shortcut
}

function toCoreBindingKeys(shortcutId: CoreShortcutId): string[] {
  const shortcut = getCoreShortcut(shortcutId)
  return toShortcutStrokesFromBindings(shortcut.bindings)
}

function toLeaderFollowupStrokes(key: string): string[] {
  const normalized = key.toLowerCase()
  if (normalized === '?') {
    return ['?', 'shift+?', 'shift+/']
  }
  return [normalized]
}

function toLeaderBindingKeys(shortcutId: LeaderShortcutCommandId): string[] {
  const shortcut = getLeaderShortcut(shortcutId)
  return [...new Set(
    shortcut.keys.flatMap((key) => {
      return toLeaderFollowupStrokes(key).map((followupStroke) => {
        return `${LEADER_ACTIVATION_STROKE} ${followupStroke}`
      })
    }),
  )]
}

function toCoreCommandDefinitions(): CommandDefinition<EditorShortcutRuntimeContext>[] {
  const save = getCoreShortcut('save')
  const undo = getCoreShortcut('undo')
  const redo = getCoreShortcut('redo')
  const remove = getCoreShortcut('delete-selected-block')
  const deselect = getCoreShortcut('deselect-selected-block')

  return [
    {
      id: EDITOR_SHORTCUT_COMMAND_IDS.save,
      label: save.action,
      category: 'Core',
      run: (context) => {
        context.save()
        return { ok: true }
      },
    },
    {
      id: EDITOR_SHORTCUT_COMMAND_IDS.undo,
      label: undo.action,
      category: 'Core',
      run: (context) => {
        context.undo()
        return { ok: true }
      },
    },
    {
      id: EDITOR_SHORTCUT_COMMAND_IDS.redo,
      label: redo.action,
      category: 'Core',
      run: (context) => {
        context.redo()
        return { ok: true }
      },
    },
    {
      id: EDITOR_SHORTCUT_COMMAND_IDS.deleteSelectedBlock,
      label: remove.action,
      category: 'Core',
      run: (context) => {
        if (!context.deleteSelectedBlock()) {
          return { ok: false, message: 'No selected block to delete' }
        }
        return { ok: true }
      },
    },
    {
      id: EDITOR_SHORTCUT_COMMAND_IDS.deselectSelectedBlock,
      label: deselect.action,
      category: 'Core',
      run: (context) => {
        if (!context.deselectSelectedBlock()) {
          return { ok: false, message: 'No selected block to deselect' }
        }
        return { ok: true }
      },
    },
  ]
}

function toLeaderCommandDefinitions(): CommandDefinition<EditorShortcutRuntimeContext>[] {
  return LEADER_RUNTIME_DEFINITIONS.map((definition) => {
    const shortcut = getLeaderShortcut(definition.legacyId)
    LEADER_IDLE_TOKEN_BY_COMMAND_ID.set(definition.commandId, shortcut.idleToken)

    return {
      id: definition.commandId,
      label: shortcut.action,
      category: 'Leader',
      run: (context) => {
        const output = definition.run(context)
        const ok = output === undefined ? true : output
        if (!ok) {
          return {
            ok: false,
            message: definition.failureMessage,
          }
        }
        return {
          ok: true,
          message: shortcut.successMessage,
        }
      },
      metadata: {
        legacyLeaderId: definition.legacyId,
        idleToken: shortcut.idleToken,
      },
    }
  })
}

function toCoreKeybindingDefinitions(): KeybindingDefinition[] {
  return [
    {
      commandId: EDITOR_SHORTCUT_COMMAND_IDS.save,
      context: 'editor',
      keys: toCoreBindingKeys('save'),
      preventDefault: true,
      display: getCoreShortcut('save').helpKeys,
    },
    {
      commandId: EDITOR_SHORTCUT_COMMAND_IDS.undo,
      context: 'editor',
      keys: toCoreBindingKeys('undo'),
      preventDefault: true,
      display: getCoreShortcut('undo').helpKeys,
    },
    {
      commandId: EDITOR_SHORTCUT_COMMAND_IDS.redo,
      context: 'editor',
      keys: toCoreBindingKeys('redo'),
      preventDefault: true,
      display: getCoreShortcut('redo').helpKeys,
    },
    {
      commandId: EDITOR_SHORTCUT_COMMAND_IDS.deleteSelectedBlock,
      context: 'editor',
      keys: toCoreBindingKeys('delete-selected-block'),
      preventDefault: true,
      when: (context) => (context as EditorShortcutRuntimeContext).canDeleteSelectedBlock,
      display: getCoreShortcut('delete-selected-block').helpKeys,
    },
    {
      commandId: EDITOR_SHORTCUT_COMMAND_IDS.deselectSelectedBlock,
      context: 'editor',
      keys: toCoreBindingKeys('deselect-selected-block'),
      when: (context) => (context as EditorShortcutRuntimeContext).canDeselectSelectedBlock,
      display: getCoreShortcut('deselect-selected-block').helpKeys,
    },
  ]
}

function toLeaderKeybindingDefinitions(): KeybindingDefinition[] {
  return LEADER_RUNTIME_DEFINITIONS.map((definition) => {
    const shortcut = getLeaderShortcut(definition.legacyId)
    return {
      commandId: definition.commandId,
      context: 'global',
      keys: toLeaderBindingKeys(definition.legacyId),
      preventDefault: true,
      display: shortcut.helpKeys,
    }
  })
}

const EDITOR_SHORTCUT_COMMANDS: readonly CommandDefinition<EditorShortcutRuntimeContext>[] = [
  ...toCoreCommandDefinitions(),
  ...toLeaderCommandDefinitions(),
]

const EDITOR_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition[] = [
  ...toCoreKeybindingDefinitions(),
  ...toLeaderKeybindingDefinitions(),
]

export const EDITOR_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<EditorShortcutRuntimeContext> =
  defineShortcutRegistry({
    commands: EDITOR_SHORTCUT_COMMANDS,
    keybindings: EDITOR_SHORTCUT_KEYBINDINGS,
  })

assertValidShortcutRegistry(EDITOR_SHORTCUT_REGISTRY)

const KEYBINDINGS_BY_COMMAND_ID = new Map<CommandId, KeybindingDefinition[]>()
for (const binding of EDITOR_SHORTCUT_REGISTRY.keybindings) {
  const current = KEYBINDINGS_BY_COMMAND_ID.get(binding.commandId)
  if (current) {
    current.push(binding)
  } else {
    KEYBINDINGS_BY_COMMAND_ID.set(binding.commandId, [binding])
  }
}

export function getLeaderIdleTokensForCommandIds(commandIds: readonly CommandId[]): string[] {
  const tokens: string[] = []
  for (const commandId of commandIds) {
    const token = LEADER_IDLE_TOKEN_BY_COMMAND_ID.get(commandId)
    if (!token || tokens.includes(token)) {
      continue
    }
    tokens.push(token)
  }
  return tokens
}

export function getEditorShortcutRegistry(): ShortcutRegistryDefinition<EditorShortcutRuntimeContext> {
  return EDITOR_SHORTCUT_REGISTRY
}

export function getShortcutDisplaysForCommandId(commandId: CommandId): string[] {
  const bindings = KEYBINDINGS_BY_COMMAND_ID.get(commandId)
  if (!bindings) {
    return []
  }

  const displays = bindings
    .map((binding) => binding.display)
    .filter((display): display is string => typeof display === 'string' && display.length > 0)

  return [...new Set(displays)]
}

export function getShortcutDisplayForCommandId(commandId: CommandId): string {
  const displays = getShortcutDisplaysForCommandId(commandId)
  if (displays.length === 0) {
    throw new Error(`Missing shortcut display for command "${commandId}"`)
  }
  return displays.join(' / ')
}
