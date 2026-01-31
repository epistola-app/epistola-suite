import * as React from "react";
import {cn} from "@/lib/utils";
import {type CSSUnit, formatValueWithUnit, parseValueWithUnit, UNIT_PRESETS,} from "@/types/styles";
import {Input} from "./input";
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from "./select";

interface UnitInputProps {
  value?: string;
  onChange: (value: string | undefined) => void;
  units?: readonly CSSUnit[];
  defaultUnit?: CSSUnit;
  placeholder?: string;
  min?: number;
  max?: number;
  step?: number;
  className?: string;
  disabled?: boolean;
  id?: string;
}

function UnitInput({
  value,
  onChange,
  units = UNIT_PRESETS.spacing,
  defaultUnit = "px",
  placeholder,
  min,
  max,
  step,
  className,
  disabled,
  id,
}: UnitInputProps) {
  // Parse the incoming value into number and unit
  const parsed = parseValueWithUnit(value);
  const numericValue = parsed?.value?.toString() ?? "";
  const unitValue = parsed?.unit ?? defaultUnit;

  // Handle number change
  const handleNumberChange = React.useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const newNumber = e.target.value;
      if (newNumber === "") {
        onChange(undefined);
      } else {
        const num = parseFloat(newNumber);
        if (!isNaN(num)) {
          onChange(formatValueWithUnit(num, unitValue));
        }
      }
    },
    [onChange, unitValue],
  );

  // Handle unit change
  const handleUnitChange = React.useCallback(
    (newUnit: string) => {
      if (numericValue === "") {
        // No number yet, just remember the unit for next input
        return;
      }
      const num = parseFloat(numericValue);
      if (!isNaN(num)) {
        onChange(formatValueWithUnit(num, newUnit as CSSUnit));
      }
    },
    [onChange, numericValue],
  );

  return (
    <div className={cn("flex items-center", className)}>
      <Input
        id={id}
        type="number"
        value={numericValue}
        onChange={handleNumberChange}
        placeholder={placeholder}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        className="h-8 rounded-r-none border-r-0 shadow-none focus-visible:z-10"
      />
      <Select value={unitValue} onValueChange={handleUnitChange} disabled={disabled}>
        <SelectTrigger size="sm" className="rounded-l-none shadow-none focus-visible:z-10">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {units.map((unit) => (
            <SelectItem key={unit} value={unit}>
              {unit}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

export { UnitInput };
export type { UnitInputProps };
