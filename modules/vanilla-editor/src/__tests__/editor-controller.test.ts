import { describe, it, expect } from "vitest";
import { EditorController } from "../controllers/editor-controller";

describe("EditorController", () => {
  it("declares expected Stimulus targets", () => {
    expect(EditorController.targets).toEqual([
      "undoBtn",
      "redoBtn",
      "saveBtn",
      "saveStatus",
      "themeSelect",
      "dataExampleSelect",
      "blockContainer",
      "blockStylesBtn",
    ]);
  });

  it("declares reactive values for editor state", () => {
    expect(EditorController.values).toEqual({
      dirty: Boolean,
      canUndo: Boolean,
      canRedo: Boolean,
    });
  });
});
