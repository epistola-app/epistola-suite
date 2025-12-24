import { Minus, Plus } from "lucide-react";
import type { TableBlock } from "../../../types/template";
import { Button } from "../../ui/button";

interface TableSizeControlsProps {
  config: TableBlock;
  onAddRow: () => void;
  onRemoveRow: () => void;
  onAddColumn: () => void;
  onRemoveColumn: () => void;
}

export function TableSizeControls({
  config,
  onAddRow,
  onRemoveRow,
  onAddColumn,
  onRemoveColumn,
}: TableSizeControlsProps) {
  const rowCount = config.rows.length;
  const columnCount = config.rows[0]?.cells.length || 0;
  const canRemoveRow = rowCount > 1;
  const canRemoveColumn = columnCount > 1;

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-medium text-foreground">Table Size</h3>

      {/* Rows */}
      <div className="flex items-center gap-2">
        <label className="text-sm text-muted-foreground w-20">Rows:</label>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="icon-xs"
            onClick={onRemoveRow}
            disabled={!canRemoveRow}
            title={canRemoveRow ? "Remove row" : "Must have at least 1 row"}
          >
            <Minus className="size-3" />
          </Button>
          <span className="text-sm font-medium text-foreground w-8 text-center">{rowCount}</span>
          <Button variant="outline" size="icon-xs" onClick={onAddRow} title="Add row">
            <Plus className="size-3" />
          </Button>
        </div>
      </div>

      {/* Columns */}
      <div className="flex items-center gap-2">
        <label className="text-sm text-muted-foreground w-20">Columns:</label>
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="icon-xs"
            onClick={onRemoveColumn}
            disabled={!canRemoveColumn}
            title={canRemoveColumn ? "Remove column" : "Must have at least 1 column"}
          >
            <Minus className="size-3" />
          </Button>
          <span className="text-sm font-medium text-foreground w-8 text-center">{columnCount}</span>
          <Button variant="outline" size="icon-xs" onClick={onAddColumn} title="Add column">
            <Plus className="size-3" />
          </Button>
        </div>
      </div>
    </div>
  );
}
