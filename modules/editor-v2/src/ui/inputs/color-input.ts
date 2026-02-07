/**
 * Color input component.
 *
 * Simple color picker with text input for hex values.
 */

// ============================================================================
// Types
// ============================================================================

export interface ColorInputOptions {
  /** Current color value (hex) */
  value?: string;
  /** Callback when color changes */
  onChange: (value: string | undefined) => void;
  /** Label text */
  label?: string;
  /** Placeholder text */
  placeholder?: string;
  /** Whether input is disabled */
  disabled?: boolean;
  /** Show clear button */
  allowClear?: boolean;
}

export interface ColorInput {
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
// Component
// ============================================================================

/**
 * Create a color input component.
 */
export function createColorInput(options: ColorInputOptions): ColorInput {
  const {
    value,
    onChange,
    label,
    placeholder = "#000000",
    disabled = false,
    allowClear = true,
  } = options;

  // Create elements
  const container = document.createElement("div");
  container.className = "ev2-color-input";

  // Label (optional)
  if (label) {
    const labelEl = document.createElement("label");
    labelEl.className = "ev2-color-input__label";
    labelEl.textContent = label;
    container.appendChild(labelEl);
  }

  // Input wrapper
  const wrapper = document.createElement("div");
  wrapper.className = "ev2-color-input__wrapper";

  // Color swatch (native color picker)
  const colorPicker = document.createElement("input");
  colorPicker.type = "color";
  colorPicker.className = "ev2-color-input__picker";
  colorPicker.value = value || "#000000";
  colorPicker.disabled = disabled;

  // Text input for hex value
  const textInput = document.createElement("input");
  textInput.type = "text";
  textInput.className = "ev2-color-input__text";
  textInput.placeholder = placeholder;
  textInput.value = value || "";
  textInput.disabled = disabled;

  wrapper.appendChild(colorPicker);
  wrapper.appendChild(textInput);

  // Clear button (optional)
  let clearButton: HTMLButtonElement | null = null;
  if (allowClear) {
    clearButton = document.createElement("button");
    clearButton.type = "button";
    clearButton.className = "ev2-color-input__clear";
    clearButton.textContent = "×";
    clearButton.title = "Clear color";
    clearButton.disabled = disabled;
    wrapper.appendChild(clearButton);
  }

  container.appendChild(wrapper);

  // Event handlers
  function updateFromColorPicker(): void {
    const newValue = colorPicker.value;
    textInput.value = newValue;
    onChange(newValue);
  }

  function updateFromTextInput(): void {
    const newValue = textInput.value.trim();
    if (newValue === "") {
      onChange(undefined);
      colorPicker.value = "#000000";
    } else if (/^#[0-9A-Fa-f]{6}$/.test(newValue)) {
      colorPicker.value = newValue;
      onChange(newValue);
    } else if (/^[0-9A-Fa-f]{6}$/.test(newValue)) {
      const hex = `#${newValue}`;
      colorPicker.value = hex;
      textInput.value = hex;
      onChange(hex);
    }
  }

  function handleClear(): void {
    textInput.value = "";
    colorPicker.value = "#000000";
    onChange(undefined);
  }

  colorPicker.addEventListener("input", updateFromColorPicker);
  colorPicker.addEventListener("change", updateFromColorPicker);
  textInput.addEventListener("input", updateFromTextInput);
  textInput.addEventListener("blur", updateFromTextInput);
  clearButton?.addEventListener("click", handleClear);

  return {
    element: container,

    getValue(): string | undefined {
      const val = textInput.value.trim();
      return val || undefined;
    },

    setValue(newValue: string | undefined): void {
      if (newValue) {
        textInput.value = newValue;
        colorPicker.value = newValue;
      } else {
        textInput.value = "";
        colorPicker.value = "#000000";
      }
    },

    setDisabled(isDisabled: boolean): void {
      colorPicker.disabled = isDisabled;
      textInput.disabled = isDisabled;
      if (clearButton) clearButton.disabled = isDisabled;
    },

    destroy(): void {
      colorPicker.removeEventListener("input", updateFromColorPicker);
      colorPicker.removeEventListener("change", updateFromColorPicker);
      textInput.removeEventListener("input", updateFromTextInput);
      textInput.removeEventListener("blur", updateFromTextInput);
      clearButton?.removeEventListener("click", handleClear);
      container.remove();
    },
  };
}
