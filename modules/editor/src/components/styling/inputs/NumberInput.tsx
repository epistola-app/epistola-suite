import { useState, useEffect } from 'react';
import type { CSSUnit } from '../../../types/styles';
import { parseValueWithUnit, formatValueWithUnit } from '../../../types/styles';

interface NumberInputProps {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  units?: CSSUnit[];
  defaultUnit?: CSSUnit;
  min?: number;
  max?: number;
  step?: number;
  placeholder?: string;
  label?: string;
  allowEmpty?: boolean;
}

export function NumberInput({
  value,
  onChange,
  units = ['px', 'em', 'rem', '%'],
  defaultUnit = 'px',
  min,
  max,
  step = 1,
  placeholder = '0',
  label,
  allowEmpty = true,
}: NumberInputProps) {
  const parsed = parseValueWithUnit(value);
  const [numericValue, setNumericValue] = useState<string>(
    parsed ? String(parsed.value) : ''
  );
  const [unit, setUnit] = useState<CSSUnit>(parsed?.unit || defaultUnit);

  // Sync with external value changes
  useEffect(() => {
    const parsed = parseValueWithUnit(value);
    if (parsed) {
      setNumericValue(String(parsed.value));
      setUnit(parsed.unit);
    } else if (!value) {
      setNumericValue('');
    }
  }, [value]);

  const handleNumericChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = e.target.value;
    setNumericValue(newValue);

    if (newValue === '' && allowEmpty) {
      onChange(undefined);
    } else {
      const num = parseFloat(newValue);
      if (!isNaN(num)) {
        onChange(formatValueWithUnit(num, unit));
      }
    }
  };

  const handleUnitChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newUnit = e.target.value as CSSUnit;
    setUnit(newUnit);

    if (numericValue !== '') {
      const num = parseFloat(numericValue);
      if (!isNaN(num)) {
        onChange(formatValueWithUnit(num, newUnit));
      }
    }
  };

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label className="text-xs text-gray-500">{label}</label>
      )}
      <div className="flex">
        <input
          type="number"
          value={numericValue}
          onChange={handleNumericChange}
          min={min}
          max={max}
          step={step}
          placeholder={placeholder}
          className="w-full px-2 py-1 text-sm border border-gray-200 rounded-l
                     focus:outline-none focus:border-blue-400
                     [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
        />
        {units.length > 1 ? (
          <select
            value={unit}
            onChange={handleUnitChange}
            className="px-1 py-1 text-sm bg-gray-50 border border-l-0 border-gray-200 rounded-r
                       focus:outline-none focus:border-blue-400 cursor-pointer"
          >
            {units.map((u) => (
              <option key={u} value={u}>
                {u}
              </option>
            ))}
          </select>
        ) : (
          <span className="px-2 py-1 text-sm bg-gray-50 border border-l-0 border-gray-200 rounded-r text-gray-500">
            {units[0]}
          </span>
        )}
      </div>
    </div>
  );
}
