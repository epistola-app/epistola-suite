import type { TableBlock } from '../../../types/template';

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
      <h3 className="text-sm font-medium text-gray-700">Table Size</h3>

      {/* Rows */}
      <div className="flex items-center gap-2">
        <label className="text-sm text-gray-600 w-20">Rows:</label>
        <div className="flex items-center gap-2">
          <button
            onClick={onRemoveRow}
            disabled={!canRemoveRow}
            className={`
              w-8 h-8 flex items-center justify-center rounded border
              ${
                canRemoveRow
                  ? 'border-gray-300 hover:bg-gray-100 text-gray-700'
                  : 'border-gray-200 text-gray-300 cursor-not-allowed'
              }
            `}
            title={canRemoveRow ? 'Remove row' : 'Must have at least 1 row'}
          >
            −
          </button>
          <span className="text-sm font-medium text-gray-700 w-8 text-center">
            {rowCount}
          </span>
          <button
            onClick={onAddRow}
            className="w-8 h-8 flex items-center justify-center rounded border border-gray-300 hover:bg-gray-100 text-gray-700"
            title="Add row"
          >
            +
          </button>
        </div>
      </div>

      {/* Columns */}
      <div className="flex items-center gap-2">
        <label className="text-sm text-gray-600 w-20">Columns:</label>
        <div className="flex items-center gap-2">
          <button
            onClick={onRemoveColumn}
            disabled={!canRemoveColumn}
            className={`
              w-8 h-8 flex items-center justify-center rounded border
              ${
                canRemoveColumn
                  ? 'border-gray-300 hover:bg-gray-100 text-gray-700'
                  : 'border-gray-200 text-gray-300 cursor-not-allowed'
              }
            `}
            title={canRemoveColumn ? 'Remove column' : 'Must have at least 1 column'}
          >
            −
          </button>
          <span className="text-sm font-medium text-gray-700 w-8 text-center">
            {columnCount}
          </span>
          <button
            onClick={onAddColumn}
            className="w-8 h-8 flex items-center justify-center rounded border border-gray-300 hover:bg-gray-100 text-gray-700"
            title="Add column"
          >
            +
          </button>
        </div>
      </div>
    </div>
  );
}
