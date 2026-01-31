import {v4 as uuidv4} from "uuid";
import type {TableBlock, TableCell, TableRow} from "../../../types/template";

/**
 * Create an empty table cell
 */
export function createEmptyCell(): TableCell {
  return {
    id: uuidv4(),
    children: [],
  };
}

/**
 * Add a new row to the end of the table
 */
export function addRow(config: TableBlock): TableBlock {
  const columnCount = config.rows[0]?.cells.length || 2;
  const newRow: TableRow = {
    id: uuidv4(),
    cells: Array.from({ length: columnCount }, () => createEmptyCell()),
    isHeader: false,
  };

  return {
    ...config,
    rows: [...config.rows, newRow],
  };
}

/**
 * Remove the last row from the table
 */
export function removeRow(config: TableBlock): TableBlock {
  if (config.rows.length <= 1) {
    return config; // Keep at least one row
  }

  return {
    ...config,
    rows: config.rows.slice(0, -1),
  };
}

/**
 * Add a new column to all rows
 */
export function addColumn(config: TableBlock): TableBlock {
  return {
    ...config,
    rows: config.rows.map((row) => ({
      ...row,
      cells: [...row.cells, createEmptyCell()],
    })),
    columnWidths: [...(config.columnWidths || []), 1],
  };
}

/**
 * Remove the last column from all rows
 */
export function removeColumn(config: TableBlock): TableBlock {
  const columnCount = config.rows[0]?.cells.length || 0;
  if (columnCount <= 1) {
    return config; // Keep at least one column
  }

  return {
    ...config,
    rows: config.rows.map((row) => ({
      ...row,
      cells: row.cells.slice(0, -1),
    })),
    columnWidths: config.columnWidths?.slice(0, -1),
  };
}

/**
 * Get cell position in the grid (accounting for colspan/rowspan)
 */
interface CellPosition {
  rowIndex: number;
  colIndex: number;
  cell: TableCell;
}

function getCellPositions(config: TableBlock): CellPosition[] {
  const positions: CellPosition[] = [];
  const occupiedCells = new Map<string, boolean>(); // "row,col" -> occupied

  config.rows.forEach((row, rowIndex) => {
    let colIndex = 0;
    row.cells.forEach((cell) => {
      // Find next available column in this row
      while (occupiedCells.get(`${rowIndex},${colIndex}`)) {
        colIndex++;
      }

      const colspan = cell.colspan || 1;
      const rowspan = cell.rowspan || 1;

      // Mark all cells as occupied
      for (let r = 0; r < rowspan; r++) {
        for (let c = 0; c < colspan; c++) {
          occupiedCells.set(`${rowIndex + r},${colIndex + c}`, true);
        }
      }

      positions.push({ rowIndex, colIndex, cell });
      colIndex += colspan;
    });
  });

  return positions;
}

/**
 * Check if selected cells form a rectangle (can be merged)
 */
export function canMergeCells(config: TableBlock, cellIds: Set<string>): boolean {
  if (cellIds.size < 2) return false;

  const positions = getCellPositions(config);
  const selectedPositions = positions.filter((p) => cellIds.has(p.cell.id));

  if (selectedPositions.length < 2) return false;

  // Find bounding box
  const minRow = Math.min(...selectedPositions.map((p) => p.rowIndex));
  const maxRow = Math.max(...selectedPositions.map((p) => p.rowIndex));
  const minCol = Math.min(...selectedPositions.map((p) => p.colIndex));
  const maxCol = Math.max(...selectedPositions.map((p) => p.colIndex));

  // Check if all cells in the rectangle are selected
  const expectedCells = (maxRow - minRow + 1) * (maxCol - minCol + 1);
  if (cellIds.size !== expectedCells) return false;

  // Verify all selected cells are contiguous
  for (let r = minRow; r <= maxRow; r++) {
    for (let c = minCol; c <= maxCol; c++) {
      const cellAtPos = positions.find((p) => p.rowIndex === r && p.colIndex === c);
      if (!cellAtPos || !cellIds.has(cellAtPos.cell.id)) {
        return false;
      }
    }
  }

  return true;
}

/**
 * Merge selected cells into one
 */
export function mergeCells(config: TableBlock, cellIds: Set<string>): TableBlock {
  if (!canMergeCells(config, cellIds)) {
    return config; // Cannot merge
  }

  const positions = getCellPositions(config);
  const selectedPositions = positions.filter((p) => cellIds.has(p.cell.id));

  // Find bounding box
  const minRow = Math.min(...selectedPositions.map((p) => p.rowIndex));
  const maxRow = Math.max(...selectedPositions.map((p) => p.rowIndex));
  const minCol = Math.min(...selectedPositions.map((p) => p.colIndex));
  const maxCol = Math.max(...selectedPositions.map((p) => p.colIndex));

  const colspan = maxCol - minCol + 1;
  const rowspan = maxRow - minRow + 1;

  // Collect all children from merged cells
  const allChildren = selectedPositions.flatMap((p) => p.cell.children);

  // Find the top-left cell (this will be the merged cell)
  const topLeftCell = selectedPositions.find((p) => p.rowIndex === minRow && p.colIndex === minCol);
  if (!topLeftCell) return config;

  // Update table: set colspan/rowspan on top-left cell, remove others
  return {
    ...config,
    rows: config.rows.map((row, rowIndex) => {
      if (rowIndex < minRow || rowIndex > maxRow) {
        return row; // Row outside merge range
      }

      return {
        ...row,
        cells: row.cells
          .map((cell) => {
            if (cell.id === topLeftCell.cell.id) {
              // This is the top-left cell - make it the merged cell
              return {
                ...cell,
                colspan: colspan > 1 ? colspan : undefined,
                rowspan: rowspan > 1 ? rowspan : undefined,
                children: allChildren,
              };
            } else if (cellIds.has(cell.id) && cell.id !== topLeftCell.cell.id) {
              // This cell is part of the merge but not top-left - mark for removal
              return null;
            }
            return cell;
          })
          .filter((cell): cell is TableCell => cell !== null),
      };
    }),
  };
}

/**
 * Split a merged cell back to individual cells
 */
export function splitCell(config: TableBlock, cellId: string): TableBlock {
  const positions = getCellPositions(config);
  const cellPos = positions.find((p) => p.cell.id === cellId);
  if (!cellPos) return config;

  const cell = cellPos.cell;
  const colspan = cell.colspan || 1;
  const rowspan = cell.rowspan || 1;

  if (colspan === 1 && rowspan === 1) {
    return config; // Already a single cell
  }

  // Split: create new individual cells
  const newCells: TableCell[][] = [];
  for (let r = 0; r < rowspan; r++) {
    newCells[r] = [];
    for (let c = 0; c < colspan; c++) {
      if (r === 0 && c === 0) {
        // Keep original cell but reset colspan/rowspan
        newCells[r][c] = {
          ...cell,
          colspan: undefined,
          rowspan: undefined,
        };
      } else {
        // Create new empty cells
        newCells[r][c] = createEmptyCell();
      }
    }
  }

  // Insert the new cells back into the table
  return {
    ...config,
    rows: config.rows.map((row, rowIndex) => {
      if (rowIndex < cellPos.rowIndex || rowIndex > cellPos.rowIndex + rowspan - 1) {
        return row;
      }

      const relativeRowIndex = rowIndex - cellPos.rowIndex;
      const cellsToInsert = newCells[relativeRowIndex];

      return {
        ...row,
        cells: row.cells.flatMap((c) => {
          if (c.id === cellId) {
            // Replace the merged cell with new cells for this row
            return cellsToInsert;
          }
          return [c];
        }),
      };
    }),
  };
}

/**
 * Toggle header status for rows containing selected cells
 */
export function toggleRowHeader(config: TableBlock, cellIds: Set<string>): TableBlock {
  if (cellIds.size === 0) return config;

  // Find which rows contain selected cells
  const rowsToToggle = new Set<string>();
  config.rows.forEach((row) => {
    const hasSelectedCell = row.cells.some((cell) => cellIds.has(cell.id));
    if (hasSelectedCell) {
      rowsToToggle.add(row.id);
    }
  });

  return {
    ...config,
    rows: config.rows.map((row) => {
      if (rowsToToggle.has(row.id)) {
        return {
          ...row,
          isHeader: !row.isHeader,
        };
      }
      return row;
    }),
  };
}
