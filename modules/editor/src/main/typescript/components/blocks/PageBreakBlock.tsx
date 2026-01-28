import type { PageBreakBlock } from "../../types/template";
import { BlockHeader } from "./BlockHeader";

interface PageBreakBlockProps {
  block: PageBreakBlock;
  isSelected?: boolean;
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;
  onDelete?: (e: React.MouseEvent) => void;
}

export function PageBreakBlockComponent({
  block,
  isSelected = false,
  dragAttributes,
  dragListeners,
  onDelete,
}: PageBreakBlockProps) {
  return (
    <div className="rounded-lg">
      <BlockHeader
        title="PAGE BREAK"
        isSelected={isSelected}
        dragAttributes={dragAttributes}
        dragListeners={dragListeners}
        onDelete={onDelete}
      />
      <div style={block.styles} className="flex items-center justify-center py-4 gap-4">
        <div className="flex-1 h-px bg-gray-300" />
        <span className="text-sm text-gray-500 font-medium">Page Break</span>
        <div className="flex-1 h-px bg-gray-300" />
      </div>
    </div>
  );
}
