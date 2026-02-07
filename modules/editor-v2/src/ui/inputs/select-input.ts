/**
 * Select input component.
 *
 * Simple dropdown select with label support.
 */

// ============================================================================
// Types
// ============================================================================

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectInputOptions {
  /** Current value */
  value?: string;
  /** Available options */
  options: SelectOption[];
  /** Callback when value changes */
  onChange: (value: string) => void;
  /** Label text */
  label?: string;
  /** Placeholder text */
  placeholder?: string;
  /** Whether input is disabled */
  disabled?: boolean;
}

export interface SelectInput {
  /** Root element */
  element: HTMLElement;
  /** Get current value */
  getValue(): string | undefined;
  /** Set value */
  setValue(value: string): void;
  /** Set options */
  setOptions(options: SelectOption[]): void;
  /** Set disabled state */
  setDisabled(disabled: boolean): void;
  /** Destroy and cleanup */
  destroy(): void;
}

// ============================================================================
// Component
// ============================================================================

/**
 * Create a select input component.
 */
export function createSelectInput(options: SelectInputOptions): SelectInput {
  const {
    value,
    options: initialOptions,
    onChange,
    label,
    placeholder,
    disabled = false,
  } = options;

  // Create elements
  const container = document.createElement("div");
  container.className = "ev2-select-input";

  // Label (optional)
  if (label) {
    const labelEl = document.createElement("label");
    labelEl.className = "ev2-select-input__label";
    labelEl.textContent = label;
    container.appendChild(labelEl);
  }

  // Select element
  const select = document.createElement("select");
  select.className = "ev2-select-input__select";
  select.disabled = disabled;

  // Populate options
  function populateOptions(opts: SelectOption[]): void {
    select.innerHTML = "";

    if (placeholder) {
      const placeholderOpt = document.createElement("option");
      placeholderOpt.value = "";
      placeholderOpt.textContent = placeholder;
      placeholderOpt.disabled = true;
      placeholderOpt.selected = !value;
      select.appendChild(placeholderOpt);
    }

    for (const opt of opts) {
      const option = document.createElement("option");
      option.value = opt.value;
      option.textContent = opt.label;
      option.disabled = opt.disabled ?? false;
      option.selected = opt.value === value;
      select.appendChild(option);
    }
  }

  populateOptions(initialOptions);
  container.appendChild(select);

  // Event handler
  function handleChange(): void {
    onChange(select.value);
  }

  select.addEventListener("change", handleChange);

  return {
    element: container,

    getValue(): string | undefined {
      return select.value || undefined;
    },

    setValue(newValue: string): void {
      select.value = newValue;
    },

    setOptions(newOptions: SelectOption[]): void {
      const currentValue = select.value;
      populateOptions(newOptions);
      // Try to restore previous value
      if (newOptions.some((o) => o.value === currentValue)) {
        select.value = currentValue;
      }
    },

    setDisabled(isDisabled: boolean): void {
      select.disabled = isDisabled;
    },

    destroy(): void {
      select.removeEventListener("change", handleChange);
      container.remove();
    },
  };
}
