/**
 * Unit tests for ExpressionEditorController
 *
 * These tests verify the controller's core functionality without
 * requiring a full browser environment.
 *
 * Run via: import and call runExpressionEditorTests() in browser console
 * Or include in a test runner that supports ES modules.
 */

import { ExpressionEditorController } from "./expression_editor_controller.js";

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
  const controller = Object.create(ExpressionEditorController.prototype);

  // Mock element
  controller.element = document.createElement("div");

  // Mock targets
  const input = document.createElement("input");
  input.type = "text";
  const dropdown = document.createElement("div");
  const preview = document.createElement("div");

  controller.inputTarget = input;
  controller.dropdownTarget = dropdown;
  controller.previewTarget = preview;
  controller.hasInputTarget = true;
  controller.hasDropdownTarget = true;
  controller.hasPreviewTarget = true;

  // Mock values
  controller.blockIdValue = "test-block-1";
  controller.blockTypeValue = "conditional";
  controller.expressionValue = "";

  // Initialize state
  controller.isOpen = false;
  controller.value = "";

  return controller;
}

/**
 * Run all tests
 */
export function runExpressionEditorTests() {
  console.log("Running ExpressionEditorController tests...\n");

  describe("parsePartialExpression", () => {
    const controller = createMockController();

    it("returns empty path and partial for empty text", () => {
      const result = controller.parsePartialExpression("");
      expect(result.path).toEqual([]);
      expect(result.partial).toBe("");
    });

    it("returns empty path and partial for whitespace", () => {
      const result = controller.parsePartialExpression("   ");
      expect(result.path).toEqual([]);
      expect(result.partial).toBe("");
    });

    it("parses single segment as partial", () => {
      const result = controller.parsePartialExpression("customer");
      expect(result.path).toEqual([]);
      expect(result.partial).toBe("customer");
    });

    it("parses path ending with dot", () => {
      const result = controller.parsePartialExpression("customer.");
      expect(result.path).toEqual(["customer"]);
      expect(result.partial).toBe("");
    });

    it("parses multi-segment path", () => {
      const result = controller.parsePartialExpression("customer.name");
      expect(result.path).toEqual(["customer"]);
      expect(result.partial).toBe("name");
    });

    it("parses deep path", () => {
      const result = controller.parsePartialExpression("customer.address.city");
      expect(result.path).toEqual(["customer", "address"]);
      expect(result.partial).toBe("city");
    });
  });

  describe("formatPreviewValue", () => {
    const controller = createMockController();

    it("formats undefined", () => {
      expect(controller.formatPreviewValue(undefined)).toBe("undefined");
    });

    it("formats null", () => {
      expect(controller.formatPreviewValue(null)).toBe("null");
    });

    it("formats string", () => {
      expect(controller.formatPreviewValue("hello")).toBe("hello");
    });

    it("formats number", () => {
      expect(controller.formatPreviewValue(42)).toBe("42");
    });

    it("formats boolean", () => {
      expect(controller.formatPreviewValue(true)).toBe("true");
    });

    it("formats short object", () => {
      const result = controller.formatPreviewValue({ a: 1 });
      expect(result).toBe('{"a":1}');
    });

    it("truncates long object", () => {
      const longObj = { name: "very long value that exceeds the limit" };
      const result = controller.formatPreviewValue(longObj);
      expect(result.length).toBe(53); // 50 chars + "..."
      expect(result.endsWith("...")).toBeTruthy();
    });
  });

  describe("getTopLevelCompletions", () => {
    const controller = createMockController();

    // Mock test data
    window.__editorTestData = {
      customer: { name: "John", email: "john@example.com" },
      orders: [{ id: 1 }, { id: 2 }],
    };

    it("returns completions without filter", () => {
      const completions = controller.getTopLevelCompletions("");
      expect(completions.length > 0).toBeTruthy();
    });

    it("filters completions by prefix", () => {
      const completions = controller.getTopLevelCompletions("cus");
      const hasCustomer = completions.some((c) => c.label === "customer");
      expect(hasCustomer).toBeTruthy();
    });

    it("returns empty for non-matching filter", () => {
      const completions = controller.getTopLevelCompletions("zzz");
      expect(completions).toHaveLength(0);
    });
  });

  describe("dropdown management", () => {
    it("opens dropdown with suggestions", () => {
      const controller = createMockController();
      window.__editorTestData = { customer: { name: "John" } };

      controller.inputTarget.value = "cust";
      controller.inputTarget.selectionStart = 4;
      controller.inputTarget.selectionEnd = 4;

      controller.updateDropdown();

      expect(controller.isOpen).toBeTruthy();
      expect(controller.dropdownTarget.style.display).toBe("block");
    });

    it("closes dropdown when no suggestions", () => {
      const controller = createMockController();
      window.__editorTestData = {};

      controller.inputTarget.value = "nonexistent";
      controller.inputTarget.selectionStart = 11;
      controller.inputTarget.selectionEnd = 11;

      controller.updateDropdown();

      expect(controller.isOpen).toBeFalsy();
    });

    it("closeDropdown hides dropdown", () => {
      const controller = createMockController();
      controller.isOpen = true;
      controller.dropdownTarget.style.display = "block";

      controller.closeDropdown();

      expect(controller.isOpen).toBeFalsy();
      expect(controller.dropdownTarget.style.display).toBe("none");
    });
  });

  describe("highlightItem", () => {
    it("adds active class to item", () => {
      const controller = createMockController();
      const item = document.createElement("div");
      item.className = "expression-suggestion-item";
      controller.dropdownTarget.appendChild(item);

      controller.highlightItem(item);

      expect(item.classList.contains("active")).toBeTruthy();
    });

    it("removes active class from other items", () => {
      const controller = createMockController();
      const item1 = document.createElement("div");
      item1.className = "expression-suggestion-item active";
      const item2 = document.createElement("div");
      item2.className = "expression-suggestion-item";

      controller.dropdownTarget.appendChild(item1);
      controller.dropdownTarget.appendChild(item2);

      controller.highlightItem(item2);

      expect(item1.classList.contains("active")).toBeFalsy();
      expect(item2.classList.contains("active")).toBeTruthy();
    });
  });

  console.log("\nAll tests completed.");
}

// Export for use in browser console
if (typeof window !== "undefined") {
  window.runExpressionEditorTests = runExpressionEditorTests;
}
