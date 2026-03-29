import { describe, expect, it } from "vitest";
import {
  INSERT_DIALOG_SHORTCUT_COMMAND_IDS,
  getInsertDialogShortcutRegistry,
  type InsertDialogShortcutRuntimeContext,
} from "./insert-dialog-runtime.js";

function createExecutionContext() {
  return { signal: new AbortController().signal };
}

async function runCommand(commandId: string, context: InsertDialogShortcutRuntimeContext) {
  const registry = getInsertDialogShortcutRegistry();
  const command = registry.commands.find((entry) => entry.id === commandId);
  if (!command) {
    throw new Error(`Missing command "${commandId}"`);
  }
  return Promise.resolve(command.run(context, createExecutionContext()));
}

function baseContext(
  overrides: Partial<InsertDialogShortcutRuntimeContext> = {},
): InsertDialogShortcutRuntimeContext {
  return {
    hasPlacementMode: true,
    hasSelectionMode: false,
    isDocumentContext: true,
    optionCount: 0,
    highlight: 0,
    closeOrBack: () => {},
    selectMode: () => {},
    setHighlight: () => {},
    selectOption: () => {},
    setOptionOutOfRange: () => {},
    ...overrides,
  };
}

describe("insert dialog shortcut runtime", () => {
  it("defines insertDialog-context keybindings including quick-select", () => {
    const registry = getInsertDialogShortcutRegistry();

    expect(registry.keybindings.length).toBeGreaterThan(0);
    expect(registry.keybindings.every((binding) => binding.context === "insertDialog")).toBe(true);
    expect(
      registry.keybindings.some(
        (binding) => binding.commandId === INSERT_DIALOG_SHORTCUT_COMMAND_IDS.closeOrBack,
      ),
    ).toBe(true);
    expect(
      registry.keybindings.some(
        (binding) => binding.commandId === "insertDialog.quick-select.key-1",
      ),
    ).toBe(true);
  });

  it("runs placement and close commands through context callbacks", async () => {
    let selectedMode: string | null = null;
    let closeCount = 0;

    const context = baseContext({
      selectMode: (mode) => {
        selectedMode = mode;
      },
      closeOrBack: () => {
        closeCount += 1;
      },
    });

    await runCommand(INSERT_DIALOG_SHORTCUT_COMMAND_IDS.modeStart, context);
    expect(selectedMode).toBe("start");

    await runCommand(INSERT_DIALOG_SHORTCUT_COMMAND_IDS.closeOrBack, context);
    expect(closeCount).toBe(1);
  });

  it("navigates options with wrap-around behavior", async () => {
    const context = baseContext({
      hasPlacementMode: false,
      hasSelectionMode: true,
      optionCount: 3,
      highlight: 3,
      setHighlight: (index) => {
        context.highlight = index;
      },
    });

    await runCommand(INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigateNext, context);
    expect(context.highlight).toBe(1);

    await runCommand(INSERT_DIALOG_SHORTCUT_COMMAND_IDS.navigatePrevious, context);
    expect(context.highlight).toBe(3);
  });

  it("reports out-of-range quick-select options", async () => {
    let outOfRangeCount = 0;
    const context = baseContext({
      hasPlacementMode: false,
      hasSelectionMode: true,
      optionCount: 2,
      setOptionOutOfRange: () => {
        outOfRangeCount += 1;
      },
    });

    const result = await runCommand("insertDialog.quick-select.key-9", context);
    expect(result.ok).toBe(false);
    expect(result.message).toBe("Option out of range");
    expect(outOfRangeCount).toBe(1);
  });
});
