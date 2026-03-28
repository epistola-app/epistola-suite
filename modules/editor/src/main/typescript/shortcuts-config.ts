/**
 * Behavioral configuration for the editor shortcuts system.
 *
 * Key assignments and command definitions live in the runtime files
 * (editor-runtime.ts, text-runtime.ts, etc.). This module only stores
 * configuration that affects runtime behavior: leader activation/timing,
 * and resize dimensions.
 */

export interface LeaderActivationConfig {
  /** `KeyboardEvent.code` used to activate leader mode (layout-independent). */
  code: string;
  /** Whether activation requires Cmd/Ctrl to be held. */
  requiresModifier: boolean;
  /** Display-only label used in shortcut helper UI. */
  helpKeys: string;
  action: string;
}

export interface LeaderTimeoutConfig {
  idleHideMs: number;
  resultHideMs: number;
  messageClearMs: number;
}

export interface LeaderConfig {
  activation: LeaderActivationConfig;
  timeout: LeaderTimeoutConfig;
}

export interface ResizeDimensionsConfig {
  minWidth: number;
  maxWidth: number;
  defaultWidth: number;
  keyboardStep: number;
}

export interface EditorShortcutsConfig {
  leader: LeaderConfig;
  resize: { dimensions: ResizeDimensionsConfig };
}

/** Canonical editor shortcut configuration used by runtime and helper UI. */
export const EDITOR_SHORTCUTS_CONFIG = {
  leader: {
    activation: {
      code: "Space",
      requiresModifier: true,
      helpKeys: "{cmd} + Space",
      action: "Enter leader mode",
    },
    timeout: {
      idleHideMs: 1600,
      resultHideMs: 700,
      messageClearMs: 180,
    },
  },
  resize: {
    dimensions: {
      minWidth: 200,
      maxWidth: 800,
      defaultWidth: 400,
      keyboardStep: 16,
    },
  },
} as const satisfies EditorShortcutsConfig;
