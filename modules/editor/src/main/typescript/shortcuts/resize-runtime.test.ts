import { describe, expect, it } from "vitest";
import {
  RESIZE_SHORTCUT_COMMAND_IDS,
  getResizeShortcutRegistry,
  type ResizeShortcutRuntimeContext,
} from "./resize-runtime.js";

function createExecutionContext() {
  return { signal: new AbortController().signal };
}

async function runCommand(commandId: string, context: ResizeShortcutRuntimeContext) {
  const registry = getResizeShortcutRegistry();
  const command = registry.commands.find((entry) => entry.id === commandId);
  if (!command) {
    throw new Error(`Missing command "${commandId}"`);
  }
  return Promise.resolve(command.run(context, createExecutionContext()));
}

describe("resize shortcut runtime", () => {
  it("defines resize-handle keybindings", () => {
    const registry = getResizeShortcutRegistry();

    expect(registry.keybindings.length).toBe(3);
    expect(registry.keybindings.every((binding) => binding.context === "resizeHandle")).toBe(true);
    expect(
      registry.keybindings.find(
        (binding) => binding.commandId === RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth,
      )?.keys,
    ).toEqual(["ArrowLeft"]);
    expect(
      registry.keybindings.find(
        (binding) => binding.commandId === RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth,
      )?.keys,
    ).toEqual(["ArrowRight"]);
  });

  it("runs grow and shrink commands using runtime width operations", async () => {
    const observed: number[] = [];
    const context: ResizeShortcutRuntimeContext = {
      currentWidth: 400,
      minWidth: 200,
      step: 16,
      setWidth: (nextWidth) => {
        observed.push(nextWidth);
      },
      closePreview: () => {},
    };

    await runCommand(RESIZE_SHORTCUT_COMMAND_IDS.growPreviewWidth, context);
    await runCommand(RESIZE_SHORTCUT_COMMAND_IDS.shrinkPreviewWidth, context);

    expect(observed).toEqual([416, 384]);
  });

  it("runs close-at-min-width command through close callback", async () => {
    let closeCount = 0;
    const context: ResizeShortcutRuntimeContext = {
      currentWidth: 200,
      minWidth: 200,
      step: 16,
      setWidth: () => {},
      closePreview: () => {
        closeCount += 1;
      },
    };

    const result = await runCommand(RESIZE_SHORTCUT_COMMAND_IDS.closePreviewWhenMinWidth, context);
    expect(result.ok).toBe(true);
    expect(closeCount).toBe(1);
  });
});
