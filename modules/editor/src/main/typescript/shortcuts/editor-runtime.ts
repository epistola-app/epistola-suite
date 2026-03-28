import { EDITOR_SHORTCUTS_CONFIG } from "../shortcuts-config.js";
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  type CommandDefinition,
  type CommandId,
  type KeybindingDefinition,
  type ShortcutRegistryDefinition,
} from "./foundation.js";
import { withAnyModifiers } from "./key-strokes.js";

// ---------------------------------------------------------------------------
// Command IDs
// ---------------------------------------------------------------------------

export const EDITOR_SHORTCUT_COMMAND_IDS = {
  save: "editor.document.save",
  undo: "editor.history.undo",
  redo: "editor.history.redo",
  deleteSelectedBlock: "editor.block.delete-selected",
  deselectSelectedBlock: "editor.block.deselect",
  togglePreview: "editor.preview.toggle",
  duplicateSelectedBlock: "editor.block.duplicate",
  openInsertDialog: "insertDialog.open",
  openShortcutsHelp: "editor.shortcuts.open-help",
  openDataPreview: "editor.data-example.open-viewer",
  focusBlocksPanel: "editor.panel.blocks.focus",
  focusStructurePanel: "editor.panel.structure.focus",
  focusInspectorPanel: "editor.panel.inspector.focus",
  focusResizeHandle: "resize.handle.focus",
  moveSelectedBlockUp: "editor.block.move-up",
  moveSelectedBlockDown: "editor.block.move-down",
} as const;

export type EditorShortcutCommandId =
  (typeof EDITOR_SHORTCUT_COMMAND_IDS)[keyof typeof EDITOR_SHORTCUT_COMMAND_IDS];

// ---------------------------------------------------------------------------
// Runtime context
// ---------------------------------------------------------------------------

export interface EditorShortcutRuntimeContext {
  save: () => void;
  undo: () => void;
  redo: () => void;
  canDeleteSelectedBlock: boolean;
  deleteSelectedBlock: () => boolean;
  canDeselectSelectedBlock: boolean;
  deselectSelectedBlock: () => boolean;
  togglePreview: () => void;
  duplicateSelectedBlock: () => boolean;
  openInsertDialog: () => boolean;
  openShortcutsHelp: () => boolean;
  openDataPreview: () => boolean;
  focusBlocksPanel: () => boolean;
  focusStructurePanel: () => boolean;
  focusInspectorPanel: () => boolean;
  focusResizeHandle: () => boolean;
  moveSelectedBlockUp: () => boolean;
  moveSelectedBlockDown: () => boolean;
}

// ---------------------------------------------------------------------------
// Leader mode helpers
// ---------------------------------------------------------------------------

const leaderActivation = EDITOR_SHORTCUTS_CONFIG.leader.activation;
const LEADER_STROKE = `${leaderActivation.requiresModifier ? "mod+" : ""}code:${leaderActivation.code.toLowerCase()}`;

function leaderKeys(...followupKeys: readonly string[]): string[] {
  return [
    ...new Set(
      followupKeys.flatMap((key) => {
        const normalized = key.toLowerCase();
        const variants = normalized === "?" ? ["?", "shift+?", "shift+/"] : [normalized];
        return variants.map((v) => `${LEADER_STROKE} ${v}`);
      }),
    ),
  ];
}

function leaderRun(
  action: (context: EditorShortcutRuntimeContext) => boolean | void,
  successMessage: string,
  failureMessage: string,
) {
  return (context: EditorShortcutRuntimeContext) => {
    const output = action(context);
    const ok = output === undefined ? true : output;
    return ok ? { ok: true, message: successMessage } : { ok: false, message: failureMessage };
  };
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

const C = EDITOR_SHORTCUT_COMMAND_IDS;

const EDITOR_SHORTCUT_COMMANDS: readonly CommandDefinition<EditorShortcutRuntimeContext>[] = [
  // Core
  {
    id: C.save,
    label: "Save",
    category: "Core",
    run: (ctx) => {
      ctx.save();
      return { ok: true };
    },
  },
  {
    id: C.undo,
    label: "Undo",
    category: "Core",
    run: (ctx) => {
      ctx.undo();
      return { ok: true };
    },
  },
  {
    id: C.redo,
    label: "Redo",
    category: "Core",
    run: (ctx) => {
      ctx.redo();
      return { ok: true };
    },
  },
  {
    id: C.deleteSelectedBlock,
    label: "Delete selected block",
    category: "Core",
    run: (ctx) =>
      ctx.deleteSelectedBlock()
        ? { ok: true }
        : { ok: false, message: "No selected block to delete" },
  },
  {
    id: C.deselectSelectedBlock,
    label: "Deselect selected block",
    category: "Core",
    run: (ctx) =>
      ctx.deselectSelectedBlock()
        ? { ok: true }
        : { ok: false, message: "No selected block to deselect" },
  },
  // Leader
  {
    id: C.togglePreview,
    label: "Preview",
    category: "Leader",
    run: leaderRun(
      (ctx) => {
        ctx.togglePreview();
      },
      "Preview toggled",
      "Cannot toggle preview",
    ),
    metadata: { idleToken: "P" },
  },
  {
    id: C.duplicateSelectedBlock,
    label: "Duplicate selected block",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.duplicateSelectedBlock(),
      "Duplicated block",
      "Select a block to duplicate",
    ),
    metadata: { idleToken: "D" },
  },
  {
    id: C.openInsertDialog,
    label: "Open insert block dialog",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.openInsertDialog(),
      "Insert dialog opened",
      "Cannot open insert dialog",
    ),
    metadata: { idleToken: "A" },
  },
  {
    id: C.openShortcutsHelp,
    label: "Open shortcuts help",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.openShortcutsHelp(),
      "Opened shortcuts help",
      "Cannot open shortcuts help",
    ),
    metadata: { idleToken: "?" },
  },
  {
    id: C.openDataPreview,
    label: "Open current data example",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.openDataPreview(),
      "Opened data example viewer",
      "Cannot open data example viewer",
    ),
    metadata: { idleToken: "E" },
  },
  {
    id: C.focusBlocksPanel,
    label: "Focus Blocks panel",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.focusBlocksPanel(),
      "Focused Blocks panel",
      "Blocks panel unavailable",
    ),
    metadata: { idleToken: "1" },
  },
  {
    id: C.focusStructurePanel,
    label: "Focus Structure panel",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.focusStructurePanel(),
      "Focused Structure panel",
      "Structure panel unavailable",
    ),
    metadata: { idleToken: "2" },
  },
  {
    id: C.focusInspectorPanel,
    label: "Focus Inspector panel",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.focusInspectorPanel(),
      "Focused Inspector panel",
      "Inspector panel unavailable",
    ),
    metadata: { idleToken: "3" },
  },
  {
    id: C.focusResizeHandle,
    label: "Focus resize handle",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.focusResizeHandle(),
      "Focused resize handle",
      "Resize handle unavailable",
    ),
    metadata: { idleToken: "R" },
  },
  {
    id: C.moveSelectedBlockUp,
    label: "Move selected block up",
    category: "Leader",
    run: leaderRun((ctx) => ctx.moveSelectedBlockUp(), "Moved block up", "Cannot move block up"),
    metadata: { idleToken: "\u2191" },
  },
  {
    id: C.moveSelectedBlockDown,
    label: "Move selected block down",
    category: "Leader",
    run: leaderRun(
      (ctx) => ctx.moveSelectedBlockDown(),
      "Moved block down",
      "Cannot move block down",
    ),
    metadata: { idleToken: "\u2193" },
  },
];

// ---------------------------------------------------------------------------
// Keybindings
// ---------------------------------------------------------------------------

const EDITOR_SHORTCUT_KEYBINDINGS: readonly KeybindingDefinition<EditorShortcutRuntimeContext>[] = [
  // Core
  {
    commandId: C.save,
    context: "editor",
    keys: ["mod+s"],
    preventDefault: true,
    display: "{cmd} + S",
  },
  {
    commandId: C.undo,
    context: "editor",
    keys: ["mod+z"],
    preventDefault: true,
    display: "{cmd} + Z",
  },
  {
    commandId: C.redo,
    context: "editor",
    keys: ["mod+shift+z", "mod+y"],
    preventDefault: true,
    display: "{cmd} + Shift + Z / {cmd} + Y",
  },
  {
    commandId: C.deleteSelectedBlock,
    context: "editor",
    keys: withAnyModifiers("delete", "backspace"),
    preventDefault: true,
    when: (ctx) => ctx.canDeleteSelectedBlock,
    display: "Delete / Backspace",
  },
  {
    commandId: C.deselectSelectedBlock,
    context: "editor",
    keys: withAnyModifiers("escape"),
    when: (ctx) => ctx.canDeselectSelectedBlock,
    display: "Esc",
  },
  // Leader
  {
    commandId: C.togglePreview,
    context: "global",
    keys: leaderKeys("p"),
    preventDefault: true,
    display: "Leader + P",
  },
  {
    commandId: C.duplicateSelectedBlock,
    context: "global",
    keys: leaderKeys("d"),
    preventDefault: true,
    display: "Leader + D",
  },
  {
    commandId: C.openInsertDialog,
    context: "global",
    keys: leaderKeys("a"),
    preventDefault: true,
    display: "Leader + A",
  },
  {
    commandId: C.openShortcutsHelp,
    context: "global",
    keys: leaderKeys("?", "/"),
    preventDefault: true,
    display: "Leader + ? or /",
  },
  {
    commandId: C.openDataPreview,
    context: "global",
    keys: leaderKeys("e"),
    preventDefault: true,
    display: "Leader + E",
  },
  {
    commandId: C.focusBlocksPanel,
    context: "global",
    keys: leaderKeys("1"),
    preventDefault: true,
    display: "Leader + 1",
  },
  {
    commandId: C.focusStructurePanel,
    context: "global",
    keys: leaderKeys("2"),
    preventDefault: true,
    display: "Leader + 2",
  },
  {
    commandId: C.focusInspectorPanel,
    context: "global",
    keys: leaderKeys("3"),
    preventDefault: true,
    display: "Leader + 3",
  },
  {
    commandId: C.focusResizeHandle,
    context: "global",
    keys: leaderKeys("r"),
    preventDefault: true,
    display: "Leader + R",
  },
  {
    commandId: C.moveSelectedBlockUp,
    context: "global",
    keys: leaderKeys("arrowup"),
    preventDefault: true,
    display: "Leader + \u2191",
  },
  {
    commandId: C.moveSelectedBlockDown,
    context: "global",
    keys: leaderKeys("arrowdown"),
    preventDefault: true,
    display: "Leader + \u2193",
  },
];

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

export const EDITOR_SHORTCUT_REGISTRY: ShortcutRegistryDefinition<EditorShortcutRuntimeContext> =
  defineShortcutRegistry({
    commands: EDITOR_SHORTCUT_COMMANDS,
    keybindings: EDITOR_SHORTCUT_KEYBINDINGS,
  });

assertValidShortcutRegistry(EDITOR_SHORTCUT_REGISTRY);

// ---------------------------------------------------------------------------
// Lookups
// ---------------------------------------------------------------------------

const KEYBINDINGS_BY_COMMAND_ID = new Map<
  CommandId,
  KeybindingDefinition<EditorShortcutRuntimeContext>[]
>();
for (const binding of EDITOR_SHORTCUT_REGISTRY.keybindings) {
  const current = KEYBINDINGS_BY_COMMAND_ID.get(binding.commandId);
  if (current) {
    current.push(binding);
  } else {
    KEYBINDINGS_BY_COMMAND_ID.set(binding.commandId, [binding]);
  }
}

const IDLE_TOKENS_BY_COMMAND_ID = new Map<CommandId, string>();
for (const command of EDITOR_SHORTCUT_COMMANDS) {
  const token = (command.metadata as Record<string, unknown> | undefined)?.idleToken;
  if (typeof token === "string") {
    IDLE_TOKENS_BY_COMMAND_ID.set(command.id, token);
  }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

export function getLeaderIdleTokensForCommandIds(commandIds: readonly CommandId[]): string[] {
  const tokens: string[] = [];
  for (const commandId of commandIds) {
    const token = IDLE_TOKENS_BY_COMMAND_ID.get(commandId);
    if (!token || tokens.includes(token)) {
      continue;
    }
    tokens.push(token);
  }
  return tokens;
}

export function getAllLeaderIdleTokens(): string[] {
  return [...new Set(IDLE_TOKENS_BY_COMMAND_ID.values())];
}

export function getEditorShortcutRegistry(): ShortcutRegistryDefinition<EditorShortcutRuntimeContext> {
  return EDITOR_SHORTCUT_REGISTRY;
}

export function getShortcutDisplaysForCommandId(commandId: CommandId): string[] {
  const bindings = KEYBINDINGS_BY_COMMAND_ID.get(commandId);
  if (!bindings) {
    return [];
  }

  const displays = bindings
    .map((binding) => binding.display)
    .filter((display): display is string => typeof display === "string" && display.length > 0);

  return [...new Set(displays)];
}

export function getShortcutDisplayForCommandId(commandId: CommandId): string {
  const displays = getShortcutDisplaysForCommandId(commandId);
  if (displays.length === 0) {
    throw new Error(`Missing shortcut display for command "${commandId}"`);
  }
  return displays.join(" / ");
}
