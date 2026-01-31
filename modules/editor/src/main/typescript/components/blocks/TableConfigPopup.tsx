import {useState} from "react";
import {Check, X} from "lucide-react";
import {Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle} from "../ui/dialog";
import {Button} from "../ui/button";
import type {TableBlock} from "../../types/template";
import {TableGridDesigner} from "./table-config/TableGridDesigner";
import {TableSizeControls} from "./table-config/TableSizeControls";
import {BorderControls} from "./table-config/BorderControls";
import {CellActionsControls} from "./table-config/CellActionsControls";
import {useGridSelection} from "./table-config/useGridSelection";
import {
    addColumn,
    addRow,
    mergeCells,
    removeColumn,
    removeRow,
    splitCell,
    toggleRowHeader,
} from "./table-config/tableConfigUtils";

interface TableConfigPopupProps {
  isOpen: boolean;
  config: TableBlock;
  onClose: () => void;
  onChange: (config: TableBlock) => void;
}

export function TableConfigPopup({ isOpen, config, onClose, onChange }: TableConfigPopupProps) {
  // Working copy of the config
  const [workingConfig, setWorkingConfig] = useState<TableBlock>(config);

  // Reset working copy when config or isOpen changes
  useState(() => {
    if (isOpen) {
      setWorkingConfig(config);
    }
  });

  // Cell selection
  const { selectedCells, selectCell, clearSelection } = useGridSelection();

  // Handlers for table operations
  const handleAddRow = () => {
    setWorkingConfig(addRow(workingConfig));
  };

  const handleRemoveRow = () => {
    setWorkingConfig(removeRow(workingConfig));
  };

  const handleAddColumn = () => {
    setWorkingConfig(addColumn(workingConfig));
  };

  const handleRemoveColumn = () => {
    setWorkingConfig(removeColumn(workingConfig));
  };

  const handleMergeCells = () => {
    const merged = mergeCells(workingConfig, selectedCells);
    setWorkingConfig(merged);
    clearSelection();
  };

  const handleSplitCell = () => {
    // Get the first selected cell
    const cellId = Array.from(selectedCells)[0];
    if (cellId) {
      const split = splitCell(workingConfig, cellId);
      setWorkingConfig(split);
      clearSelection();
    }
  };

  const handleToggleHeader = () => {
    setWorkingConfig(toggleRowHeader(workingConfig, selectedCells));
  };

  const handleBorderChange = (borderStyle: "none" | "all" | "horizontal" | "vertical") => {
    setWorkingConfig({
      ...workingConfig,
      borderStyle,
    });
  };

  const handleApply = () => {
    onChange(workingConfig);
    onClose();
  };

  const handleCancel = () => {
    // Reset working config to original
    setWorkingConfig(config);
    clearSelection();
    onClose();
  };

  return (
    <Dialog open={isOpen} onOpenChange={(open) => !open && handleCancel()}>
      <DialogContent className="sm:max-w-full w-[70vw] h-[80vh] flex flex-col">
        <DialogHeader>
          <DialogTitle>Configure Table</DialogTitle>
        </DialogHeader>

        <div className="flex gap-6 flex-1 overflow-hidden">
          {/* Left: Visual Grid */}
          <div className="flex-1 overflow-y-auto">
            <TableGridDesigner
              config={workingConfig}
              selectedCells={selectedCells}
              onCellClick={selectCell}
            />
          </div>

          {/* Right: Controls */}
          <div className="max-w-82 w-full space-y-6 shrink-0 bg-muted/50 rounded-lg p-4">
            <TableSizeControls
              config={workingConfig}
              onAddRow={handleAddRow}
              onRemoveRow={handleRemoveRow}
              onAddColumn={handleAddColumn}
              onRemoveColumn={handleRemoveColumn}
            />

            <CellActionsControls
              config={workingConfig}
              selectedCells={selectedCells}
              onMergeCells={handleMergeCells}
              onSplitCell={handleSplitCell}
              onToggleHeader={handleToggleHeader}
            />

            <BorderControls config={workingConfig} onChange={handleBorderChange} />
          </div>
        </div>

        <DialogFooter>
          <Button
            variant="outline"
            className="ring ring-neutral-500 hover:ring-neutral-700"
            onClick={handleCancel}
          >
            <X className="size-4" />
            Cancel
          </Button>
          <Button variant="indigo" onClick={handleApply}>
            <Check className="size-4" />
            Apply
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
