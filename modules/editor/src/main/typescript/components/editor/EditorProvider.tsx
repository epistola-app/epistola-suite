import type {DragEndEvent, DragStartEvent} from "@dnd-kit/core";
import {closestCenter, DndContext, DragOverlay} from "@dnd-kit/core";
import type {ReactNode} from "react";
import {useState} from "react";
import {useEditorStore} from "../../store/editorStore";
import type {Block} from "../../types/template";

interface EditorProviderProps {
  children: ReactNode;
}

export function EditorProvider({ children }: EditorProviderProps) {
  const addBlock = useEditorStore((s) => s.addBlock);
  const moveBlock = useEditorStore((s) => s.moveBlock);
  const [activeBlock, setActiveBlock] = useState<Block | null>(null);

  const handleDragStart = (event: DragStartEvent) => {
    const { active } = event;

    if (active.data.current?.type === "palette") {
      // Creating a new block from palette
      const newBlock = active.data.current.createBlock();
      setActiveBlock(newBlock);
    } else if (active.data.current?.type === "block") {
      // Moving an existing block
      setActiveBlock(active.data.current.block);
    }
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;

    if (!over) {
      setActiveBlock(null);
      return;
    }

    const activeData = active.data.current;
    const overData = over.data.current;

    if (!activeData || !overData) {
      setActiveBlock(null);
      return;
    }

    // Determine the target location
    let targetParentId: string | null = null;
    let targetIndex = 0;

    if (overData.type === "canvas") {
      targetParentId = null;
      targetIndex = overData.index;
    } else if (overData.type === "container" || overData.type === "loop" || overData.type === "pageheader" || overData.type === "pagefooter") {
      targetParentId = overData.parentId;
      targetIndex = overData.index;
    } else if (overData.type === "column") {
      // Handle dropping into a column within a columns block
      targetParentId = `${overData.parentId}::${overData.columnId}`;
      targetIndex = overData.index;
    } else if (overData.type === "cell") {
      // Handle dropping into a table cell
      targetParentId = overData.parentId;
      targetIndex = overData.index;
    } else if (overData.type === "conditional-then") {
      // Special handling for conditional blocks - then branch
      targetParentId = overData.parentId;
      targetIndex = overData.index;
      // For now, treat then blocks as regular children
    } else if (overData.type === "conditional-else") {
      // Special handling for conditional blocks - else branch
      targetParentId = overData.parentId;
      targetIndex = overData.index;
    } else if (overData.type === "block-drop") {
      targetParentId = overData.parentId;
      targetIndex = overData.index;
    }

    if (activeData.type === "palette") {
      // Adding a new block from palette
      const newBlock = activeData.createBlock();
      addBlock(newBlock, targetParentId, targetIndex);
    } else if (activeData.type === "block") {
      // Moving an existing block
      const blockId = activeData.block.id;
      moveBlock(blockId, targetParentId, targetIndex);
    }

    setActiveBlock(null);
  };

  return (
    <DndContext
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      {children}
      <DragOverlay className="grid">
        {activeBlock && (
          <div className="grid place-content-center border-2 border-blue-400 rounded-lg shadow-lg p-2.5 text-center opacity-80">
            <span className="text-sm font-medium text-blue-600 pointer-events-none">
              {activeBlock.type.toUpperCase()}
            </span>
          </div>
        )}
      </DragOverlay>
    </DndContext>
  );
}
