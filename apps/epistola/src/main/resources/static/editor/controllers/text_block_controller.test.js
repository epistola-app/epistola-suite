/**
 * Unit tests for TextBlockController
 *
 * These tests verify the controller's core functionality without
 * requiring a full browser environment.
 *
 * Run via: import and call runTextBlockTests() in browser console
 * Or include in a test runner that supports ES modules.
 */

import { TextBlockController } from "./text_block_controller.js";

/**
 * Simple test helper
 */
function describe(name, fn) {
  console.group(`Suite: ${name}`);
  fn();
  console.groupEnd();
}

function it(name, fn) {
  try {
    fn();
    console.log(`  \u2713 ${name}`);
  } catch (e) {
    console.error(`  \u2717 ${name}`);
    console.error(`    ${e.message}`);
  }
}

function expect(actual) {
  return {
    toBe(expected) {
      if (actual !== expected) {
        throw new Error(`Expected ${JSON.stringify(expected)} but got ${JSON.stringify(actual)}`);
      }
    },
    toEqual(expected) {
      if (JSON.stringify(actual) !== JSON.stringify(expected)) {
        throw new Error(`Expected ${JSON.stringify(expected)} but got ${JSON.stringify(actual)}`);
      }
    },
    toBeTruthy() {
      if (!actual) {
        throw new Error(`Expected truthy value but got ${JSON.stringify(actual)}`);
      }
    },
    toBeFalsy() {
      if (actual) {
        throw new Error(`Expected falsy value but got ${JSON.stringify(actual)}`);
      }
    },
    toContain(expected) {
      if (!actual.includes(expected)) {
        throw new Error(`Expected ${JSON.stringify(actual)} to contain ${JSON.stringify(expected)}`);
      }
    },
    toHaveLength(expected) {
      if (actual.length !== expected) {
        throw new Error(`Expected length ${expected} but got ${actual.length}`);
      }
    },
  };
}

/**
 * Create a mock controller instance for testing
 */
function createMockController() {
  const controller = Object.create(TextBlockController.prototype);

  // Mock element
  controller.element = document.createElement("div");

  // Mock targets
  const editor = document.createElement("div");
  editor.contentEditable = "true";
  const popover = document.createElement("div");
  const popoverInput = document.createElement("input");
  const popoverPreview = document.createElement("div");

  controller.editorTarget = editor;
  controller.popoverTarget = popover;
  controller.popoverInputTarget = popoverInput;
  controller.popoverPreviewTarget = popoverPreview;

  controller.hasEditorTarget = true;
  controller.hasPopoverTarget = true;
  controller.hasPopoverInputTarget = true;
  controller.hasPopoverPreviewTarget = true;

  // Mock values
  controller.blockIdValue = "test-block-1";
  controller.contentValue = "";

  // Initialize state
  controller.activeChip = null;

  return controller;
}

/**
 * Run all tests
 */
export function runTextBlockTests() {
  console.log("Running TextBlockController tests...\n");

  describe("parseTextWithExpressions", () => {
    const controller = createMockController();

    it("returns plain text as single part", () => {
      const result = controller.parseTextWithExpressions("Hello world");
      expect(result).toHaveLength(1);
      expect(result[0].type).toBe("text");
      expect(result[0].value).toBe("Hello world");
    });

    it("parses single expression", () => {
      const result = controller.parseTextWithExpressions("Hello {{name}}");
      expect(result).toHaveLength(2);
      expect(result[0].type).toBe("text");
      expect(result[0].value).toBe("Hello ");
      expect(result[1].type).toBe("expression");
      expect(result[1].value).toBe("name");
    });

    it("parses multiple expressions", () => {
      const result = controller.parseTextWithExpressions("{{greeting}} {{name}}!");
      expect(result).toHaveLength(4);
      expect(result[0].type).toBe("expression");
      expect(result[0].value).toBe("greeting");
      expect(result[1].type).toBe("text");
      expect(result[1].value).toBe(" ");
      expect(result[2].type).toBe("expression");
      expect(result[2].value).toBe("name");
      expect(result[3].type).toBe("text");
      expect(result[3].value).toBe("!");
    });

    it("trims whitespace from expression", () => {
      const result = controller.parseTextWithExpressions("{{ customer.name }}");
      expect(result).toHaveLength(1);
      expect(result[0].type).toBe("expression");
      expect(result[0].value).toBe("customer.name");
    });

    it("handles nested property access", () => {
      const result = controller.parseTextWithExpressions("{{customer.address.city}}");
      expect(result).toHaveLength(1);
      expect(result[0].type).toBe("expression");
      expect(result[0].value).toBe("customer.address.city");
    });

    it("returns empty array for empty string", () => {
      const result = controller.parseTextWithExpressions("");
      expect(result).toHaveLength(0);
    });
  });

  describe("formatValue", () => {
    const controller = createMockController();

    it("formats undefined", () => {
      expect(controller.formatValue(undefined)).toBe("undefined");
    });

    it("formats null", () => {
      expect(controller.formatValue(null)).toBe("null");
    });

    it("formats short string", () => {
      expect(controller.formatValue("hello")).toBe("hello");
    });

    it("truncates long string", () => {
      const longStr = "This is a very long string that exceeds the limit";
      const result = controller.formatValue(longStr);
      expect(result.length).toBe(23); // 20 chars + "..."
      expect(result.endsWith("...")).toBeTruthy();
    });

    it("formats number", () => {
      expect(controller.formatValue(42)).toBe("42");
    });

    it("formats boolean", () => {
      expect(controller.formatValue(true)).toBe("true");
    });

    it("formats short object", () => {
      expect(controller.formatValue({ a: 1 })).toBe('{"a":1}');
    });

    it("truncates long object", () => {
      const longObj = { name: "A very long value" };
      const result = controller.formatValue(longObj);
      expect(result.length).toBe(23); // 20 chars + "..."
    });
  });

  describe("createExpressionChip", () => {
    const controller = createMockController();

    it("creates chip element", () => {
      const chip = controller.createExpressionChip("customer.name");

      expect(chip.tagName).toBe("SPAN");
      expect(chip.className).toBe("expression-chip");
      expect(chip.contentEditable).toBe("false");
      expect(chip.dataset.expression).toBe("customer.name");
    });

    it("creates chip with expression text", () => {
      const chip = controller.createExpressionChip("orders.length");
      const exprSpan = chip.querySelector(".expression-chip-expr");

      expect(exprSpan).toBeTruthy();
      expect(exprSpan.textContent).toBe("orders.length");
    });

    it("creates chip with value placeholder", () => {
      const chip = controller.createExpressionChip("name");
      const valueSpan = chip.querySelector(".expression-chip-value");

      expect(valueSpan).toBeTruthy();
      expect(valueSpan.textContent).toBe("");
    });

    it("creates chip with placeholder for empty expression", () => {
      const chip = controller.createExpressionChip("");
      const exprSpan = chip.querySelector(".expression-chip-expr");

      expect(exprSpan.textContent).toBe("...");
    });
  });

  describe("convertToTipTap", () => {
    it("returns null for empty content", () => {
      const controller = createMockController();
      controller.editorTarget.textContent = "";

      const result = controller.convertToTipTap();

      expect(result).toBe(null);
    });

    it("converts plain text to TipTap format", () => {
      const controller = createMockController();
      const p = document.createElement("div");
      p.className = "text-block-paragraph";
      p.textContent = "Hello world";
      controller.editorTarget.appendChild(p);

      const result = controller.convertToTipTap();

      expect(result.type).toBe("doc");
      expect(result.content).toHaveLength(1);
      expect(result.content[0].type).toBe("paragraph");
      expect(result.content[0].content[0].type).toBe("text");
      expect(result.content[0].content[0].text).toBe("Hello world");
    });

    it("converts expression chip to mustache syntax", () => {
      const controller = createMockController();
      const p = document.createElement("div");
      p.className = "text-block-paragraph";
      p.appendChild(document.createTextNode("Hello "));

      const chip = document.createElement("span");
      chip.className = "expression-chip";
      chip.dataset.expression = "name";
      p.appendChild(chip);

      controller.editorTarget.appendChild(p);

      const result = controller.convertToTipTap();

      expect(result.content[0].content).toHaveLength(2);
      expect(result.content[0].content[0].text).toBe("Hello ");
      expect(result.content[0].content[1].text).toBe("{{name}}");
    });
  });

  describe("popover management", () => {
    it("openPopover shows popover", () => {
      const controller = createMockController();
      const chip = controller.createExpressionChip("test");

      // Add chip to DOM for positioning
      controller.element.appendChild(chip);

      controller.openPopover(chip);

      expect(controller.popoverTarget.style.display).toBe("block");
      expect(controller.activeChip).toBe(chip);
    });

    it("openPopover sets input value", () => {
      const controller = createMockController();
      const chip = controller.createExpressionChip("customer.name");
      controller.element.appendChild(chip);

      controller.openPopover(chip);

      expect(controller.popoverInputTarget.value).toBe("customer.name");
    });

    it("closePopover hides popover", () => {
      const controller = createMockController();
      controller.popoverTarget.style.display = "block";
      controller.activeChip = document.createElement("span");

      controller.closePopover();

      expect(controller.popoverTarget.style.display).toBe("none");
      expect(controller.activeChip).toBe(null);
    });
  });

  describe("savePopover", () => {
    it("updates chip with new expression", () => {
      const controller = createMockController();
      const chip = controller.createExpressionChip("old");
      controller.element.appendChild(chip);
      controller.activeChip = chip;
      controller.popoverInputTarget.value = "new.expression";

      // Mock window.__editor
      window.__editor = {
        updateBlock: () => {},
      };

      controller.savePopover();

      expect(chip.dataset.expression).toBe("new.expression");
      const exprSpan = chip.querySelector(".expression-chip-expr");
      expect(exprSpan.textContent).toBe("new.expression");
    });

    it("removes chip when expression is empty", () => {
      const controller = createMockController();
      const chip = controller.createExpressionChip("test");
      controller.editorTarget.appendChild(chip);
      controller.activeChip = chip;
      controller.popoverInputTarget.value = "";

      // Mock window.__editor
      window.__editor = {
        updateBlock: () => {},
      };

      controller.savePopover();

      expect(controller.editorTarget.contains(chip)).toBeFalsy();
    });
  });

  console.log("\nAll tests completed.");
}

// Export for use in browser console
if (typeof window !== "undefined") {
  window.runTextBlockTests = runTextBlockTests;
}
