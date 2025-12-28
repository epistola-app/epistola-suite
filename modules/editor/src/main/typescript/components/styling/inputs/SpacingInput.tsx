import { useState } from "react";
import type { CSSUnit } from "../../../types/styles";
import { parseValueWithUnit, formatValueWithUnit } from "../../../types/styles";

interface SpacingInputProps {
  top: string | undefined;
  right: string | undefined;
  bottom: string | undefined;
  left: string | undefined;
  onChange: (side: "top" | "right" | "bottom" | "left", value: string | undefined) => void;
  label?: string;
  units?: CSSUnit[];
  defaultUnit?: CSSUnit;
}

export function SpacingInput({
  top,
  right,
  bottom,
  left,
  onChange,
  label,
  units = ["px", "em", "rem", "%"],
  defaultUnit = "px",
}: SpacingInputProps) {
  const [linked, setLinked] = useState(false);
  const [unit, setUnit] = useState<CSSUnit>(defaultUnit);

  const parseValue = (val: string | undefined) => {
    const parsed = parseValueWithUnit(val);
    return parsed ? String(parsed.value) : "";
  };

  const handleValueChange = (side: "top" | "right" | "bottom" | "left", numericValue: string) => {
    if (numericValue === "") {
      if (linked) {
        onChange("top", undefined);
        onChange("right", undefined);
        onChange("bottom", undefined);
        onChange("left", undefined);
      } else {
        onChange(side, undefined);
      }
    } else {
      const num = parseFloat(numericValue);
      if (!isNaN(num)) {
        const formatted = formatValueWithUnit(num, unit);
        if (linked) {
          onChange("top", formatted);
          onChange("right", formatted);
          onChange("bottom", formatted);
          onChange("left", formatted);
        } else {
          onChange(side, formatted);
        }
      }
    }
  };

  const handleUnitChange = (newUnit: CSSUnit) => {
    setUnit(newUnit);
    // Update all values with the new unit
    const sides = ["top", "right", "bottom", "left"] as const;
    const values = [top, right, bottom, left];
    sides.forEach((side, i) => {
      const parsed = parseValueWithUnit(values[i]);
      if (parsed) {
        onChange(side, formatValueWithUnit(parsed.value, newUnit));
      }
    });
  };

  return (
    <div className="flex flex-col gap-2">
      {label && (
        <div className="flex items-center justify-between">
          <label className="text-xs text-gray-500">{label}</label>
          <button
            type="button"
            onClick={() => setLinked(!linked)}
            className={`text-xs px-2 py-0.5 rounded ${
              linked ? "bg-blue-100 text-blue-600" : "bg-gray-100 text-gray-500 hover:bg-gray-200"
            }`}
            title={linked ? "Unlink sides" : "Link all sides"}
          >
            {linked ? "Linked" : "Link"}
          </button>
        </div>
      )}

      <div className="grid grid-cols-3 gap-1 items-center">
        {/* Top */}
        <div className="col-start-2">
          <input
            type="number"
            value={parseValue(top)}
            onChange={(e) => handleValueChange("top", e.target.value)}
            placeholder="0"
            className="w-full px-1 py-0.5 text-xs text-center border border-gray-200 rounded
                       focus:outline-none focus:border-blue-400
                       [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
          />
        </div>

        {/* Left */}
        <div className="col-start-1 row-start-2">
          <input
            type="number"
            value={parseValue(left)}
            onChange={(e) => handleValueChange("left", e.target.value)}
            placeholder="0"
            className="w-full px-1 py-0.5 text-xs text-center border border-gray-200 rounded
                       focus:outline-none focus:border-blue-400
                       [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
          />
        </div>

        {/* Center - unit selector */}
        <div className="col-start-2 row-start-2 flex justify-center">
          <select
            value={unit}
            onChange={(e) => handleUnitChange(e.target.value as CSSUnit)}
            className="px-1 py-0.5 text-xs bg-gray-50 border border-gray-200 rounded
                       focus:outline-none cursor-pointer"
          >
            {units.map((u) => (
              <option key={u} value={u}>
                {u}
              </option>
            ))}
          </select>
        </div>

        {/* Right */}
        <div className="col-start-3 row-start-2">
          <input
            type="number"
            value={parseValue(right)}
            onChange={(e) => handleValueChange("right", e.target.value)}
            placeholder="0"
            className="w-full px-1 py-0.5 text-xs text-center border border-gray-200 rounded
                       focus:outline-none focus:border-blue-400
                       [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
          />
        </div>

        {/* Bottom */}
        <div className="col-start-2 row-start-3">
          <input
            type="number"
            value={parseValue(bottom)}
            onChange={(e) => handleValueChange("bottom", e.target.value)}
            placeholder="0"
            className="w-full px-1 py-0.5 text-xs text-center border border-gray-200 rounded
                       focus:outline-none focus:border-blue-400
                       [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
          />
        </div>
      </div>
    </div>
  );
}
