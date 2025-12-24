import { useDroppable } from "@dnd-kit/core";
import type { ColumnsBlock, Column } from "../../types/template";
import { BlockRenderer } from "./BlockRenderer";
import { useEditorStore } from "../../store/editorStore";
import { v4 as uuidv4 } from "uuid";

interface ColumnsBlockProps {
  block: ColumnsBlock;
  isSelected?: boolean;
}

export function ColumnsBlockComponent({ block, isSelected = false }: ColumnsBlockProps) {
  const updateBlock = useEditorStore((state) => state.updateBlock);

  const gap = block.gap ?? 16;

  const handleAddColumn = () => {
    const newColumn: Column = {
      id: uuidv4(),
      size: 1,
      children: [],
    };
    updateBlock(block.id, {
      columns: [...block.columns, newColumn],
    });
  };

  const handleRemoveColumn = (columnId: string) => {
    if (block.columns.length <= 1) return; // Keep at least one column
    updateBlock(block.id, {
      columns: block.columns.filter((col) => col.id !== columnId),
    });
  };

  const handleUpdateColumnSize = (columnId: string, size: number) => {
    updateBlock(block.id, {
      columns: block.columns.map((col) =>
        col.id === columnId ? { ...col, size: Math.max(1, size) } : col,
      ),
    });
  };

  const handleUpdateGap = (newGap: number) => {
    updateBlock(block.id, { gap: Math.max(0, newGap) });
  };

  return (
    <div
      className={`rounded-lg border ${isSelected ? "bg-gray-50 border-dashed border-gray-300" : "border-transparent"}`}
    >
      {isSelected && (
        <div className="px-3 pt-2 pb-1 space-y-2">
          <div className="text-xs text-gray-400 font-medium">COLUMNS</div>
          <div className="flex items-center gap-2">
            <label className="text-xs text-gray-600">Gap:</label>
            <input
              type="number"
              value={gap}
              onChange={(e) => handleUpdateGap(Number(e.target.value))}
              className="w-16 px-2 py-1 text-xs border border-gray-300 rounded"
              min="0"
            />
            <span className="text-xs text-gray-500">px</span>
            <button
              onClick={handleAddColumn}
              className="ml-auto px-2 py-1 text-xs bg-blue-500 text-white rounded hover:bg-blue-600"
            >
              + Add Column
            </button>
          </div>
        </div>
      )}
      <div
        style={{
          display: "flex",
          gap: `${gap}px`,
          ...block.styles,
        }}
        className="p-2"
      >
        {block.columns.map((column, columnIndex) => (
          <ColumnComponent
            key={column.id}
            column={column}
            blockId={block.id}
            columnIndex={columnIndex}
            isSelected={isSelected}
            onRemove={() => handleRemoveColumn(column.id)}
            onUpdateSize={(size) => handleUpdateColumnSize(column.id, size)}
            canRemove={block.columns.length > 1}
          />
        ))}
      </div>
    </div>
  );
}

interface ColumnComponentProps {
  column: Column;
  blockId: string;
  columnIndex: number;
  isSelected?: boolean;
  onRemove: () => void;
  onUpdateSize: (size: number) => void;
  canRemove: boolean;
}

function ColumnComponent({
  column,
  blockId,
  columnIndex: _columnIndex,
  isSelected,
  onRemove,
  onUpdateSize,
  canRemove,
}: ColumnComponentProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: `column-${blockId}-${column.id}`,
    data: {
      type: "column",
      parentId: blockId,
      columnId: column.id,
      index: column.children.length,
    },
  });

  return (
    <div
      style={{ flex: column.size }}
      className={`rounded border ${isSelected ? "border-gray-300" : "border-transparent"}`}
    >
      {isSelected && (
        <div className="px-2 py-1 bg-gray-100 border-b border-gray-300 flex items-center gap-2">
          <label className="text-xs text-gray-600">Size:</label>
          <input
            type="number"
            value={column.size}
            onChange={(e) => onUpdateSize(Number(e.target.value))}
            className="w-12 px-1 py-0.5 text-xs border border-gray-300 rounded"
            min="1"
          />
          {canRemove && (
            <button
              onClick={onRemove}
              className="ml-auto text-xs text-red-500 hover:text-red-700"
              title="Remove column"
            >
              ✕
            </button>
          )}
        </div>
      )}
      <div
        ref={setNodeRef}
        className={`
          min-h-[100px] p-2
          ${isOver ? "bg-blue-50" : ""}
          ${column.children.length === 0 ? "flex items-center justify-center" : ""}
        `}
      >
        {column.children.length === 0 ? (
          <span className="text-gray-400 text-sm">
            {isSelected ? "Drop blocks here" : "Empty column"}
          </span>
        ) : (
          <div className="space-y-2">
            {column.children.map((child, index) => (
              <BlockRenderer
                key={child.id}
                block={child}
                index={index}
                parentId={`${blockId}::${column.id}`}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
