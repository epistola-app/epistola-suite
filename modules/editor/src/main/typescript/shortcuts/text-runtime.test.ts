import { describe, expect, it } from "vitest";
import {
  TEXT_SHORTCUT_COMMAND_IDS,
  getTextBubbleTitle,
  getTextShortcutBindingsForCommandId,
  getTextShortcutDisplayForCommandId,
  getTextShortcutRegistry,
} from "./text-runtime.js";

describe("text shortcut runtime", () => {
  it("defines text-context keybindings for mark and line-break commands", () => {
    const registry = getTextShortcutRegistry();

    expect(registry.keybindings.length).toBeGreaterThanOrEqual(5);
    expect(registry.keybindings.every((binding) => binding.context === "text")).toBe(true);

    const boldBindings = getTextShortcutBindingsForCommandId(TEXT_SHORTCUT_COMMAND_IDS.bold);
    expect(boldBindings.length).toBe(1);
    expect(boldBindings[0]?.keys).toContain("mod+b");

    const lineBreakBindings = getTextShortcutBindingsForCommandId(
      TEXT_SHORTCUT_COMMAND_IDS.lineBreakShiftEnter,
    );
    expect(lineBreakBindings[0]?.keys).toContain("shift+enter");
  });

  it("exposes shortcut displays from registry bindings", () => {
    expect(getTextShortcutDisplayForCommandId(TEXT_SHORTCUT_COMMAND_IDS.bold)).toBe("{cmd} + B");
    expect(getTextShortcutDisplayForCommandId(TEXT_SHORTCUT_COMMAND_IDS.italic)).toBe("{cmd} + I");
  });

  it("builds bubble titles from binding display values", () => {
    expect(getTextBubbleTitle(TEXT_SHORTCUT_COMMAND_IDS.bold, "Bold")).toBe("Bold (Ctrl/Cmd + B)");
    expect(getTextBubbleTitle(TEXT_SHORTCUT_COMMAND_IDS.underline, "Underline")).toBe(
      "Underline (Ctrl/Cmd + U)",
    );
  });
});
