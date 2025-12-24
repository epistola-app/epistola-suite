import type { TableBlock } from "../../../types/template";

interface BorderControlsProps {
  config: TableBlock;
  onChange: (borderStyle: "none" | "all" | "horizontal" | "vertical") => void;
}

export function BorderControls({ config, onChange }: BorderControlsProps) {
  const borderStyle = config.borderStyle || "all";

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-medium text-gray-700">Borders</h3>

      <div>
        <select
          value={borderStyle}
          onChange={(e) => onChange(e.target.value as any)}
          className="w-full px-3 py-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:border-blue-400"
        >
          <option value="none">None</option>
          <option value="all">All Borders</option>
          <option value="horizontal">Horizontal Only</option>
          <option value="vertical">Vertical Only</option>
        </select>
      </div>

      {/* Visual preview */}
      <div className="p-4 bg-gray-50 rounded-md">
        <div className="text-xs text-gray-500 mb-2">Preview:</div>
        <div
          className={`
            w-full h-16 bg-white
            ${borderStyle === "all" ? "border border-gray-400" : ""}
            ${borderStyle === "horizontal" ? "border-t border-b border-gray-400" : ""}
            ${borderStyle === "vertical" ? "border-l border-r border-gray-400" : ""}
          `}
        >
          <div className="grid grid-cols-2 grid-rows-2 h-full">
            {[1, 2, 3, 4].map((i) => (
              <div
                key={i}
                className={`
                  ${borderStyle === "all" ? "border border-gray-300" : ""}
                  ${borderStyle === "horizontal" ? "border-b border-gray-300" : ""}
                  ${borderStyle === "vertical" ? "border-r border-gray-300" : ""}
                `}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
