import { useDroppable } from "@dnd-kit/core";
import { useState } from "react";
import type { ConditionalBlock } from "../../types/template";
import { useEditorStore } from "../../store/editorStore";
import { BlockRenderer } from "./BlockRenderer";
import { ExpressionEditor } from "./ExpressionEditor";

interface ConditionalBlockProps {
  block: ConditionalBlock;
  isSelected?: boolean;
}

export function ConditionalBlockComponent({ block, isSelected = false }: ConditionalBlockProps) {
  const updateBlock = useEditorStore((s) => s.updateBlock);

  const [isEditingCondition, setIsEditingCondition] = useState(false);

  const toggleInverse = () => {
    updateBlock(block.id, { inverse: !block.inverse });
  };

  const { setNodeRef, isOver } = useDroppable({
    id: `conditional-${block.id}`,
    data: {
      type: "container",
      parentId: block.id,
      index: block.children.length,
    },
  });

  const handleConditionSave = (newCondition: string) => {
    updateBlock(block.id, { condition: { raw: newCondition } });
    setIsEditingCondition(false);
  };

  // Collapsed view - shows just a hint that this is a conditional
  if (!isSelected) {
    return (
      <div className="rounded-lg border border-amber-200 bg-amber-50/50">
        {/* Minimal header */}
        <div className="px-2 py-1 flex items-center gap-2 text-xs text-amber-500 border-b border-amber-100">
          <span className="font-medium">?</span>
          <span className="opacity-60">{block.inverse ? "if not" : "if"}</span>
          <code className="text-amber-600">{block.condition.raw}</code>
        </div>

        {/* Content - simplified */}
        <div
          ref={setNodeRef}
          style={block.styles}
          className={`p-2 ${isOver ? "bg-amber-100" : ""}`}
        >
          {block.children.length === 0 ? (
            <div className="text-amber-300 text-sm py-2 text-center">Empty conditional</div>
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

  // Expanded view - full editing controls
  return (
    <div className="bg-amber-50 rounded-lg border border-amber-200">
      {/* Header */}
      <div className="px-3 py-2 border-b border-amber-200 flex items-center justify-between flex-wrap gap-2">
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-amber-600">
            {block.inverse ? "IF NOT" : "IF"}
          </span>
          {isEditingCondition ? (
            <div onClick={(e) => e.stopPropagation()}>
              <ExpressionEditor
                value={block.condition.raw}
                onSave={handleConditionSave}
                onCancel={() => setIsEditingCondition(false)}
              />
            </div>
          ) : (
            <code
              onClick={() => setIsEditingCondition(true)}
              className="px-2 py-0.5 text-sm bg-amber-100 rounded cursor-pointer hover:bg-amber-200"
              title="Click to edit condition"
            >
              {block.condition.raw}
            </code>
          )}
        </div>

        {/* Inverse Toggle */}
        <button
          onClick={(e) => {
            e.stopPropagation();
            toggleInverse();
          }}
          className={`
            px-2 py-0.5 text-xs rounded transition-colors
            ${
              block.inverse
                ? "bg-amber-500 text-white"
                : "bg-amber-100 text-amber-700 hover:bg-amber-200"
            }
          `}
          title={
            block.inverse
              ? "Currently showing when FALSE - click to show when TRUE"
              : "Currently showing when TRUE - click to show when FALSE"
          }
        >
          {block.inverse ? "⇄ Inverted" : "⇄ Invert"}
        </button>
      </div>

      {/* Content */}
      <div
        ref={setNodeRef}
        style={block.styles}
        className={`
          min-h-[60px] p-3
          ${isOver ? "bg-amber-100" : ""}
        `}
      >
        {block.children.length === 0 ? (
          <span className="text-amber-400 text-sm">
            Drop blocks here (shown when condition is {block.inverse ? "false" : "true"})
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
