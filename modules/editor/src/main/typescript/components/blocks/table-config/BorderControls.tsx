import type {TableBlock} from "../../../types/template";
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from "../../ui/select";

interface BorderControlsProps {
  config: TableBlock;
  onChange: (borderStyle: "none" | "all" | "horizontal" | "vertical") => void;
}

export function BorderControls({ config, onChange }: BorderControlsProps) {
  const borderStyle = config.borderStyle || "all";

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-medium text-foreground">Borders</h3>

      <Select value={borderStyle} onValueChange={onChange}>
        <SelectTrigger className="w-full bg-white">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="none">None</SelectItem>
          <SelectItem value="all">All Borders</SelectItem>
          <SelectItem value="horizontal">Horizontal Only</SelectItem>
          <SelectItem value="vertical">Vertical Only</SelectItem>
        </SelectContent>
      </Select>

      {/* Visual preview */}
      <div className="p-4 bg-muted rounded-md">
        <div className="text-xs text-muted-foreground mb-2">Preview:</div>
        <div
          className={`
            w-full h-16 bg-background
            ${borderStyle === "all" ? "border border-border" : ""}
            ${borderStyle === "horizontal" ? "border-t border-b border-border" : ""}
            ${borderStyle === "vertical" ? "border-l border-r border-border" : ""}
          `}
        >
          <div className="grid grid-cols-2 grid-rows-2 h-full">
            {[1, 2, 3, 4].map((i) => (
              <div
                key={i}
                className={`
                  ${borderStyle === "all" ? "border border-border" : ""}
                  ${borderStyle === "horizontal" ? "border-b border-border" : ""}
                  ${borderStyle === "vertical" ? "border-r border-border" : ""}
                `}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
