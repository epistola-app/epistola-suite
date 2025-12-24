import { NumberInput } from "./NumberInput";
import { SelectInput } from "./SelectInput";
import { ColorPicker } from "./ColorPicker";
import { BORDER_STYLES } from "../../../types/styles";

interface BorderInputProps {
  width: string | undefined;
  style: string | undefined;
  color: string | undefined;
  radius: string | undefined;
  onWidthChange: (value: string | undefined) => void;
  onStyleChange: (value: string | undefined) => void;
  onColorChange: (value: string | undefined) => void;
  onRadiusChange: (value: string | undefined) => void;
  label?: string;
}

export function BorderInput({
  width,
  style,
  color,
  radius,
  onWidthChange,
  onStyleChange,
  onColorChange,
  onRadiusChange,
  label,
}: BorderInputProps) {
  return (
    <div className="flex flex-col gap-3">
      {label && <label className="text-xs text-gray-500 font-medium">{label}</label>}
      <div className="grid grid-cols-2 gap-2">
        <NumberInput
          value={width}
          onChange={onWidthChange}
          label="Width"
          units={["px"]}
          defaultUnit="px"
          min={0}
          placeholder="0"
        />
        <SelectInput
          value={style}
          onChange={onStyleChange}
          options={BORDER_STYLES}
          label="Style"
          placeholder="none"
        />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <ColorPicker value={color} onChange={onColorChange} label="Color" />
        <NumberInput
          value={radius}
          onChange={onRadiusChange}
          label="Radius"
          units={["px", "%"]}
          defaultUnit="px"
          min={0}
          placeholder="0"
        />
      </div>
    </div>
  );
}
