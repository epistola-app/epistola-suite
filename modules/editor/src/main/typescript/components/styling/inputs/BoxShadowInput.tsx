import { useState, useEffect } from "react";
import { ColorPicker } from "./ColorPicker";

interface BoxShadowInputProps {
  value: string | undefined;
  onChange: (value: string | undefined) => void;
  label?: string;
}

interface ShadowValues {
  x: number;
  y: number;
  blur: number;
  spread: number;
  color: string;
}

function parseShadow(value: string | undefined): ShadowValues {
  if (!value || value === "none") {
    return { x: 0, y: 0, blur: 0, spread: 0, color: "#00000033" };
  }
  // Parse box-shadow: Xpx Ypx Blur Spread Color
  const match = value.match(
    /(-?\d+)px\s+(-?\d+)px\s+(\d+)px\s+(\d+)px\s+(#[0-9a-fA-F]{6,8}|rgba?\([^)]+\))/,
  );
  if (match) {
    return {
      x: parseInt(match[1]),
      y: parseInt(match[2]),
      blur: parseInt(match[3]),
      spread: parseInt(match[4]),
      color: match[5],
    };
  }
  return { x: 0, y: 4, blur: 6, spread: 0, color: "#00000033" };
}

function formatShadow(values: ShadowValues): string {
  if (values.x === 0 && values.y === 0 && values.blur === 0 && values.spread === 0) {
    return "none";
  }
  return `${values.x}px ${values.y}px ${values.blur}px ${values.spread}px ${values.color}`;
}

export function BoxShadowInput({ value, onChange, label }: BoxShadowInputProps) {
  const [values, setValues] = useState<ShadowValues>(() => parseShadow(value));
  const [enabled, setEnabled] = useState(value !== undefined && value !== "none");

  useEffect(() => {
    const parsed = parseShadow(value);
    setValues(parsed);
    setEnabled(value !== undefined && value !== "none");
  }, [value]);

  const handleChange = (key: keyof ShadowValues, newValue: number | string) => {
    const updated = { ...values, [key]: newValue };
    setValues(updated);
    if (enabled) {
      onChange(formatShadow(updated));
    }
  };

  const handleToggle = () => {
    const newEnabled = !enabled;
    setEnabled(newEnabled);
    if (newEnabled) {
      onChange(formatShadow(values));
    } else {
      onChange(undefined);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      {label && (
        <div className="flex items-center justify-between">
          <label className="text-xs text-gray-500">{label}</label>
          <button
            type="button"
            onClick={handleToggle}
            className={`text-xs px-2 py-0.5 rounded ${
              enabled ? "bg-blue-100 text-blue-600" : "bg-gray-100 text-gray-500 hover:bg-gray-200"
            }`}
          >
            {enabled ? "On" : "Off"}
          </button>
        </div>
      )}

      {enabled && (
        <>
          {/* Preview */}
          <div
            className="h-8 bg-white border border-gray-200 rounded"
            style={{ boxShadow: formatShadow(values) }}
          />

          <div className="grid grid-cols-2 gap-2">
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-400">X Offset</label>
              <input
                type="number"
                value={values.x}
                onChange={(e) => handleChange("x", parseInt(e.target.value) || 0)}
                className="w-full px-2 py-1 text-xs border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-400">Y Offset</label>
              <input
                type="number"
                value={values.y}
                onChange={(e) => handleChange("y", parseInt(e.target.value) || 0)}
                className="w-full px-2 py-1 text-xs border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-400">Blur</label>
              <input
                type="number"
                value={values.blur}
                onChange={(e) => handleChange("blur", parseInt(e.target.value) || 0)}
                min={0}
                className="w-full px-2 py-1 text-xs border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400"
              />
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs text-gray-400">Spread</label>
              <input
                type="number"
                value={values.spread}
                onChange={(e) => handleChange("spread", parseInt(e.target.value) || 0)}
                className="w-full px-2 py-1 text-xs border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400"
              />
            </div>
          </div>
          <ColorPicker
            value={values.color}
            onChange={(c) => handleChange("color", c || "#00000033")}
            label="Shadow Color"
            allowEmpty={false}
          />
        </>
      )}
    </div>
  );
}
