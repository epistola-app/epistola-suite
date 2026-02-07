/**
 * Tests for UI input components.
 */

import { describe, it, expect, vi } from "vitest";
import {
  createUnitInput,
  parseValueWithUnit,
  formatValueWithUnit,
} from "./unit-input.ts";
import { createColorInput } from "./color-input.ts";
import { createSpacingInput } from "./spacing-input.ts";
import { createSelectInput } from "./select-input.ts";

describe("Unit Input", () => {
  describe("parseValueWithUnit", () => {
    it("should parse value with px unit", () => {
      expect(parseValueWithUnit("16px")).toEqual({ value: 16, unit: "px" });
    });

    it("should parse value with em unit", () => {
      expect(parseValueWithUnit("1.5em")).toEqual({ value: 1.5, unit: "em" });
    });

    it("should parse value with % unit", () => {
      expect(parseValueWithUnit("100%")).toEqual({ value: 100, unit: "%" });
    });

    it("should parse negative values", () => {
      expect(parseValueWithUnit("-10px")).toEqual({ value: -10, unit: "px" });
    });

    it("should default to px when no unit", () => {
      expect(parseValueWithUnit("42")).toEqual({ value: 42, unit: "px" });
    });

    it("should return null for undefined", () => {
      expect(parseValueWithUnit(undefined)).toBeNull();
    });

    it("should return null for invalid value", () => {
      expect(parseValueWithUnit("invalid")).toBeNull();
    });
  });

  describe("formatValueWithUnit", () => {
    it("should format value with unit", () => {
      expect(formatValueWithUnit(16, "px")).toBe("16px");
      expect(formatValueWithUnit(1.5, "em")).toBe("1.5em");
      expect(formatValueWithUnit(100, "%")).toBe("100%");
    });
  });

  describe("createUnitInput", () => {
    it("should create element with correct class", () => {
      const onChange = vi.fn();
      const input = createUnitInput({ onChange });

      expect(input.element.className).toBe("ev2-unit-input");
      input.destroy();
    });

    it("should render label when provided", () => {
      const onChange = vi.fn();
      const input = createUnitInput({ label: "Width", onChange });

      expect(input.element.querySelector(".ev2-unit-input__label")).toBeTruthy();
      expect(
        input.element.querySelector(".ev2-unit-input__label")?.textContent,
      ).toBe("Width");
      input.destroy();
    });

    it("should set initial value", () => {
      const onChange = vi.fn();
      const input = createUnitInput({ value: "24px", onChange });

      expect(input.getValue()).toBe("24px");
      input.destroy();
    });

    it("should update value", () => {
      const onChange = vi.fn();
      const input = createUnitInput({ onChange });

      input.setValue("32em");
      expect(input.getValue()).toBe("32em");
      input.destroy();
    });

    it("should call onChange when value changes", () => {
      const onChange = vi.fn();
      const input = createUnitInput({ onChange });

      const numberInput = input.element.querySelector(
        ".ev2-unit-input__number",
      ) as HTMLInputElement;
      numberInput.value = "20";
      numberInput.dispatchEvent(new Event("input"));

      expect(onChange).toHaveBeenCalledWith("20px");
      input.destroy();
    });
  });
});

describe("Color Input", () => {
  describe("createColorInput", () => {
    it("should create element with correct class", () => {
      const onChange = vi.fn();
      const input = createColorInput({ onChange });

      expect(input.element.className).toBe("ev2-color-input");
      input.destroy();
    });

    it("should set initial value", () => {
      const onChange = vi.fn();
      const input = createColorInput({ value: "#ff0000", onChange });

      expect(input.getValue()).toBe("#ff0000");
      input.destroy();
    });

    it("should update value", () => {
      const onChange = vi.fn();
      const input = createColorInput({ onChange });

      input.setValue("#00ff00");
      expect(input.getValue()).toBe("#00ff00");
      input.destroy();
    });

    it("should render clear button when allowClear is true", () => {
      const onChange = vi.fn();
      const input = createColorInput({ allowClear: true, onChange });

      expect(
        input.element.querySelector(".ev2-color-input__clear"),
      ).toBeTruthy();
      input.destroy();
    });

    it("should not render clear button when allowClear is false", () => {
      const onChange = vi.fn();
      const input = createColorInput({ allowClear: false, onChange });

      expect(input.element.querySelector(".ev2-color-input__clear")).toBeNull();
      input.destroy();
    });
  });
});

describe("Spacing Input", () => {
  describe("createSpacingInput", () => {
    it("should create element with correct class", () => {
      const onChange = vi.fn();
      const input = createSpacingInput({
        values: {},
        onChange,
      });

      expect(input.element.className).toBe("ev2-spacing-input");
      input.destroy();
    });

    it("should set initial values", () => {
      const onChange = vi.fn();
      const input = createSpacingInput({
        values: {
          top: "10px",
          right: "20px",
          bottom: "10px",
          left: "20px",
        },
        onChange,
      });

      const values = input.getValues();
      expect(values.top).toBe("10px");
      expect(values.right).toBe("20px");
      expect(values.bottom).toBe("10px");
      expect(values.left).toBe("20px");
      input.destroy();
    });

    it("should render label when provided", () => {
      const onChange = vi.fn();
      const input = createSpacingInput({
        label: "Padding",
        values: {},
        onChange,
      });

      expect(
        input.element.querySelector(".ev2-spacing-input__label"),
      ).toBeTruthy();
      expect(
        input.element.querySelector(".ev2-spacing-input__label")?.textContent,
      ).toBe("Padding");
      input.destroy();
    });

    it("should have link button", () => {
      const onChange = vi.fn();
      const input = createSpacingInput({
        values: {},
        onChange,
      });

      const linkButton = input.element.querySelector(".ev2-spacing-input__link");
      expect(linkButton).toBeTruthy();
      expect(linkButton?.textContent).toBe("Link");
      input.destroy();
    });

    it("should update values", () => {
      const onChange = vi.fn();
      const input = createSpacingInput({
        values: {},
        onChange,
      });

      input.setValues({
        top: "8px",
        right: "16px",
        bottom: "8px",
        left: "16px",
      });

      const values = input.getValues();
      expect(values.top).toBe("8px");
      expect(values.right).toBe("16px");
      input.destroy();
    });
  });
});

describe("Select Input", () => {
  const defaultOptions = [
    { value: "a", label: "Option A" },
    { value: "b", label: "Option B" },
    { value: "c", label: "Option C" },
  ];

  describe("createSelectInput", () => {
    it("should create element with correct class", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        options: defaultOptions,
        onChange,
      });

      expect(input.element.className).toBe("ev2-select-input");
      input.destroy();
    });

    it("should set initial value", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        value: "b",
        options: defaultOptions,
        onChange,
      });

      expect(input.getValue()).toBe("b");
      input.destroy();
    });

    it("should render all options", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        options: defaultOptions,
        onChange,
      });

      const select = input.element.querySelector(
        ".ev2-select-input__select",
      ) as HTMLSelectElement;
      expect(select.options.length).toBe(3);
      input.destroy();
    });

    it("should update value", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        options: defaultOptions,
        onChange,
      });

      input.setValue("c");
      expect(input.getValue()).toBe("c");
      input.destroy();
    });

    it("should call onChange when value changes", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        options: defaultOptions,
        onChange,
      });

      const select = input.element.querySelector(
        ".ev2-select-input__select",
      ) as HTMLSelectElement;
      select.value = "b";
      select.dispatchEvent(new Event("change"));

      expect(onChange).toHaveBeenCalledWith("b");
      input.destroy();
    });

    it("should update options", () => {
      const onChange = vi.fn();
      const input = createSelectInput({
        options: defaultOptions,
        onChange,
      });

      const newOptions = [
        { value: "x", label: "Option X" },
        { value: "y", label: "Option Y" },
      ];

      input.setOptions(newOptions);

      const select = input.element.querySelector(
        ".ev2-select-input__select",
      ) as HTMLSelectElement;
      expect(select.options.length).toBe(2);
      input.destroy();
    });
  });
});
