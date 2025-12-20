import { useState } from 'react';
import { Modal } from '../ui/Modal';
import type { TableBlock } from '../../types/template';
import { TableGridDesigner } from './table-config/TableGridDesigner';
import { TableSizeControls } from './table-config/TableSizeControls';
import { BorderControls } from './table-config/BorderControls';
import { CellActionsControls } from './table-config/CellActionsControls';
import { useGridSelection } from './table-config/useGridSelection';
import {
  addRow,
  removeRow,
  addColumn,
  removeColumn,
  mergeCells,
  splitCell,
  toggleRowHeader,
} from './table-config/tableConfigUtils';

interface TableConfigPopupProps {
  isOpen: boolean;
  config: TableBlock;
  onClose: () => void;
  onChange: (config: TableBlock) => void;
}

export function TableConfigPopup({
  isOpen,
  config,
  onClose,
  onChange,
}: TableConfigPopupProps) {
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

  const handleBorderChange = (
    borderStyle: 'none' | 'all' | 'horizontal' | 'vertical'
  ) => {
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
    <Modal isOpen={isOpen} onClose={handleCancel} title="Configure Table" size="xl">
      <div className="flex gap-6">
        {/* Left: Visual Grid */}
        <div className="flex-1">
          <TableGridDesigner
            config={workingConfig}
            selectedCells={selectedCells}
            onCellClick={selectCell}
          />
        </div>

        {/* Right: Controls */}
        <div className="w-64 space-y-6">
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

      {/* Footer buttons */}
      <div className="flex justify-end gap-3 mt-6 pt-4 border-t border-gray-200 -mx-6 -mb-6 px-6 pb-6 bg-gray-50">
        <button
          onClick={handleCancel}
          className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50"
        >
          Cancel
        </button>
        <button
          onClick={handleApply}
          className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700"
        >
          Apply
        </button>
      </div>
    </Modal>
  );
}
