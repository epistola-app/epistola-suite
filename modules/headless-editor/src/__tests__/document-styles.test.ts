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

    it("should inherit parent block styles hierarchically", () => {
      const editor = new TemplateEditor();

      // Set document-level styles
      editor.updateDocumentStyles({
        fontFamily: "Arial",
        fontSize: "14px",
        color: "#333",
      });

      // Add a container block with large font size
      const container = editor.addBlock("container");
      expect(container).not.toBeNull();
      editor.updateBlock(container!.id, {
        styles: {
          fontSize: "4rem",
          color: "#ff0000",
          fontWeight: "700",
        },
      });

      // Add a nested text block inside the container
      const nestedBlock = editor.addBlock("text", container!.id);
      expect(nestedBlock).not.toBeNull();

      // Get resolved styles for both blocks
      const containerResolvedStyles = editor.getResolvedBlockStyles(
        container!.id,
      );
      const nestedResolvedStyles = editor.getResolvedBlockStyles(
        nestedBlock!.id,
      );

      // Container should have its overrides
      expect(containerResolvedStyles).toEqual({
        fontFamily: "Arial",
        fontSize: "4rem",
        color: "#ff0000",
        fontWeight: "700",
      });

      // Nested block SHOULD inherit from parent container
      expect(nestedResolvedStyles).toEqual({
        fontFamily: "Arial",
        fontSize: "4rem", // Inherited from container
        color: "#ff0000", // Inherited from container
        fontWeight: "700", // Inherited from container
      });
    });

    it("should cascade styles through multiple nesting levels", () => {
      const editor = new TemplateEditor();

      // Set document styles
      editor.updateDocumentStyles({
        fontSize: "12px",
        color: "#000",
        fontWeight: "400",
      });

      // Level 1: Container with large font and red background
      const container1 = editor.addBlock("container");
      expect(container1).not.toBeNull();
      editor.updateBlock(container1!.id, {
        styles: {
          fontSize: "3rem",
          backgroundColor: "#ff0000",
        },
      });

      // Level 2: Nested container overrides font size and adds blue color
      const container2 = editor.addBlock("container", container1!.id);
      expect(container2).not.toBeNull();
      editor.updateBlock(container2!.id, {
        styles: {
          fontSize: "2rem",
          color: "#0000ff",
        },
      });

      // Level 3: Text block deeply nested
      const textBlock = editor.addBlock("text", container2!.id);
      expect(textBlock).not.toBeNull();

      // Text block inherits cascade: document → container1 → container2 → textBlock
      const textResolvedStyles = editor.getResolvedBlockStyles(textBlock!.id);

      expect(textResolvedStyles).toEqual({
        fontSize: "2rem", // From container2 (most recent override)
        color: "#0000ff", // From container2
        backgroundColor: "#ff0000", // From container1
        fontWeight: "400", // From document
      });
    });
  });
});
