import { describe, it, expect, vi } from "vitest";
import { TemplateEditor } from "../editor";

describe("batch()", () => {
  it("should group multiple mutations into a single undo entry", () => {
    const editor = new TemplateEditor();

    editor.batch(() => {
      editor.addBlock("text");
      editor.addBlock("container");
    });

    expect(editor.getTemplate().blocks).toHaveLength(2);

    // Single undo should revert both
    editor.undo();
    expect(editor.getTemplate().blocks).toHaveLength(0);
  });

  it("should fire onTemplateChange callback exactly once", () => {
    const onChange = vi.fn();
    const editor = new TemplateEditor({ callbacks: { onTemplateChange: onChange } });

    // Clear the initial subscription call
    onChange.mockClear();

    editor.batch(() => {
      editor.addBlock("text");
      editor.addBlock("text");
      editor.addBlock("text");
    });

    // Only one callback after the batch completes
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ blocks: expect.any(Array) })
    );
  });

  it("should propagate errors from the batch function", () => {
    const editor = new TemplateEditor();

    expect(() => {
      editor.batch(() => {
        editor.addBlock("text");
        throw new Error("batch error");
      });
    }).toThrow("batch error");

    // The mutation before the error should have been applied
    expect(editor.getTemplate().blocks).toHaveLength(1);
  });

  it("should restore callback even when error occurs", () => {
    const onChange = vi.fn();
    const editor = new TemplateEditor({ callbacks: { onTemplateChange: onChange } });
    onChange.mockClear();

    try {
      editor.batch(() => {
        editor.addBlock("text");
        throw new Error("oops");
      });
    } catch {
      // expected
    }

    // Callback should still fire for the batch completion
    expect(onChange).toHaveBeenCalled();

    // And it should work for subsequent mutations
    onChange.mockClear();
    editor.addBlock("text");
    expect(onChange).toHaveBeenCalled();
  });

  it("should handle nested batch calls", () => {
    const editor = new TemplateEditor();

    editor.batch(() => {
      editor.addBlock("text");
      editor.batch(() => {
        editor.addBlock("container");
      });
      editor.addBlock("text");
    });

    expect(editor.getTemplate().blocks).toHaveLength(3);

    // Single undo reverts all three
    editor.undo();
    expect(editor.getTemplate().blocks).toHaveLength(0);
  });

  it("should work with empty batch", () => {
    const editor = new TemplateEditor();
    editor.addBlock("text");
    const before = editor.getTemplate();

    editor.batch(() => {
      // no mutations
    });

    expect(editor.getTemplate()).toEqual(before);
  });
});
