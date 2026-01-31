import {cn} from "@/lib/utils";
import {Grip, X} from "lucide-react";
import {Button} from "../ui/button";

interface BlockHeaderProps {
  // Drag and drop functionality
  dragAttributes?: React.HTMLAttributes<HTMLDivElement>;
  dragListeners?: React.HTMLAttributes<HTMLDivElement>;

  // Block actions
  onDelete?: (e: React.MouseEvent) => void;

  // Display options
  title?: string;
  isSelected?: boolean;
  showDelete?: boolean; // Default: true
  showDragHandle?: boolean; // Default: true

  // Customization
  children?: React.ReactNode;
  className?: string;
}

export function BlockHeader({
  dragAttributes,
  dragListeners,
  onDelete,
  title,
  isSelected = false,
  showDelete = true,
  showDragHandle = true,
  children,
  className,
}: BlockHeaderProps) {
  return (
    <div
      className={cn(
        "flex items-center justify-between px-2 py-1 border-b border-gray-100 rounded-t-lg",
        "transition-colors",
        isSelected ? "bg-blue-50 border-blue-100" : "bg-gray-50 border-gray-100",
        className,
      )}
    >
      {/* Left: Drag Handle */}
      {showDragHandle && (
        <div
          {...dragAttributes}
          {...dragListeners}
          className="cursor-grab active:cursor-grabbing text-gray-600 hover:text-gray-800
                     transition-colors p-1"
          title="Drag to move"
        >
          <Grip className="size-4" />
        </div>
      )}

      {/* Center: Title/Content */}
      <div className="flex-1 text-center min-w-0 text-xs">
        {title && <span className="text-xs text-gray-500 font-medium truncate">{title}</span>}
        {children}
      </div>

      {/* Right: Delete Button */}
      {showDelete && onDelete && (
        <Button
          onClick={onDelete}
          variant="outline"
          className="shadow-none border-red-300 bg-red-50 text-red-500 hover:bg-red-100 hover:text-red-700 "
          title="Delete block"
          size="icon-xs"
          aria-label={`Delete ${title || "block"}`}
        >
          <X className="size-4" aria-hidden="true" />
        </Button>
      )}
    </div>
  );
}
