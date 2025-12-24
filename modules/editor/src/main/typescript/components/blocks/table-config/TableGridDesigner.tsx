import type { TableBlock } from "../../../types/template";

interface TableGridDesignerProps {
  config: TableBlock;
  selectedCells: Set<string>;
  onCellClick: (cellId: string, mode: "single" | "toggle" | "range") => void;
}

export function TableGridDesigner({ config, selectedCells, onCellClick }: TableGridDesignerProps) {
  const handleCellClick = (cellId: string, e: React.MouseEvent) => {
    if (e.shiftKey) {
      onCellClick(cellId, "range");
    } else if (e.ctrlKey || e.metaKey) {
      onCellClick(cellId, "toggle");
    } else {
      onCellClick(cellId, "single");
    }
  };

  // Calculate total columns (accounting for merged cells)
  const calculateColumnCount = (): number => {
    let maxCols = 0;
    config.rows.forEach((row) => {
      let colCount = 0;
      row.cells.forEach((cell) => {
        colCount += cell.colspan || 1;
      });
      maxCols = Math.max(maxCols, colCount);
    });
    return maxCols;
  };

  const columnCount = calculateColumnCount();

  return (
    <div className="space-y-4">
      <h3 className="text-sm font-medium text-gray-700">Table Structure</h3>

      <div className="border border-gray-300 rounded-lg overflow-auto bg-white p-4">
        <div className="flex">
          {/* Row labels column */}
          <div className="flex flex-col">
            <div className="h-6" /> {/* Spacer for column labels */}
            {config.rows.map((row, rowIndex) => (
              <div
                key={row.id}
                className="flex items-center justify-center px-3 py-2 text-xs text-gray-500 font-medium border-r border-gray-200 bg-gray-50"
                style={{ minHeight: "60px" }}
              >
                {rowIndex + 1}
              </div>
            ))}
          </div>

          {/* Table grid */}
          <div className="flex-1">
            {/* Column labels */}
            <div className="flex h-6 mb-1">
              {Array.from({ length: columnCount }, (_, i) => (
                <div key={i} className="flex-1 text-center text-xs text-gray-500 font-medium">
                  {String.fromCharCode(65 + i)}
                </div>
              ))}
            </div>

            {/* Table */}
            <table className="border-collapse w-full">
              <tbody>
                {config.rows.map((row) => (
                  <tr key={row.id}>
                    {row.cells.map((cell) => {
                      const isSelected = selectedCells.has(cell.id);
                      const colspan = cell.colspan || 1;
                      const rowspan = cell.rowspan || 1;
                      const isMerged = colspan > 1 || rowspan > 1;

                      return (
                        <td
                          key={cell.id}
                          colSpan={colspan}
                          rowSpan={rowspan}
                          onClick={(e) => handleCellClick(cell.id, e)}
                          className={`
                            relative p-4 border cursor-pointer
                            min-w-[80px] min-h-[60px]
                            transition-all
                            ${
                              isSelected
                                ? "bg-blue-100 border-blue-500 border-2 z-10"
                                : "bg-white hover:bg-gray-50 border-gray-300"
                            }
                            ${row.isHeader ? "bg-gray-100 font-semibold" : ""}
                          `}
                        >
                          {/* Cell content indicator */}
                          <div className="text-xs text-gray-400 text-center">
                            {cell.children.length > 0
                              ? `${cell.children.length} block${
                                  cell.children.length !== 1 ? "s" : ""
                                }`
                              : "Empty"}
                          </div>

                          {/* Header badge */}
                          {row.isHeader && (
                            <div className="absolute top-1 left-1 px-1 py-0.5 text-xs bg-indigo-100 text-indigo-700 rounded">
                              H
                            </div>
                          )}

                          {/* Merged cell badge */}
                          {isMerged && (
                            <div className="absolute top-1 right-1 px-1 py-0.5 text-xs bg-purple-100 text-purple-700 rounded">
                              {colspan}×{rowspan}
                            </div>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div className="text-xs text-gray-500 italic">
        Click to select • Shift+click for range • Ctrl/Cmd+click to toggle
      </div>
    </div>
  );
}
