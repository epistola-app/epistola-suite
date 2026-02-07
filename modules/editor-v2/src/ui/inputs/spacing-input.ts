/**
 * Spacing input component.
 *
 * Four-sided input for padding/margin with optional linking.
 */

import {
  type CSSUnit,
  parseValueWithUnit,
  formatValueWithUnit,
} from "./unit-input.ts";

// ============================================================================
// Types
// ============================================================================

export interface SpacingValues {
  top?: string;
  right?: string;
  bottom?: string;
  left?: string;
}

export type SpacingSide = "top" | "right" | "bottom" | "left";

export interface SpacingInputOptions {
  /** Current values */
  values: SpacingValues;
  /** Callback when any value changes */
  onChange: (side: SpacingSide, value: string | undefined) => void;
  /** Label text */
  label?: string;
  /** Available units */
  units?: CSSUnit[];
  /** Default unit */
  defaultUnit?: CSSUnit;
  /** Whether input is disabled */
  disabled?: boolean;
}

export interface SpacingInput {
  /** Root element */
  element: HTMLElement;
  /** Get current values */
  getValues(): SpacingValues;
  /** Set values */
  setValues(values: SpacingValues): void;
  /** Set disabled state */
  setDisabled(disabled: boolean): void;
  /** Destroy and cleanup */
  destroy(): void;
}

// ============================================================================
// Component
// ============================================================================

/**
 * Create a spacing input component.
 */
export function createSpacingInput(options: SpacingInputOptions): SpacingInput {
  const {
    values,
    onChange,
    label,
    units = ["px", "em", "rem", "%"],
    defaultUnit = "px",
    disabled = false,
  } = options;

  let linked = false;
  let currentUnit: CSSUnit = defaultUnit;

  // Create elements
  const container = document.createElement("div");
  container.className = "ev2-spacing-input";

  // Header with label and link button
  const header = document.createElement("div");
  header.className = "ev2-spacing-input__header";

  if (label) {
    const labelEl = document.createElement("span");
    labelEl.className = "ev2-spacing-input__label";
    labelEl.textContent = label;
    header.appendChild(labelEl);
  }

  const linkButton = document.createElement("button");
  linkButton.type = "button";
  linkButton.className = "ev2-spacing-input__link";
  linkButton.textContent = "Link";
  linkButton.title = "Link all sides";
  header.appendChild(linkButton);

  container.appendChild(header);

  // Grid layout for inputs
  const grid = document.createElement("div");
  grid.className = "ev2-spacing-input__grid";

  // Create input for each side
  const inputs: Record<SpacingSide, HTMLInputElement> = {
    top: createSideInput("top", values.top),
    right: createSideInput("right", values.right),
    bottom: createSideInput("bottom", values.bottom),
    left: createSideInput("left", values.left),
  };

  // Unit selector in center
  const unitSelect = document.createElement("select");
  unitSelect.className = "ev2-spacing-input__unit";
  unitSelect.disabled = disabled;

  for (const unit of units) {
    const option = document.createElement("option");
    option.value = unit;
    option.textContent = unit;
    option.selected = unit === currentUnit;
    unitSelect.appendChild(option);
  }

  // Layout: 3x3 grid
  // Row 1: [empty] [top] [empty]
  // Row 2: [left] [unit] [right]
  // Row 3: [empty] [bottom] [empty]
  const spacer1 = document.createElement("div");
  const spacer2 = document.createElement("div");
  const spacer3 = document.createElement("div");
  const spacer4 = document.createElement("div");

  grid.appendChild(spacer1);
  grid.appendChild(inputs.top);
  grid.appendChild(spacer2);
  grid.appendChild(inputs.left);
  grid.appendChild(unitSelect);
  grid.appendChild(inputs.right);
  grid.appendChild(spacer3);
  grid.appendChild(inputs.bottom);
  grid.appendChild(spacer4);

  container.appendChild(grid);

  // Helper to create side input
  function createSideInput(side: SpacingSide, value?: string): HTMLInputElement {
    const input = document.createElement("input");
    input.type = "number";
    input.className = `ev2-spacing-input__side ev2-spacing-input__side--${side}`;
    input.placeholder = "0";
    input.disabled = disabled;

    const parsed = parseValueWithUnit(value);
    input.value = parsed?.value?.toString() ?? "";

    input.addEventListener("input", () => handleSideChange(side, input.value));
    input.addEventListener("change", () => handleSideChange(side, input.value));

    return input;
  }

  // Handle side value change
  function handleSideChange(side: SpacingSide, numericValue: string): void {
    if (numericValue === "") {
      if (linked) {
        onChange("top", undefined);
        onChange("right", undefined);
        onChange("bottom", undefined);
        onChange("left", undefined);
        inputs.top.value = "";
        inputs.right.value = "";
        inputs.bottom.value = "";
        inputs.left.value = "";
      } else {
        onChange(side, undefined);
      }
    } else {
      const num = parseFloat(numericValue);
      if (!isNaN(num)) {
        const formatted = formatValueWithUnit(num, currentUnit);
        if (linked) {
          onChange("top", formatted);
          onChange("right", formatted);
          onChange("bottom", formatted);
          onChange("left", formatted);
          inputs.top.value = numericValue;
          inputs.right.value = numericValue;
          inputs.bottom.value = numericValue;
          inputs.left.value = numericValue;
        } else {
          onChange(side, formatted);
        }
      }
    }
  }

  // Handle unit change
  function handleUnitChange(): void {
    currentUnit = unitSelect.value as CSSUnit;

    // Update all sides with new unit
    const sides: SpacingSide[] = ["top", "right", "bottom", "left"];
    for (const side of sides) {
      const input = inputs[side];
      if (input.value !== "") {
        const num = parseFloat(input.value);
        if (!isNaN(num)) {
          onChange(side, formatValueWithUnit(num, currentUnit));
        }
      }
    }
  }

  // Handle link toggle
  function handleLinkToggle(): void {
    linked = !linked;
    linkButton.textContent = linked ? "Linked" : "Link";
    linkButton.classList.toggle("ev2-spacing-input__link--active", linked);

    // If linking and values differ, sync to top value
    if (linked) {
      const topValue = inputs.top.value;
      if (topValue !== "") {
        inputs.right.value = topValue;
        inputs.bottom.value = topValue;
        inputs.left.value = topValue;
        handleSideChange("top", topValue);
      }
    }
  }

  unitSelect.addEventListener("change", handleUnitChange);
  linkButton.addEventListener("click", handleLinkToggle);

  return {
    element: container,

    getValues(): SpacingValues {
      const result: SpacingValues = {};
      const sides: SpacingSide[] = ["top", "right", "bottom", "left"];

      for (const side of sides) {
        const input = inputs[side];
        if (input.value !== "") {
          const num = parseFloat(input.value);
          if (!isNaN(num)) {
            result[side] = formatValueWithUnit(num, currentUnit);
          }
        }
      }

      return result;
    },

    setValues(newValues: SpacingValues): void {
      const sides: SpacingSide[] = ["top", "right", "bottom", "left"];

      for (const side of sides) {
        const value = newValues[side];
        const parsed = parseValueWithUnit(value);
        inputs[side].value = parsed?.value?.toString() ?? "";

        // Update unit from first non-empty value
        if (parsed && currentUnit !== parsed.unit) {
          currentUnit = parsed.unit;
          unitSelect.value = currentUnit;
        }
      }
    },

    setDisabled(isDisabled: boolean): void {
      inputs.top.disabled = isDisabled;
      inputs.right.disabled = isDisabled;
      inputs.bottom.disabled = isDisabled;
      inputs.left.disabled = isDisabled;
      unitSelect.disabled = isDisabled;
      linkButton.disabled = isDisabled;
    },

    destroy(): void {
      unitSelect.removeEventListener("change", handleUnitChange);
      linkButton.removeEventListener("click", handleLinkToggle);
      container.remove();
    },
  };
}
