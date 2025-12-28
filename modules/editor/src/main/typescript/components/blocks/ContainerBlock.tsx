import { useDroppable } from "@dnd-kit/core";
import type { ContainerBlock } from "../../types/template";
import { BlockRenderer } from "./BlockRenderer";
import { BlockHeader } from "./BlockHeader";

interface ContainerBlockProps {
  block: ContainerBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

export function ContainerBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: ContainerBlockProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: `container-${block.id}`,
    data: {
      type: "container",
      parentId: block.id,
      index: block.children.length,
    },
  });

  return (
    <div
      className={`rounded-lg border ${isSelected ? "bg-gray-50 border-dashed border-gray-300" : "border-transparent"}`}
    >
      <BlockHeader
        title="CONTAINER"
        isSelected={isSelected}
        dragAttributes={dragAttributes}
        dragListeners={dragListeners}
        onDelete={onDelete}
      />
      <div
        ref={setNodeRef}
        style={block.styles}
        className={`
          min-h-10 p-2
          ${isOver ? "bg-blue-50" : ""}
          ${block.children.length === 0 ? "flex items-center justify-center" : ""}
        `}
      >
        {block.children.length === 0 ? (
          <span className="text-gray-400 text-sm">
            {isSelected ? "Drop blocks here" : "Empty container"}
          </span>
        ) : (
          <div className="space-y-2">
            {block.children.map((child, index) => (
              <BlockRenderer key={child.id} block={child} index={index} parentId={block.id} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
