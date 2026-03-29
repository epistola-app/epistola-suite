import { describe, expect, it } from "vitest";
import {
  definePluginShortcutContribution,
  type PluginShortcutContribution,
} from "./plugin-registry.js";
import {
  getCoreShortcutRegistries,
  validateCoreShortcutRegistriesOnStartup,
  validateShortcutRegistriesOnStartup,
} from "./startup-validation.js";

describe("shortcut startup validation", () => {
  it("validates core registries without plugin contributions", () => {
    expect(getCoreShortcutRegistries().length).toBe(4);
    expect(() => {
      validateCoreShortcutRegistriesOnStartup();
    }).not.toThrow();
  });

  it("accepts valid plugin shortcut contributions on startup", () => {
    const pluginContribution = definePluginShortcutContribution({
      pluginId: "ai",
      commands: [
        {
          id: "plugin.ai.open-panel",
          label: "Open AI panel",
          category: "Plugin",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "plugin.ai.open-panel",
          context: "global",
          keys: ["mod+shift+a"],
          when: () => true,
          display: "Ctrl/Cmd + Shift + A",
        },
      ],
    });

    expect(() => {
      validateShortcutRegistriesOnStartup([pluginContribution]);
    }).not.toThrow();
  });

  it("fails startup validation for invalid plugin bindings", () => {
    const invalidPluginContribution = {
      pluginId: "ai",
      commands: [
        {
          id: "plugin.ai.open-panel",
          label: "Open AI panel",
          category: "Plugin",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "plugin.ai.missing-command",
          context: "global",
          keys: ["mod+shift+a"],
          display: "Ctrl/Cmd + Shift + A",
        },
      ],
    };

    expect(() => {
      validateShortcutRegistriesOnStartup([
        invalidPluginContribution as unknown as PluginShortcutContribution,
      ]);
    }).toThrow("Invalid plugin shortcut contribution");
  });

  it("fails startup validation for plugin conflicts against core shortcuts", () => {
    const conflictingContribution = definePluginShortcutContribution({
      pluginId: "analytics",
      commands: [
        {
          id: "plugin.analytics.override-save",
          label: "Override save",
          category: "Plugin",
          run: () => ({ ok: true }),
        },
      ],
      keybindings: [
        {
          commandId: "plugin.analytics.override-save",
          context: "editor",
          keys: ["mod+s"],
          display: "Ctrl/Cmd + S",
        },
      ],
    });

    expect(() => {
      validateShortcutRegistriesOnStartup([conflictingContribution]);
    }).toThrow("binding-conflict");
  });
});
