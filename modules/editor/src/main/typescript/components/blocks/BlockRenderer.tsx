import { useDraggable, useDroppable } from '@dnd-kit/core';
import type { Block } from '../../types/template';
import { useEditorStore } from '../../store/editorStore';
import { TextBlockComponent } from './TextBlock';
import { ContainerBlockComponent } from './ContainerBlock';
import { ConditionalBlockComponent } from './ConditionalBlock';
import { LoopBlockComponent } from './LoopBlock';
import { ColumnsBlockComponent } from './ColumnsBlock';
import { TableBlockComponent } from './TableBlock';

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

  const { attributes, listeners, setNodeRef: setDragRef, isDragging } = useDraggable({
    id: block.id,
    data: {
      type: 'block',
      block,
      index,
      parentId,
    },
  });

  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `drop-${block.id}`,
    data: {
      type: 'block-drop',
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
      case 'text':
        return <TextBlockComponent block={block} isSelected={isSelected} />;
      case 'container':
        return <ContainerBlockComponent block={block} isSelected={isSelected} />;
      case 'conditional':
        return <ConditionalBlockComponent block={block} isSelected={isSelected} />;
      case 'loop':
        return <LoopBlockComponent block={block} isSelected={isSelected} />;
      case 'columns':
        return <ColumnsBlockComponent block={block} isSelected={isSelected} />;
      case 'table':
        return <TableBlockComponent block={block} isSelected={isSelected} />;
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
          group relative rounded-lg border-2 transition-all
          ${isSelected ? 'border-blue-500 shadow-md' : 'border-transparent hover:border-gray-300'}
          ${isDragging ? 'opacity-50' : ''}
        `}
      >
        {/* Drag Handle */}
        <div
          {...attributes}
          {...listeners}
          className="absolute -left-6 top-1/2 -translate-y-1/2 opacity-0 group-hover:opacity-100
                     cursor-grab p-1 text-gray-400 hover:text-gray-600 transition-opacity"
        >
          ⋮⋮
        </div>

        {/* Delete Button */}
        <button
          onClick={handleDelete}
          className="absolute -right-2 -top-2 opacity-0 group-hover:opacity-100
                     w-5 h-5 bg-red-500 text-white rounded-full text-xs
                     hover:bg-red-600 transition-opacity flex items-center justify-center"
        >
          ×
        </button>

        {/* Block Content */}
        {renderBlockContent()}
      </div>

      {/* Drop Zone After Block */}
      <div
        ref={setDropRef}
        className={`
          h-2 -my-1 transition-all rounded
          ${isOver ? 'bg-blue-400 h-4' : ''}
        `}
      />
    </>
  );
}
