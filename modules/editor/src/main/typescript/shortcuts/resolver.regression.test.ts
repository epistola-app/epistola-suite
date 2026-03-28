import { describe, expect, it } from "vitest";
import { defineShortcutRegistry } from "./foundation.js";
import { ShortcutResolver, type ShortcutKeyboardEvent } from "./resolver.js";

function keyboardEvent(
  input: Partial<ShortcutKeyboardEvent> & Pick<ShortcutKeyboardEvent, "key" | "code">,
): ShortcutKeyboardEvent {
  return {
    key: input.key,
    code: input.code,
    ctrlKey: input.ctrlKey ?? false,
    metaKey: input.metaKey ?? false,
    shiftKey: input.shiftKey ?? false,
    altKey: input.altKey ?? false,
    preventDefault: input.preventDefault,
    stopPropagation: input.stopPropagation,
  };
}

describe("shortcut resolver regression", () => {
  function createResolver(): ShortcutResolver<unknown> {
    return new ShortcutResolver(
      defineShortcutRegistry({
        commands: [
          {
            id: "editor.document.save",
            label: "Save",
            category: "Core",
            run: () => ({ ok: true }),
          },
          {
            id: "editor.preview.toggle",
            label: "Toggle preview",
            category: "Leader",
            run: () => ({ ok: true }),
          },
          {
            id: "editor.shortcuts.open",
            label: "Open shortcuts help",
            category: "Leader",
            run: () => ({ ok: true }),
          },
        ],
        keybindings: [
          {
            commandId: "editor.document.save",
            context: "global",
            keys: ["mod+s"],
            preventDefault: true,
          },
          {
            commandId: "editor.preview.toggle",
            context: "global",
            keys: ["mod+code:space p"],
            preventDefault: true,
          },
          {
            commandId: "editor.shortcuts.open",
            context: "global",
            keys: ["mod+code:space /", "mod+code:space ?"],
            preventDefault: true,
          },
        ],
      }),
      {
        chord: {
          timeoutMs: 800,
          cancelKeys: ["escape"],
        },
      },
    );
  }

  it("keeps save as a direct non-chord command", () => {
    const resolver = createResolver();

    const resolution = resolver.resolve({
      event: keyboardEvent({ key: "s", code: "KeyS", ctrlKey: true }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 100,
    });

    expect(resolution.kind).toBe("command");
    if (resolution.kind === "command") {
      expect(resolution.fromChord).toBe(false);
      expect(resolution.match.commandId).toBe("editor.document.save");
    }
  });

  it("keeps leader activation and follow-up command behavior", () => {
    const resolver = createResolver();

    const start = resolver.resolve({
      event: keyboardEvent({ key: " ", code: "Space", ctrlKey: true }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 200,
    });
    expect(start.kind).toBe("chord-awaiting");

    const finish = resolver.resolve({
      event: keyboardEvent({ key: "p", code: "KeyP" }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 250,
    });
    expect(finish.kind).toBe("command");
    if (finish.kind === "command") {
      expect(finish.fromChord).toBe(true);
      expect(finish.match.commandId).toBe("editor.preview.toggle");
    }
  });

  it("keeps leader alias follow-up behavior for shortcuts help", () => {
    const resolver = createResolver();

    resolver.resolve({
      event: keyboardEvent({ key: " ", code: "Space", ctrlKey: true }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 300,
    });

    const alias = resolver.resolve({
      event: keyboardEvent({ key: "/", code: "Slash" }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 320,
    });
    expect(alias.kind).toBe("command");
    if (alias.kind === "command") {
      expect(alias.match.commandId).toBe("editor.shortcuts.open");
    }
  });

  it("keeps escape cancellation behavior during active leader chord", () => {
    const resolver = createResolver();

    resolver.resolve({
      event: keyboardEvent({ key: " ", code: "Space", ctrlKey: true }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 400,
    });

    const cancelled = resolver.resolve({
      event: keyboardEvent({ key: "Escape", code: "Escape" }),
      activeContexts: ["global"],
      runtimeContext: {},
      timestampMs: 420,
    });

    expect(cancelled).toEqual({
      kind: "chord-cancelled",
      reason: "cancel-key",
    });
    expect(resolver.getActiveChordState()).toBeNull();
  });
});
