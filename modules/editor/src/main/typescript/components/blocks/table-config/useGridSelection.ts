import {useCallback, useState} from "react";

export interface GridSelectionOptions {
  onSelectionChange?: (selectedCells: Set<string>) => void;
}

export function useGridSelection(options: GridSelectionOptions = {}) {
  const [selectedCells, setSelectedCells] = useState<Set<string>>(new Set());
  const [lastSelectedCell, setLastSelectedCell] = useState<string | null>(null);

  const selectCell = useCallback(
    (cellId: string, mode: "single" | "toggle" | "range" = "single") => {
      setSelectedCells((prev) => {
        const newSelection = new Set(prev);

        switch (mode) {
          case "single":
            // Replace selection with this cell
            newSelection.clear();
            newSelection.add(cellId);
            setLastSelectedCell(cellId);
            break;

          case "toggle":
            // Toggle this cell (Ctrl/Cmd+click)
            if (newSelection.has(cellId)) {
              newSelection.delete(cellId);
            } else {
              newSelection.add(cellId);
            }
            setLastSelectedCell(cellId);
            break;

          case "range":
            // Range selection (Shift+click)
            // Note: Range selection would need cell grid info to work properly
            // For now, just add the cell
            newSelection.add(cellId);
            setLastSelectedCell(cellId);
            break;
        }

        options.onSelectionChange?.(newSelection);
        return newSelection;
      });
    },
    [options],
  );

  const selectMultipleCells = useCallback(
    (cellIds: string[]) => {
      setSelectedCells((prev) => {
        const newSelection = new Set(prev);
        cellIds.forEach((id) => newSelection.add(id));
        options.onSelectionChange?.(newSelection);
        return newSelection;
      });
    },
    [options],
  );

  const clearSelection = useCallback(() => {
    setSelectedCells(new Set());
    setLastSelectedCell(null);
    options.onSelectionChange?.(new Set());
  }, [options]);

  const isSelected = useCallback(
    (cellId: string) => {
      return selectedCells.has(cellId);
    },
    [selectedCells],
  );

  return {
    selectedCells,
    lastSelectedCell,
    selectCell,
    selectMultipleCells,
    clearSelection,
    isSelected,
  };
}
