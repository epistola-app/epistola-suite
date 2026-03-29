import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  LeaderModeController,
  type LeaderModeState,
  type LeaderModeControllerOptions,
} from "./leader-controller.js";
import type { CommandId } from "./foundation.js";

function createTestController(overrides: Partial<LeaderModeControllerOptions> = {}) {
  const stateChanges: LeaderModeState[] = [];
  const cancelActiveChord = vi.fn();
  const blurEditingTarget = vi.fn();

  const options: LeaderModeControllerOptions = {
    timing: { idleHideMs: 1600, resultHideMs: 700, messageClearMs: 180 },
    getIdleTokens: (commandIds) => commandIds.map((id) => id.split(".").pop() ?? ""),
    fallbackTokens: ["P", "D", "A", "?"],
    onStateChange: (state) => stateChanges.push({ ...state }),
    cancelActiveChord,
    blurEditingTarget,
    ...overrides,
  };

  const controller = new LeaderModeController(options);
  return { controller, stateChanges, cancelActiveChord, blurEditingTarget };
}

describe("LeaderModeController", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("showAwaiting", () => {
    it("sets visible state with idle tokens from command ids", () => {
      const { controller, stateChanges, blurEditingTarget } = createTestController();

      controller.showAwaiting(["editor.preview.toggle", "editor.block.duplicate"] as CommandId[]);

      expect(stateChanges).toHaveLength(1);
      expect(stateChanges[0]).toEqual({
        visible: true,
        status: "idle",
        message: "Waiting: toggle duplicate",
      });
      expect(blurEditingTarget).toHaveBeenCalled();
    });

    it("uses fallback tokens when getIdleTokens returns empty", () => {
      const { controller, stateChanges } = createTestController({
        getIdleTokens: () => [],
      });

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);

      expect(stateChanges[0]?.message).toBe("Waiting: P D A ?");
    });

    it("cancels active chord and hides after idle timeout", () => {
      const { controller, cancelActiveChord, stateChanges } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      expect(stateChanges).toHaveLength(1);

      vi.advanceTimersByTime(1600);
      expect(cancelActiveChord).toHaveBeenCalled();
      // Should have emitted a hide state (visible: false)
      expect(stateChanges.length).toBeGreaterThan(1);
      expect(stateChanges[stateChanges.length - 1]?.visible).toBe(false);
    });
  });

  describe("handleChordCancelled", () => {
    it("shows error result for mismatch cancellation", () => {
      const { controller, stateChanges } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      controller.handleChordCancelled("mismatch");

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(true);
      expect(last?.status).toBe("error");
      expect(last?.message).toBe("Unknown leader command");
    });

    it("hides for cancel-key cancellation", () => {
      const { controller, stateChanges } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      controller.handleChordCancelled("cancel-key");

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(false);
    });

    it("hides for timeout cancellation", () => {
      const { controller, stateChanges } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      controller.handleChordCancelled("timeout");

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(false);
    });
  });

  describe("handleCommandExecution", () => {
    it("shows success result for synchronous ok execution", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "ok", message: "Preview toggled" },
        Promise.resolve({ status: "ok", message: "Preview toggled" }),
      );

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(true);
      expect(last?.status).toBe("success");
      expect(last?.message).toBe("Preview toggled");
    });

    it("shows error result for synchronous rejected execution", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "rejected", message: "Cannot toggle" },
        Promise.resolve({ status: "rejected", message: "Cannot toggle" }),
      );

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(true);
      expect(last?.status).toBe("error");
      expect(last?.message).toBe("Cannot toggle");
    });

    it('shows "Running command..." for pending then resolves on completion', async () => {
      const { controller, stateChanges } = createTestController();
      let resolveCompletion!: (result: { status: string; message: string }) => void;
      const completion = new Promise<any>((resolve) => {
        resolveCompletion = resolve;
      });

      controller.handleCommandExecution({ status: "pending" }, completion);

      expect(stateChanges[stateChanges.length - 1]).toEqual({
        visible: true,
        status: "idle",
        message: "Running command...",
      });

      resolveCompletion({ status: "ok", message: "Done" });
      // Flush microtask queue for the .then() handler
      await Promise.resolve();
      await Promise.resolve();

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.status).toBe("success");
      expect(last?.message).toBe("Done");
    });

    it("hides for cancelled execution", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "cancelled" },
        Promise.resolve({ status: "cancelled" }),
      );

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(false);
    });

    it("uses default message when ok result has no message", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution({ status: "ok" }, Promise.resolve({ status: "ok" }));

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.message).toBe("Done");
    });

    it('uses "Command rejected" when rejected result has no message', () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "rejected" },
        Promise.resolve({ status: "rejected" }),
      );

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.message).toBe("Command rejected");
    });

    it('uses "Command failed" when error result has no message', () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution({ status: "error" }, Promise.resolve({ status: "error" }));

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.message).toBe("Command failed");
    });
  });

  describe("result display timing", () => {
    it("hides after resultHideMs for success", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "ok", message: "Done" },
        Promise.resolve({ status: "ok", message: "Done" }),
      );

      const afterResult = stateChanges.length;
      vi.advanceTimersByTime(700);

      expect(stateChanges.length).toBeGreaterThan(afterResult);
      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(false);
    });

    it("clears message after messageClearMs following hide", () => {
      const { controller, stateChanges } = createTestController();

      controller.handleCommandExecution(
        { status: "ok", message: "Done" },
        Promise.resolve({ status: "ok", message: "Done" }),
      );

      // Trigger the result hide
      vi.advanceTimersByTime(700);
      // Trigger the message clear
      vi.advanceTimersByTime(180);

      const last = stateChanges[stateChanges.length - 1];
      expect(last?.visible).toBe(false);
      expect(last?.status).toBe("idle");
      expect(last?.message).toBe("");
    });
  });

  describe("dispose", () => {
    it("clears all timers without emitting state changes", () => {
      const { controller, stateChanges } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      const countBefore = stateChanges.length;

      controller.dispose();

      // Advancing timers should not trigger any further state changes
      vi.advanceTimersByTime(5000);
      expect(stateChanges).toHaveLength(countBefore);
    });
  });

  describe("state transitions", () => {
    it("new showAwaiting resets previous timers", () => {
      const { controller, stateChanges, cancelActiveChord } = createTestController();

      controller.showAwaiting(["editor.preview.toggle"] as CommandId[]);
      vi.advanceTimersByTime(800); // halfway through idle timeout

      // New chord starts — should reset the timer
      controller.showAwaiting(["editor.block.duplicate"] as CommandId[]);
      vi.advanceTimersByTime(800); // another 800ms (total 1600 from first, 800 from second)

      // Should NOT have timed out yet since second call reset the timer
      // cancelActiveChord should not have been called yet
      expect(cancelActiveChord).not.toHaveBeenCalled();

      vi.advanceTimersByTime(800); // now 1600ms from second call
      expect(cancelActiveChord).toHaveBeenCalledTimes(1);
    });
  });
});
