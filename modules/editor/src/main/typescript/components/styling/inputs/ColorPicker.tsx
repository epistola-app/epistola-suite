interface ColorPickerProps {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  label?: string;
  allowEmpty?: boolean;
}

export function ColorPicker({ value, onChange, label, allowEmpty = true }: ColorPickerProps) {
  const displayValue = value || "#000000";

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    onChange(e.target.value);
  };

  const handleClear = () => {
    if (allowEmpty) {
      onChange(undefined);
    }
  };

  return (
    <div className="flex flex-col gap-1">
      {label && <label className="text-xs text-gray-500">{label}</label>}
      <div className="flex items-center gap-2">
        <div className="relative">
          <input
            type="color"
            value={displayValue}
            onChange={handleChange}
            className="w-8 h-8 cursor-pointer border border-gray-200 rounded"
          />
        </div>
        <input
          type="text"
          value={value || ""}
          onChange={(e) => onChange(e.target.value || undefined)}
          placeholder="#000000"
          className="flex-1 px-2 py-1 text-sm font-mono border border-gray-200 rounded
                     focus:outline-none focus:border-blue-400"
        />
        {allowEmpty && value && (
          <button
            type="button"
            onClick={handleClear}
            className="px-2 py-1 text-xs text-gray-400 hover:text-gray-600"
            title="Clear color"
          >
            x
          </button>
        )}
      </div>
    </div>
  );
}
