/**
 * Unit input component.
 *
 * Combines a number input with a unit selector (px, em, rem, %).
 */

// ============================================================================
// Types
// ============================================================================

export type CSSUnit = "px" | "em" | "rem" | "%" | "pt" | "vh" | "vw";

export interface UnitInputOptions {
  /** Current value (e.g., "16px", "1.5em") */
  value?: string;
  /** Callback when value changes */
  onChange: (value: string | undefined) => void;
  /** Available units */
  units?: CSSUnit[];
  /** Default unit when none specified */
  defaultUnit?: CSSUnit;
  /** Placeholder text */
  placeholder?: string;
  /** Minimum value */
  min?: number;
  /** Maximum value */
  max?: number;
  /** Step increment */
  step?: number;
  /** Label text */
  label?: string;
  /** Whether input is disabled */
  disabled?: boolean;
}

export interface UnitInput {
  /** Root element */
  element: HTMLElement;
  /** Get current value */
  getValue(): string | undefined;
  /** Set value */
  setValue(value: string | undefined): void;
  /** Set disabled state */
  setDisabled(disabled: boolean): void;
  /** Destroy and cleanup */
  destroy(): void;
}

// ============================================================================
// Helpers
// ============================================================================

/**
 * Parse a CSS value with unit into parts.
 */
export function parseValueWithUnit(
  value: string | undefined,
): { value: number; unit: CSSUnit } | null {
  if (!value) return null;

  const match = value.match(/^(-?\d*\.?\d+)(px|em|rem|%|pt|vh|vw)?$/);
  if (!match) return null;

  return {
    value: parseFloat(match[1]),
    unit: (match[2] as CSSUnit) || "px",
  };
}

/**
 * Format number and unit into CSS value.
 */
export function formatValueWithUnit(value: number, unit: CSSUnit): string {
  return `${value}${unit}`;
}

// ============================================================================
// Component
// ============================================================================

/**
 * Create a unit input component.
 */
export function createUnitInput(options: UnitInputOptions): UnitInput {
  const {
    value,
    onChange,
    units = ["px", "em", "rem", "%"],
    defaultUnit = "px",
    placeholder = "0",
    min,
    max,
    step = 1,
    label,
    disabled = false,
  } = options;

  // Parse initial value
  const parsed = parseValueWithUnit(value);
  let currentUnit = parsed?.unit ?? defaultUnit;

  // Create elements
  const container = document.createElement("div");
  container.className = "ev2-unit-input";

  // Label (optional)
  if (label) {
    const labelEl = document.createElement("label");
    labelEl.className = "ev2-unit-input__label";
    labelEl.textContent = label;
    container.appendChild(labelEl);
  }

  // Input wrapper
  const wrapper = document.createElement("div");
  wrapper.className = "ev2-unit-input__wrapper";

  // Number input
  const input = document.createElement("input");
  input.type = "number";
  input.className = "ev2-unit-input__number";
  input.placeholder = placeholder;
  input.value = parsed?.value?.toString() ?? "";
  input.disabled = disabled;
  if (min !== undefined) input.min = min.toString();
  if (max !== undefined) input.max = max.toString();
  input.step = step.toString();

  // Unit selector
  const select = document.createElement("select");
  select.className = "ev2-unit-input__unit";
  select.disabled = disabled;

  for (const unit of units) {
    const option = document.createElement("option");
    option.value = unit;
    option.textContent = unit;
    option.selected = unit === currentUnit;
    select.appendChild(option);
  }

  wrapper.appendChild(input);
  wrapper.appendChild(select);
  container.appendChild(wrapper);

  // Event handlers
  function handleChange(): void {
    const numValue = input.value;
    if (numValue === "") {
      onChange(undefined);
    } else {
      const num = parseFloat(numValue);
      if (!isNaN(num)) {
        onChange(formatValueWithUnit(num, currentUnit));
      }
    }
  }

  input.addEventListener("input", handleChange);
  input.addEventListener("change", handleChange);

  select.addEventListener("change", () => {
    currentUnit = select.value as CSSUnit;
    if (input.value !== "") {
      handleChange();
    }
  });

  return {
    element: container,

    getValue(): string | undefined {
      const numValue = input.value;
      if (numValue === "") return undefined;
      const num = parseFloat(numValue);
      if (isNaN(num)) return undefined;
      return formatValueWithUnit(num, currentUnit);
    },

    setValue(newValue: string | undefined): void {
      const parsed = parseValueWithUnit(newValue);
      if (parsed) {
        input.value = parsed.value.toString();
        currentUnit = parsed.unit;
        select.value = parsed.unit;
      } else {
        input.value = "";
      }
    },

    setDisabled(isDisabled: boolean): void {
      input.disabled = isDisabled;
      select.disabled = isDisabled;
    },

    destroy(): void {
      input.removeEventListener("input", handleChange);
      input.removeEventListener("change", handleChange);
      container.remove();
    },
  };
}
