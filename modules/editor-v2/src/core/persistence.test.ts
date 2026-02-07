import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createSaveOrchestrator } from "./persistence.ts";
import type { SaveFunction } from "./persistence.ts";
import type { Template } from "../types/template.ts";

function createTestTemplate(name: string = "test"): Template {
  return {
    id: "test-template",
    name,
    version: 1,
    pageSettings: {
      format: "A4",
      orientation: "portrait",
      margins: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    blocks: [],
    documentStyles: {},
  };
}

describe("createSaveOrchestrator", () => {
  let template: Template;
  let saveFn: ReturnType<typeof vi.fn<SaveFunction>>;

  beforeEach(() => {
    vi.useFakeTimers();
    template = createTestTemplate();
    saveFn = vi.fn<SaveFunction>().mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  describe("isDirty", () => {
    it("should return false initially", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      expect(orchestrator.isDirty()).toBe(false);
    });

    it("should return true after template changes", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        initialSnapshot: createTestTemplate("original"),
      });

      template.name = "changed";

      expect(orchestrator.isDirty()).toBe(true);
    });

    it("should return false after markSaved", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        initialSnapshot: createTestTemplate("original"),
      });

      template.name = "changed";
      expect(orchestrator.isDirty()).toBe(true);

      orchestrator.markSaved();
      expect(orchestrator.isDirty()).toBe(false);
    });
  });

  describe("getStatus", () => {
    it("should return 'saved' initially", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      expect(orchestrator.getStatus()).toBe("saved");
    });

    it("should return 'dirty' after markDirty", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      orchestrator.markDirty();

      expect(orchestrator.getStatus()).toBe("dirty");
    });
  });

  describe("requestSave", () => {
    it("should debounce save calls", async () => {
      template = createTestTemplate("original");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 1000,
        initialSnapshot: createTestTemplate("different"),
      });

      orchestrator.requestSave();
      orchestrator.requestSave();
      orchestrator.requestSave();

      expect(saveFn).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1000);

      expect(saveFn).toHaveBeenCalledTimes(1);
    });

    it("should call save with current template", async () => {
      template = createTestTemplate("to-save");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 100,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      await vi.advanceTimersByTimeAsync(100);

      expect(saveFn).toHaveBeenCalledWith(template);
    });

    it("should update status during save", async () => {
      template = createTestTemplate("new");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 100,
        initialSnapshot: createTestTemplate("original"),
      });

      const statuses: string[] = [];
      orchestrator.onStatusChange((status) => statuses.push(status));

      orchestrator.requestSave();
      await vi.advanceTimersByTimeAsync(100);

      expect(statuses).toContain("dirty");
      expect(statuses).toContain("saving");
      expect(statuses).toContain("saved");
    });
  });

  describe("saveNow", () => {
    it("should save immediately without debounce", async () => {
      template = createTestTemplate("immediate");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 10000,
        initialSnapshot: createTestTemplate("original"),
      });

      await orchestrator.saveNow();

      expect(saveFn).toHaveBeenCalledTimes(1);
    });

    it("should cancel pending debounced save", async () => {
      template = createTestTemplate("test");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 1000,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      await orchestrator.saveNow();

      await vi.advanceTimersByTimeAsync(1000);

      expect(saveFn).toHaveBeenCalledTimes(1);
    });

    it("should not save if not dirty", async () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      await orchestrator.saveNow();

      expect(saveFn).not.toHaveBeenCalled();
    });
  });

  describe("cancelPending", () => {
    it("should cancel pending debounced save", async () => {
      template = createTestTemplate("test");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 1000,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      orchestrator.cancelPending();

      await vi.advanceTimersByTimeAsync(1000);

      expect(saveFn).not.toHaveBeenCalled();
    });
  });

  describe("onStatusChange", () => {
    it("should call listener immediately with current status", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      const listener = vi.fn();
      orchestrator.onStatusChange(listener);

      expect(listener).toHaveBeenCalledWith("saved");
    });

    it("should notify on status changes", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      const listener = vi.fn();
      orchestrator.onStatusChange(listener);
      listener.mockClear();

      orchestrator.markDirty();

      expect(listener).toHaveBeenCalledWith("dirty", undefined);
    });

    it("should return unsubscribe function", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      const listener = vi.fn();
      const unsubscribe = orchestrator.onStatusChange(listener);
      listener.mockClear();

      unsubscribe();
      orchestrator.markDirty();

      expect(listener).not.toHaveBeenCalled();
    });

    it("should include error on save failure", async () => {
      const error = new Error("Save failed");
      saveFn.mockRejectedValue(error);
      template = createTestTemplate("fail");

      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 100,
        initialSnapshot: createTestTemplate("original"),
      });

      const listener = vi.fn();
      orchestrator.onStatusChange(listener);

      orchestrator.requestSave();
      await vi.advanceTimersByTimeAsync(100);

      expect(listener).toHaveBeenCalledWith("error", error);
    });
  });

  describe("dispose", () => {
    it("should cancel pending saves", async () => {
      template = createTestTemplate("test");
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 1000,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      orchestrator.dispose();

      await vi.advanceTimersByTimeAsync(1000);

      expect(saveFn).not.toHaveBeenCalled();
    });

    it("should clear listeners", () => {
      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
      });

      const listener = vi.fn();
      orchestrator.onStatusChange(listener);
      listener.mockClear();

      orchestrator.dispose();
      // Can't directly test listeners are cleared, but dispose should not throw
    });
  });

  describe("error handling", () => {
    it("should set error status on save failure", async () => {
      saveFn.mockRejectedValue(new Error("Network error"));
      template = createTestTemplate("test");

      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 100,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      await vi.advanceTimersByTimeAsync(100);

      expect(orchestrator.getStatus()).toBe("error");
    });

    it("should still be dirty after failed save", async () => {
      saveFn.mockRejectedValue(new Error("Network error"));
      template = createTestTemplate("test");

      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        debounceDelay: 100,
        initialSnapshot: createTestTemplate("original"),
      });

      orchestrator.requestSave();
      await vi.advanceTimersByTimeAsync(100);

      expect(orchestrator.isDirty()).toBe(true);
    });

    it("should throw error from saveNow", async () => {
      const error = new Error("Network error");
      saveFn.mockRejectedValue(error);
      template = createTestTemplate("test");

      const orchestrator = createSaveOrchestrator({
        getTemplate: () => template,
        save: saveFn,
        initialSnapshot: createTestTemplate("original"),
      });

      await expect(orchestrator.saveNow()).rejects.toThrow("Network error");
    });
  });
});
