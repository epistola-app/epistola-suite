import { useDraggable, useDroppable } from "@dnd-kit/core";
import type { Block } from "../../types/template";
import { useEditorStore } from "../../store/editorStore";
import { TextBlockComponent } from "./TextBlock";
import { ContainerBlockComponent } from "./ContainerBlock";
import { ConditionalBlockComponent } from "./ConditionalBlock";
import { LoopBlockComponent } from "./LoopBlock";
import { ColumnsBlockComponent } from "./ColumnsBlock";
import { TableBlockComponent } from "./TableBlock";

interface BlockRendererProps {
  block: Block;
  index: number;
  parentId: string | null;
}

export function BlockRenderer({ block, index, parentId }: BlockRendererProps) {
  const selectedBlockId = useEditorStore((s) => s.selectedBlockId);
  const selectBlock = useEditorStore((s) => s.selectBlock);
  const deleteBlock = useEditorStore((s) => s.deleteBlock);

  const isSelected = selectedBlockId === block.id;

  const {
    attributes,
    listeners,
    setNodeRef: setDragRef,
    isDragging,
  } = useDraggable({
    id: block.id,
    data: {
      type: "block",
      block,
      index,
      parentId,
    },
  });

  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `drop-${block.id}`,
    data: {
      type: "block-drop",
      parentId,
      index: index + 1,
    },
  });

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    selectBlock(block.id);
  };

  const handleDelete = (e: React.MouseEvent) => {
    e.stopPropagation();
    deleteBlock(block.id);
  };

  const renderBlockContent = () => {
    switch (block.type) {
      case "text":
        return (
          <TextBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      case "container":
        return (
          <ContainerBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      case "conditional":
        return (
          <ConditionalBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      case "loop":
        return (
          <LoopBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      case "columns":
        return (
          <ColumnsBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      case "table":
        return (
          <TableBlockComponent
            block={block}
            isSelected={isSelected}
            dragAttributes={attributes}
            dragListeners={listeners}
            onDelete={handleDelete}
          />
        );
      default:
        return <div>Unknown block type</div>;
    }
  };

  return (
    <>
      <div
        ref={setDragRef}
        onClick={handleClick}
        className={`
          rounded-lg border-2 transition-all
          ${isSelected ? "border-blue-500 shadow-md" : "border-transparent hover:border-gray-300"}
          ${isDragging ? "opacity-50" : ""}
        `}
      >
        {/* Block Content */}
        {renderBlockContent()}
      </div>

      {/* Drop Zone After Block */}
      <div
        ref={setDropRef}
        className={`
          h-2 -my-1 transition-all rounded
          ${isOver ? "bg-blue-400 h-4" : ""}
        `}
      />
    </>
  );
}
