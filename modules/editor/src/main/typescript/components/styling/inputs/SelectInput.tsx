interface SelectOption {
  label: string;
  value: string;
}

interface SelectInputProps {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  options: readonly SelectOption[];
  label?: string;
  placeholder?: string;
  allowEmpty?: boolean;
}

export function SelectInput({
  value,
  onChange,
  options,
  label,
  placeholder = "Select...",
  allowEmpty = true,
}: SelectInputProps) {
  const handleChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const newValue = e.target.value;
    onChange(newValue === "" ? undefined : newValue);
  };

  return (
    <div className="flex flex-col gap-1">
      {label && <label className="text-xs text-gray-500">{label}</label>}
      <select
        value={value || ""}
        onChange={handleChange}
        className="w-full px-2 py-1 text-sm border border-gray-200 rounded
                   focus:outline-none focus:border-blue-400 cursor-pointer bg-white"
      >
        {allowEmpty && <option value="">{placeholder}</option>}
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
