interface ToggleOption {
  label: string;
  value: string;
}

interface ToggleGroupProps {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  options: readonly ToggleOption[];
  label?: string;
  allowDeselect?: boolean;
}

export function ToggleGroup({
  value,
  onChange,
  options,
  label,
  allowDeselect = true,
}: ToggleGroupProps) {
  const handleClick = (optionValue: string) => {
    if (value === optionValue && allowDeselect) {
      onChange(undefined);
    } else {
      onChange(optionValue);
    }
  };

  return (
    <div className="flex flex-col gap-1">
      {label && (
        <label className="text-xs text-gray-500">{label}</label>
      )}
      <div className="flex border border-gray-200 rounded overflow-hidden">
        {options.map((option, index) => (
          <button
            key={option.value}
            type="button"
            onClick={() => handleClick(option.value)}
            className={`
              flex-1 px-2 py-1 text-xs font-medium transition-colors
              ${index > 0 ? 'border-l border-gray-200' : ''}
              ${
                value === option.value
                  ? 'bg-blue-500 text-white'
                  : 'bg-white text-gray-600 hover:bg-gray-50'
              }
            `}
            title={option.label}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}
