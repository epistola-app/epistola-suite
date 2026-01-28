import { useDroppable } from "@dnd-kit/core";
import { useEditorStore } from "../../store/editorStore";
import { BlockRenderer } from "../blocks/BlockRenderer";

export function Canvas() {
  const blocks = useEditorStore((s) => s.template.blocks);
  const documentStyles = useEditorStore((s) => s.template.documentStyles);
  const selectBlock = useEditorStore((s) => s.selectBlock);

  // Canvas drop zone for empty state
  const { setNodeRef, isOver } = useDroppable({
    id: "canvas-root",
    data: {
      type: "canvas",
      parentId: null,
      index: blocks.length,
    },
  });

  // Dedicated append drop zone at the end
  const { setNodeRef: setAppendRef, isOver: isAppendOver } = useDroppable({
    id: "canvas-append",
    data: {
      type: "block-drop",
      parentId: null,
      index: blocks.length,
    },
  });

  const handleCanvasClick = () => {
    // Deselect any selected block when clicking on empty canvas area
    selectBlock(null);
  };

  // Apply document typography styles only to the content area
  const contentStyles: React.CSSProperties = {
    fontFamily: documentStyles?.fontFamily,
    fontSize: documentStyles?.fontSize,
    fontWeight: documentStyles?.fontWeight,
    color: documentStyles?.color,
    lineHeight: documentStyles?.lineHeight,
    letterSpacing: documentStyles?.letterSpacing,
    textAlign: documentStyles?.textAlign,
  };

  // Background applies to the whole canvas
  const canvasStyles: React.CSSProperties = {
    backgroundColor: documentStyles?.backgroundColor,
  };

  return (
    <div
      ref={setNodeRef}
      onClick={handleCanvasClick}
      style={canvasStyles}
      className={`
        min-h-full p-4
        ${isOver && blocks.length === 0 ? "bg-blue-50" : ""}
        ${blocks.length === 0 ? "flex items-center justify-center" : ""}
      `}
    >
      {blocks.length === 0 ? (
        <div className="text-center text-gray-400 py-20">
          <p className="text-lg mb-2">Drag blocks here to start</p>
          <p className="text-sm">Or click a block type in the palette above</p>
        </div>
      ) : (
        <div className="space-y-2" style={contentStyles}>
          {blocks.map((block, index) => (
            <BlockRenderer key={block.id} block={block} index={index} parentId={null} />
          ))}
          {/* Append drop zone at the end */}
          <div
            ref={setAppendRef}
            className={`
              transition-all duration-150 ease-out
              ${isAppendOver ? "h-6 -my-3" : "h-3 -my-1.5"}
            `}
          >
            {isAppendOver && (
              <div className="h-0.5 bg-blue-500 shadow-lg rounded-full" />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
