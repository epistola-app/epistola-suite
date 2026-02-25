/**
 * Single source of truth for editor shortcuts.
 *
 * This module stores both runtime bindings and helper UI labels to keep
 * keyboard handling and shortcut documentation in sync.
 */
export interface ShortcutBinding {
  /** Normalized key used for matching (usually `KeyboardEvent.key` lowercased). */
  key: string
  /** Cross-platform modifier: Cmd on macOS, Ctrl on Windows/Linux. */
  mod?: boolean
  /** Shift requirement for the binding. */
  shift?: boolean
  /** Alt/Option requirement for the binding. */
  alt?: boolean
}

export interface LeaderActivationShortcutConfig {
  /** `KeyboardEvent.code` used to activate leader mode (layout-independent). */
  code: string
  /** Whether activation requires Cmd/Ctrl to be held. */
  requiresModifier: boolean
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string
  action: string
}

export interface LeaderShortcutTimeoutConfig {
  idleHideMs: number
  resultHideMs: number
  messageClearMs: number
}

export type LeaderShortcutCommandId =
  | 'toggle-preview'
  | 'duplicate-selected-block'
  | 'open-insert-dialog'
  | 'open-shortcuts-help'
  | 'focus-blocks-panel'
  | 'focus-structure-panel'
  | 'focus-inspector-panel'
  | 'focus-resize-handle'
  | 'move-selected-block-up'
  | 'move-selected-block-down'

export interface LeaderShortcutCommandConfig {
  id: LeaderShortcutCommandId
  /** Follow-up keys accepted after leader activation. */
  keys: readonly string[]
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string
  action: string
  successMessage: string
  /** Token shown in the leader waiting message (for example: P, A, ?, ↑). */
  idleToken: string
}

export interface LeaderShortcutConfig {
  activation: LeaderActivationShortcutConfig
  timeout: LeaderShortcutTimeoutConfig
  commands: readonly LeaderShortcutCommandConfig[]
}

export type CoreShortcutId =
  | 'save'
  | 'undo'
  | 'redo'
  | 'delete-selected-block'
  | 'deselect-selected-block'

export interface CoreShortcutConfig {
  /** Stable id used by runtime handlers and tests. */
  id: CoreShortcutId
  /** Runtime key bindings for this action. */
  bindings: readonly ShortcutBinding[]
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string
  action: string
}

export interface ResizeShortcutDimensionsConfig {
  minWidth: number
  maxWidth: number
  defaultWidth: number
  keyboardStep: number
}

export type ResizeShortcutId =
  | 'grow-preview-width'
  | 'shrink-preview-width'
  | 'close-preview-when-min-width'

export interface ResizeKeyboardShortcutConfig {
  id: ResizeShortcutId
  key: string
  /** Applies only when the preview is already at minimum width. */
  whenAtMinWidth?: boolean
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string
  action: string
}

export interface ResizeShortcutConfig {
  dimensions: ResizeShortcutDimensionsConfig
  keyboard: readonly ResizeKeyboardShortcutConfig[]
}

export type TextShortcutId =
  | 'bold'
  | 'italic'
  | 'underline'
  | 'line-break-shift-enter'
  | 'line-break-mod-enter'

export interface TextShortcutConfig {
  id: TextShortcutId
  bindings: readonly ShortcutBinding[]
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string
  action: string
}

export interface InsertDialogPlacementShortcutConfig {
  document: {
    /** Key used for insert-at-start when selection context is document-level. */
    start: string
    /** Key used for insert-at-end when selection context is document-level. */
    end: string
  }
  selected: {
    /** Key used for insert-after current block selection. */
    after: string
    /** Key used for insert-before current block selection. */
    before: string
    /** Key used for insert-inside current block selection. */
    inside: string
  }
}

export interface InsertDialogNavigationShortcutConfig {
  /** Numeric keys used for quick option selection. */
  quickSelect: readonly string[]
  previous: string
  next: string
  confirm: string
  close: string
}

export interface InsertDialogShortcutConfig {
  placement: InsertDialogPlacementShortcutConfig
  navigation: InsertDialogNavigationShortcutConfig
}

export interface EditorShortcutsConfig {
  leader: LeaderShortcutConfig
  core: readonly CoreShortcutConfig[]
  resize: ResizeShortcutConfig
  text: readonly TextShortcutConfig[]
  insertDialog: InsertDialogShortcutConfig
}

/** Canonical editor shortcut configuration used by runtime and helper UI. */
export const EDITOR_SHORTCUTS_CONFIG = {
  leader: {
    activation: {
      code: 'Space',
      requiresModifier: true,
      helpKeys: '{cmd} + Space',
      action: 'Enter leader mode',
    },
    timeout: {
      idleHideMs: 1600,
      resultHideMs: 700,
      messageClearMs: 180,
    },
    commands: [
      {
        id: 'toggle-preview',
        keys: ['p'],
        helpKeys: 'Leader + P',
        action: 'Preview',
        successMessage: 'Preview toggled',
        idleToken: 'P',
      },
      {
        id: 'duplicate-selected-block',
        keys: ['d'],
        helpKeys: 'Leader + D',
        action: 'Duplicate selected block',
        successMessage: 'Duplicated block',
        idleToken: 'D',
      },
      {
        id: 'open-insert-dialog',
        keys: ['a'],
        helpKeys: 'Leader + A',
        action: 'Open insert block dialog',
        successMessage: 'Insert dialog opened',
        idleToken: 'A',
      },
      {
        id: 'open-shortcuts-help',
        keys: ['?', '/'],
        helpKeys: 'Leader + ? or /',
        action: 'Open shortcuts help',
        successMessage: 'Opened shortcuts help',
        idleToken: '?',
      },
      {
        id: 'focus-blocks-panel',
        keys: ['1'],
        helpKeys: 'Leader + 1',
        action: 'Focus Blocks panel',
        successMessage: 'Focused Blocks panel',
        idleToken: '1',
      },
      {
        id: 'focus-structure-panel',
        keys: ['2'],
        helpKeys: 'Leader + 2',
        action: 'Focus Structure panel',
        successMessage: 'Focused Structure panel',
        idleToken: '2',
      },
      {
        id: 'focus-inspector-panel',
        keys: ['3'],
        helpKeys: 'Leader + 3',
        action: 'Focus Inspector panel',
        successMessage: 'Focused Inspector panel',
        idleToken: '3',
      },
      {
        id: 'focus-resize-handle',
        keys: ['r'],
        helpKeys: 'Leader + R',
        action: 'Focus resize handle',
        successMessage: 'Focused resize handle',
        idleToken: 'R',
      },
      {
        id: 'move-selected-block-up',
        keys: ['arrowup'],
        helpKeys: 'Leader + \u2191',
        action: 'Move selected block up',
        successMessage: 'Moved block up',
        idleToken: '\u2191',
      },
      {
        id: 'move-selected-block-down',
        keys: ['arrowdown'],
        helpKeys: 'Leader + \u2193',
        action: 'Move selected block down',
        successMessage: 'Moved block down',
        idleToken: '\u2193',
      },
    ],
  },
  core: [
    {
      id: 'save',
      bindings: [{ key: 's', mod: true }],
      helpKeys: '{cmd} + S',
      action: 'Save',
    },
    {
      id: 'undo',
      bindings: [{ key: 'z', mod: true, shift: false }],
      helpKeys: '{cmd} + Z',
      action: 'Undo',
    },
    {
      id: 'redo',
      bindings: [
        { key: 'z', mod: true, shift: true },
        { key: 'y', mod: true },
      ],
      helpKeys: '{cmd} + Shift + Z / {cmd} + Y',
      action: 'Redo',
    },
    {
      id: 'delete-selected-block',
      bindings: [{ key: 'delete' }, { key: 'backspace' }],
      helpKeys: 'Delete / Backspace',
      action: 'Delete selected block',
    },
    {
      id: 'deselect-selected-block',
      bindings: [{ key: 'escape' }],
      helpKeys: 'Esc',
      action: 'Deselect selected block',
    },
  ],
  resize: {
    dimensions: {
      minWidth: 200,
      maxWidth: 800,
      defaultWidth: 400,
      keyboardStep: 16,
    },
    keyboard: [
      {
        id: 'grow-preview-width',
        key: 'ArrowLeft',
        helpKeys: '\u2190 (focused handle)',
        action: 'Grow preview width',
      },
      {
        id: 'shrink-preview-width',
        key: 'ArrowRight',
        helpKeys: '\u2192 (focused handle)',
        action: 'Shrink preview width',
      },
      {
        id: 'close-preview-when-min-width',
        key: 'ArrowRight',
        whenAtMinWidth: true,
        helpKeys: '\u2192 at min width',
        action: 'Close preview',
      },
    ],
  },
  text: [
    {
      id: 'bold',
      bindings: [
        { key: 'b', mod: true },
      ],
      helpKeys: '{cmd} + B',
      action: 'Bold',
    },
    {
      id: 'italic',
      bindings: [
        { key: 'i', mod: true },
      ],
      helpKeys: '{cmd} + I',
      action: 'Italic',
    },
    {
      id: 'underline',
      bindings: [
        { key: 'u', mod: true },
      ],
      helpKeys: '{cmd} + U',
      action: 'Underline',
    },
    {
      id: 'line-break-shift-enter',
      bindings: [
        { key: 'enter', shift: true },
      ],
      helpKeys: 'Shift + Enter',
      action: 'Line break',
    },
    {
      id: 'line-break-mod-enter',
      bindings: [
        { key: 'enter', mod: true },
      ],
      helpKeys: '{cmd} + Enter',
      action: 'Line break',
    },
  ],
  insertDialog: {
    placement: {
      document: {
        start: 's',
        end: 'e',
      },
      selected: {
        after: 'a',
        before: 'b',
        inside: 'i',
      },
    },
    navigation: {
      quickSelect: ['1', '2', '3', '4', '5', '6', '7', '8', '9'],
      previous: 'arrowup',
      next: 'arrowdown',
      confirm: 'enter',
      close: 'escape',
    },
  },
} as const satisfies EditorShortcutsConfig
