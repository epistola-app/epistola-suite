import type { TableBlock } from "../../../types/template";
import { Button } from "../../ui/button";
import { canMergeCells } from "./tableConfigUtils";

interface CellActionsControlsProps {
  config: TableBlock;
  selectedCells: Set<string>;
  onMergeCells: () => void;
  onSplitCell: () => void;
  onToggleHeader: () => void;
}

export function CellActionsControls({
  config,
  selectedCells,
  onMergeCells,
  onSplitCell,
  onToggleHeader,
}: CellActionsControlsProps) {
  const selectionCount = selectedCells.size;
  const canMerge = canMergeCells(config, selectedCells);

  // Check if selected cell is merged (has colspan or rowspan)
  const isMergedCell =
    selectionCount === 1 &&
    config.rows.some((row) =>
      row.cells.some(
        (cell) =>
          selectedCells.has(cell.id) &&
          ((cell.colspan && cell.colspan > 1) || (cell.rowspan && cell.rowspan > 1)),
      ),
    );

  // Check if any selected cells are in header rows
  const hasHeaderRows = config.rows.some(
    (row) => row.isHeader && row.cells.some((cell) => selectedCells.has(cell.id)),
  );

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-medium text-foreground">Cell Actions</h3>

      {selectionCount === 0 && (
        <div className="text-sm text-muted-foreground italic">
          Select cells in the grid to perform actions
        </div>
      )}

      {selectionCount > 0 && (
        <div className="space-y-2">
          <div className="text-xs text-muted-foreground">
            {selectionCount} cell{selectionCount !== 1 ? "s" : ""} selected
          </div>

          {/* Merge Cells */}
          <Button
            variant="outline"
            className={`w-full justify-center ${
              canMerge
                ? "border-blue-500 bg-blue-50 text-blue-700 hover:bg-blue-100 hover:text-blue-800"
                : ""
            }`}
            onClick={onMergeCells}
            disabled={!canMerge}
            title={
              canMerge ? "Merge selected cells" : "Select a rectangular group of cells to merge"
            }
          >
            Merge Cells
          </Button>

          {/* Split Cell */}
          <Button
            variant="outline"
            className={`w-full justify-center ${
              isMergedCell
                ? "border-orange-500 bg-orange-50 text-orange-700 hover:bg-orange-100 hover:text-orange-800"
                : ""
            }`}
            onClick={onSplitCell}
            disabled={!isMergedCell}
            title={
              isMergedCell
                ? "Split merged cell back to individual cells"
                : "Select a single merged cell to split"
            }
          >
            Split Cell
          </Button>

          {/* Toggle Header */}
          <Button
            variant="outline"
            className="w-full justify-center"
            onClick={onToggleHeader}
            title={
              hasHeaderRows
                ? "Remove header styling from selected rows"
                : "Make selected rows header rows"
            }
          >
            {hasHeaderRows ? "Unset" : "Set as"} Header Row
          </Button>
        </div>
      )}
    </div>
  );
}
