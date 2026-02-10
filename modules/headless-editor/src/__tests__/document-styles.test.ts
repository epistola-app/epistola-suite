import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";
import type { DocumentStyles } from "../types";

describe("Document Styles", () => {
  describe("updateDocumentStyles", () => {
    it("should update font family", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      expect(editor.getTemplate().documentStyles?.fontFamily).toBe("Arial");
    });

    it("should update multiple styles at once", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontSize: "14px", color: "#333" });

      const styles = editor.getTemplate().documentStyles;
      expect(styles?.fontSize).toBe("14px");
      expect(styles?.color).toBe("#333");
    });

    it("should update background color", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ backgroundColor: "#fff" });

      expect(editor.getTemplate().documentStyles?.backgroundColor).toBe("#fff");
    });

    it("should preserve other styles on partial update", () => {
      const editor = new TemplateEditor();

      // First set multiple styles
      editor.updateDocumentStyles({
        fontFamily: "Arial",
        fontSize: "16px",
        color: "#000",
      });

      // Then update only fontSize
      editor.updateDocumentStyles({ fontSize: "18px" });

      const styles = editor.getTemplate().documentStyles;
      expect(styles?.fontSize).toBe("18px");
      expect(styles?.fontFamily).toBe("Arial");
      expect(styles?.color).toBe("#000");
    });

    it("should create document styles if none exist", () => {
      const editor = new TemplateEditor();

      // Template starts without documentStyles
      expect(editor.getTemplate().documentStyles).toBeUndefined();

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      const styles = editor.getTemplate().documentStyles;
      expect(styles).toBeDefined();
      expect(styles?.fontFamily).toBe("Arial");
    });

    it("should save to history for undo", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      expect(editor.canUndo()).toBe(true);
    });

    it("should revert document styles changes on undo", () => {
      const editor = new TemplateEditor();

      // Set initial font family
      editor.updateDocumentStyles({ fontFamily: "Arial" });

      // Change font family
      editor.updateDocumentStyles({ fontFamily: "Times New Roman" });
      expect(editor.getTemplate().documentStyles?.fontFamily).toBe(
        "Times New Roman",
      );

      // Undo should revert to Arial
      editor.undo();
      expect(editor.getTemplate().documentStyles?.fontFamily).toBe("Arial");
    });

    it("should undo revert multiple styles changes", () => {
      const editor = new TemplateEditor();

      // Set initial styles
      editor.updateDocumentStyles({
        fontFamily: "Arial",
        fontSize: "14px",
        color: "#000",
      });

      // Update multiple styles
      editor.updateDocumentStyles({
        fontSize: "18px",
        color: "#333",
      });
      expect(editor.getTemplate().documentStyles).toEqual({
        fontFamily: "Arial",
        fontSize: "18px",
        color: "#333",
      });

      // Undo should revert to original styles
      editor.undo();
      expect(editor.getTemplate().documentStyles).toEqual({
        fontFamily: "Arial",
        fontSize: "14px",
        color: "#000",
      });
    });

    it("should allow redo after undo", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontFamily: "Arial" });
      editor.undo();

      expect(editor.canRedo()).toBe(true);

      editor.redo();
      expect(editor.getTemplate().documentStyles?.fontFamily).toBe("Arial");
    });

    it("should notify on template change when updating document styles", () => {
      const onTemplateChange = vi.fn();
      const editor = new TemplateEditor({ callbacks: { onTemplateChange } });

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      expect(onTemplateChange).toHaveBeenCalled();
    });

    it("should get document styles via getTemplate", () => {
      const editor = new TemplateEditor();
      const expectedStyles: DocumentStyles = {
        fontFamily: "Arial",
        fontSize: "16px",
        color: "#333",
      };

      editor.updateDocumentStyles(expectedStyles);

      expect(editor.getTemplate().documentStyles).toEqual(expectedStyles);
    });

    it("should update font weight", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontWeight: "bold" });

      expect(editor.getTemplate().documentStyles?.fontWeight).toBe("bold");
    });

    it("should update line height", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ lineHeight: "1.5" });

      expect(editor.getTemplate().documentStyles?.lineHeight).toBe("1.5");
    });

    it("should update letter spacing", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ letterSpacing: "0.5px" });

      expect(editor.getTemplate().documentStyles?.letterSpacing).toBe("0.5px");
    });

    it("should update text align", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ textAlign: "center" });

      expect(editor.getTemplate().documentStyles?.textAlign).toBe("center");
    });
  });

  describe("resolved styles API", () => {
    it("should return resolved document styles", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({
        fontFamily: "Arial",
        color: "#333",
      });

      expect(editor.getResolvedDocumentStyles()).toEqual({
        fontFamily: "Arial",
        color: "#333",
      });
    });

    it("should return empty resolved block styles when block is missing", () => {
      const editor = new TemplateEditor();

      editor.updateDocumentStyles({ fontFamily: "Arial" });

      expect(editor.getResolvedBlockStyles("missing-block")).toEqual({});
    });

    it("should resolve block styles with document fallback and block override", () => {
      const editor = new TemplateEditor();
      const block = editor.addBlock("text");

      expect(block).not.toBeNull();

      editor.updateDocumentStyles({
        fontFamily: "Arial",
        color: "#555",
        backgroundColor: "#eee",
      });

      editor.updateBlock(block!.id, {
        styles: {
          color: "#111",
          paddingTop: "8px",
        },
      });

      expect(editor.getResolvedBlockStyles(block!.id)).toEqual({
        fontFamily: "Arial",
        color: "#111",
        backgroundColor: "#eee",
        paddingTop: "8px",
      });
    });
  });
});
