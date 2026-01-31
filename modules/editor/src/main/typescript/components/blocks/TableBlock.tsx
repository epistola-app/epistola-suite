import {useState} from "react";
import {useDroppable} from "@dnd-kit/core";
import {Settings2} from "lucide-react";
import type {TableBlock, TableCell} from "../../types/template";
import {useEditorStore} from "../../store/editorStore";
import {BlockRenderer} from "./BlockRenderer";
import {TableConfigPopup} from "./TableConfigPopup";
import {BlockHeader} from "./BlockHeader";
import {Button} from "../ui/button";

interface TableBlockProps {
  block: TableBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

export function TableBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: TableBlockProps) {
  const updateBlock = useEditorStore((state) => state.updateBlock);
  const [configOpen, setConfigOpen] = useState(false);

  const borderStyle = block.borderStyle || "all";

  const handleConfigChange = (newConfig: TableBlock) => {
    updateBlock(block.id, newConfig);
  };

  const getBorderClasses = () => {
    switch (borderStyle) {
      case "all":
        return "border border-gray-300";
      case "horizontal":
        return "border-t border-b border-gray-300";
      case "vertical":
        return "border-l border-r border-gray-300";
      case "none":
      default:
        return "";
    }
  };

  const getCellBorderClasses = () => {
    switch (borderStyle) {
      case "all":
        return "border border-gray-300";
      case "horizontal":
        return "border-b border-gray-300";
      case "vertical":
        return "border-r border-gray-300";
      case "none":
      default:
        return "";
    }
  };

  return (
    <>
      <TableConfigPopup
        isOpen={configOpen}
        config={block}
        onClose={() => setConfigOpen(false)}
        onChange={handleConfigChange}
      />
      <div
        className={`rounded-lg border ${isSelected ? "bg-gray-50 border-dashed border-gray-300" : "border-transparent"}`}
      >
        <BlockHeader
          title="TABLE"
          isSelected={isSelected}
          dragAttributes={dragAttributes}
          dragListeners={dragListeners}
          onDelete={onDelete}
        />
        {isSelected && (
          <div className="flex justify-end p-2 text-base!">
            <Button variant="indigo" size="sm" onClick={() => setConfigOpen(true)}>
              <Settings2 className="size-4" />
              Configure Table
            </Button>
          </div>
        )}
        <div className="p-2 overflow-auto">
          <table className={`w-full ${getBorderClasses()}`} style={block.styles}>
            <tbody>
              {block.rows.map((row, rowIndex) => {
                // Track which cells are occupied by previous cells' colspan/rowspan
                const occupiedCells = new Set<number>();

                // Check cells from previous rows for rowspan
                for (let prevRowIdx = 0; prevRowIdx < rowIndex; prevRowIdx++) {
                  const prevRow = block.rows[prevRowIdx];
                  let colIdx = 0;
                  prevRow.cells.forEach((prevCell) => {
                    const rowspan = prevCell.rowspan || 1;
                    const colspan = prevCell.colspan || 1;

                    // If this cell spans into the current row
                    if (prevRowIdx + rowspan > rowIndex) {
                      for (let c = 0; c < colspan; c++) {
                        occupiedCells.add(colIdx + c);
                      }
                    }
                    colIdx += colspan;
                  });
                }

                return (
                  <tr key={row.id}>
                    {row.cells.map((cell, cellIndex) => {
                      // Calculate actual column index accounting for previous cells' colspan
                      let actualColIndex = 0;
                      for (let i = 0; i < cellIndex; i++) {
                        actualColIndex += row.cells[i].colspan || 1;
                      }

                      // Skip this cell if it's occupied by a previous cell's merge
                      if (occupiedCells.has(actualColIndex)) {
                        return null;
                      }

                      const CellTag = row.isHeader ? "th" : "td";
                      const colspan = cell.colspan || 1;
                      const rowspan = cell.rowspan || 1;

                      return (
                        <CellTag
                          key={cell.id}
                          colSpan={colspan}
                          rowSpan={rowspan}
                          className={`p-2 ${getCellBorderClasses()} relative align-top`}
                          style={cell.styles}
                        >
                          <TableCellComponent
                            block={block}
                            rowId={row.id}
                            cell={cell}
                            isSelected={isSelected}
                          />
                        </CellTag>
                      );
                    })}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

interface TableCellComponentProps {
  block: TableBlock;
  rowId: string;
  cell: TableCell;
  isSelected: boolean;
}

function TableCellComponent({ block, rowId, cell }: TableCellComponentProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: `cell-${block.id}-${rowId}-${cell.id}`,
    data: {
      type: "cell",
      parentId: `${block.id}::${rowId}::${cell.id}`,
      index: cell.children.length,
    },
  });

  return (
    <div
      ref={setNodeRef}
      className={`
          min-h-10
          ${isOver ? "bg-blue-50" : ""}
          ${cell.children.length === 0 ? "flex items-center justify-center" : ""}
        `}
    >
      {cell.children.length === 0 ? (
        <span className="text-gray-400 text-sm">Drop blocks here</span>
      ) : (
        <div className="space-y-1">
          {cell.children.map((child, index) => (
            <BlockRenderer
              key={child.id}
              block={child}
              index={index}
              parentId={`${block.id}::${rowId}::${cell.id}`}
            />
          ))}
        </div>
      )}
    </div>
  );
}
