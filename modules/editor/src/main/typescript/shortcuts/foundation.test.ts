import { describe, expect, it } from "vitest";
import {
  assertValidShortcutRegistry,
  defineShortcutRegistry,
  formatShortcutRegistryIssues,
  isShortcutContextId,
  isValidCommandId,
  type KeybindingDefinition,
  validateShortcutRegistry,
} from "./foundation.js";
import {
  EDITOR_SHORTCUT_COMMAND_IDS,
  getEditorShortcutRegistry,
  type EditorShortcutRuntimeContext,
} from './editor-runtime.js'

describe("shortcut foundation", () => {
  it("accepts documented command id namespaces", () => {
    expect(isValidCommandId("editor.preview.toggle")).toBe(true);
    expect(isValidCommandId("text.mark.bold")).toBe(true);
    expect(isValidCommandId("insertDialog.open")).toBe(true);
    expect(isValidCommandId("resize.preview.grow")).toBe(true);
    expect(isValidCommandId("plugin.ai.assist")).toBe(true);
  });

  it("rejects invalid command id shapes", () => {
    expect(isValidCommandId("editor")).toBe(false);
    expect(isValidCommandId("plugin.ai")).toBe(false);
    expect(isValidCommandId("unknown.preview.toggle")).toBe(false);
    expect(isValidCommandId("editor.Preview.toggle")).toBe(false);
    expect(isValidCommandId("editor..toggle")).toBe(false);
  });

  it("validates known shortcut contexts", () => {
    expect(isShortcutContextId("global")).toBe(true);
    expect(isShortcutContextId("insertDialog")).toBe(true);
    expect(isShortcutContextId("resizeHandle")).toBe(true);
    expect(isShortcutContextId("invalid")).toBe(false);
  });

  it("supports type-safe registry creation and successful validation", () => {
    const registry = defineShortcutRegistry({
      commands: [
        {
          id: "editor.document.save",
          label: "Save",
          category: "Core",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "editor.document.save",
          context: "global",
          keys: ["mod+s"],
          display: "{cmd} + S",
        },
      ],
    });

    const result = validateShortcutRegistry(registry);
    expect(result.valid).toBe(true);
    expect(result.issues).toEqual([]);
  });

  it("reports missing command references and empty keys", () => {
    const result = validateShortcutRegistry({
      commands: [],
      keybindings: [
        {
          commandId: "editor.document.save",
          context: "global",
          keys: [],
        },
      ],
    });

    expect(result.valid).toBe(false);
    expect(result.issues.map((issue) => issue.code)).toContain(
      "missing-command-reference",
    );
    expect(result.issues.map((issue) => issue.code)).toContain(
      "empty-binding-keys",
    );
  });

  it("reports invalid match mode when provided", () => {
    const result = validateShortcutRegistry({
      commands: [
        {
          id: "editor.document.save",
          label: "Save",
          category: "Core",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "editor.document.save",
          context: "global",
          keys: ["mod+s"],
          matchBy: "invalid",
        } as unknown as KeybindingDefinition,
      ],
    });

    expect(result.valid).toBe(false);
    expect(result.issues.map((issue) => issue.code)).toContain(
      "invalid-binding-match-by",
    );
  });

  it("reports conflicting unconditional bindings in same context", () => {
    const result = validateShortcutRegistry({
      commands: [
        {
          id: "editor.document.save",
          label: "Save",
          category: "Core",
          run: () => ({ ok: true }),
        },
        {
          id: "editor.document.submit",
          label: "Submit",
          category: "Core",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "editor.document.save",
          context: "global",
          keys: ["mod+s"],
        },
        {
          commandId: "editor.document.submit",
          context: "global",
          keys: ["mod+s"],
        },
      ],
    });

    expect(result.valid).toBe(false);
    expect(result.issues.map((issue) => issue.code)).toContain(
      "binding-conflict",
    );
  });

  it("allows same key when all bindings are conditional", () => {
    const result = validateShortcutRegistry({
      commands: [
        {
          id: "editor.document.save",
          label: "Save",
          category: "Core",
          run: () => ({ ok: true }),
        },
        {
          id: "editor.document.submit",
          label: "Submit",
          category: "Core",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "editor.document.save",
          context: "global",
          keys: ["mod+s"],
          when: () => true,
        },
        {
          commandId: "editor.document.submit",
          context: "global",
          keys: ["mod+s"],
          when: () => false,
        },
      ],
    });

    expect(result.valid).toBe(true);
  });

  it("throws from assert helper with formatted message", () => {
    expect(() => {
      assertValidShortcutRegistry({
        commands: [],
        keybindings: [
          {
            commandId: "editor.invalid.command",
            context: "global",
            keys: [],
          },
        ],
      });
    }).toThrow("Invalid shortcut registry");
  });

  it("formats validation output in a readable list", () => {
    const formatted = formatShortcutRegistryIssues([
      {
        code: "empty-binding-keys",
        message: "Binding at index 0 is empty",
      },
      {
        code: "missing-command-reference",
        message: "Binding at index 0 references missing command",
      },
    ]);

    expect(formatted).toContain(
      "1. [empty-binding-keys] Binding at index 0 is empty",
    );
    expect(formatted).toContain(
      "2. [missing-command-reference] Binding at index 0 references missing command",
    );
  });

  it('validates runtime registry and runs the real toggle-preview command', async () => {
    const runtimeRegistry = getEditorShortcutRegistry()
    const validation = validateShortcutRegistry(runtimeRegistry);
    expect(validation.valid).toBe(true);

    const command = runtimeRegistry.commands.find(
      (entry) => entry.id === EDITOR_SHORTCUT_COMMAND_IDS.togglePreview,
    );
    expect(command).toBeDefined();

    let toggleCount = 0;
    const runtimeContext: EditorShortcutRuntimeContext = {
      save: () => {},
      undo: () => {},
      redo: () => {},
      canDeleteSelectedBlock: false,
      deleteSelectedBlock: () => false,
      canDeselectSelectedBlock: false,
      deselectSelectedBlock: () => false,
      togglePreview: () => {
        toggleCount += 1
      },
      duplicateSelectedBlock: () => false,
      openInsertDialog: () => false,
      openShortcutsHelp: () => false,
      focusBlocksPanel: () => false,
      focusStructurePanel: () => false,
      focusInspectorPanel: () => false,
      focusResizeHandle: () => false,
      moveSelectedBlockUp: () => false,
      moveSelectedBlockDown: () => false,
    }

    const result = await Promise.resolve(
      command!.run(runtimeContext, { signal: new AbortController().signal }),
    );

    expect(toggleCount).toBe(1);
    expect(result.ok).toBe(true);
    expect(result.message).toBeTruthy();
  });
});
