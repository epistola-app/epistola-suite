interface SliderInputProps {
  value: number | undefined;
  onChange: (value: number | undefined) => void;
  min?: number;
  max?: number;
  step?: number;
  label?: string;
  showValue?: boolean;
  formatValue?: (value: number) => string;
}

export function SliderInput({
  value,
  onChange,
  min = 0,
  max = 1,
  step = 0.1,
  label,
  showValue = true,
  formatValue = (v) => v.toFixed(1),
}: SliderInputProps) {
  const displayValue = value ?? max;

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const newValue = parseFloat(e.target.value);
    onChange(newValue);
  };

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <div className="flex items-center justify-between">
          <label className="text-xs text-gray-500">{label}</label>
          {showValue && (
            <span className="text-xs font-mono text-gray-600">
              {formatValue(displayValue)}
            </span>
          )}
        </div>
      )}
      <input
        type="range"
        value={displayValue}
        onChange={handleChange}
        min={min}
        max={max}
        step={step}
        className="w-full h-2 bg-gray-200 rounded-lg appearance-none cursor-pointer
                   [&::-webkit-slider-thumb]:appearance-none
                   [&::-webkit-slider-thumb]:w-4
                   [&::-webkit-slider-thumb]:h-4
                   [&::-webkit-slider-thumb]:bg-blue-500
                   [&::-webkit-slider-thumb]:rounded-full
                   [&::-webkit-slider-thumb]:cursor-pointer
                   [&::-webkit-slider-thumb]:hover:bg-blue-600"
      />
    </div>
  );
}
