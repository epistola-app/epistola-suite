import {useDroppable} from "@dnd-kit/core";
import {Plus} from "lucide-react";
import {useId} from "react";
import {v4 as uuidv4} from "uuid";
import {useEditorStore} from "../../store/editorStore";
import type {Column, ColumnsBlock} from "../../types/template";
import {Button} from "../ui/button";
import {Label} from "../ui/label";
import {Slider} from "../ui/slider";
import {BlockRenderer} from "./BlockRenderer";
import {BlockHeader} from "./BlockHeader";

interface ColumnsBlockProps {
  block: ColumnsBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

export function ColumnsBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: ColumnsBlockProps) {
  const updateBlock = useEditorStore((state) => state.updateBlock);

  const columnGapInputId = useId();
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
    <div className={`rounded-lg bg-emerald-50 ring ring-emerald-300`}>
      <BlockHeader
        title="COLUMNS"
        isSelected={isSelected}
        dragAttributes={dragAttributes}
        dragListeners={dragListeners}
        onDelete={onDelete}
      />
      {isSelected && (
        <div className="px-3 pt-2 pb-1 space-y-2">
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-2">
              <Label htmlFor={columnGapInputId}>Gap (px):</Label>
              <Slider
                value={[Number(gap) || 4]}
                id={columnGapInputId}
                onValueChange={(values) => handleUpdateGap(values[0])}
                min={0}
                max={100}
                step={1}
                className="w-40 **:data-[slot=slider-track]:bg-white **:data-[slot=slider-track]:ring-emerald-400 "
              />
              <span className="text-xs text-primary">{Number(gap) || 4}px</span>
            </div>

            <Button variant="indigo" size="sm" onClick={handleAddColumn} className="ml-auto">
              <Plus className="size-4 shrink-0" /> Add Column
            </Button>
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
        {block.columns.map((column) => (
          <ColumnComponent
            key={column.id}
            column={column}
            blockId={block.id}
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
  isSelected?: boolean;
  onRemove: () => void;
  onUpdateSize: (size: number) => void;
  canRemove: boolean;
}

function ColumnComponent({
  column,
  blockId,
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
          ${isOver && column.children.length === 0 ? "bg-blue-50" : ""}
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
