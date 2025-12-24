import { ArrowLeftToLine, ArrowRightToLine } from "lucide-react";
import { useState } from "react";
import { useEditorStore } from "../../store/editorStore";
import type { Block } from "../../types/template";
import { BlockStyleEditor } from "./BlockStyleEditor";
import { DocumentPropertiesEditor } from "./DocumentPropertiesEditor";
import { DocumentStyleEditor } from "./DocumentStyleEditor";

type TabType = "properties" | "styles";

interface StyleSidebarProps {
  className?: string;
}

function BlockPropertiesEditor({ block }: { block: Block }) {
  return (
    <div className="p-3 space-y-4">
      <div>
        <label className="block text-xs text-gray-500 mb-1">Block Type</label>
        <div className="text-sm font-medium text-gray-700">
          {block.type.charAt(0).toUpperCase() + block.type.slice(1)}
        </div>
      </div>

      <div>
        <label className="block text-xs text-gray-500 mb-1">Block ID</label>
        <code className="text-xs bg-gray-100 px-2 py-1 rounded block">{block.id}</code>
      </div>

      {block.type === "text" && (
        <div className="p-3 bg-blue-50 rounded-lg">
          <p className="text-xs text-blue-600">
            <strong>Tip:</strong> Type{" "}
            <code className="bg-blue-100 px-1 rounded">{"{{expression}}"}</code> in the text to
            insert a dynamic placeholder.
          </p>
        </div>
      )}

      {block.type === "conditional" && (
        <div className="p-3 bg-amber-50 rounded-lg">
          <p className="text-xs text-amber-600">
            <strong>Tip:</strong> Click on the condition expression above to edit it. Use the Invert
            button to show content when the condition is false.
          </p>
        </div>
      )}

      {block.type === "loop" && (
        <div className="p-3 bg-purple-50 rounded-lg">
          <p className="text-xs text-purple-600">
            <strong>Tip:</strong> The loop iterates over an array. Use the item alias (e.g.,{" "}
            <code className="bg-purple-100 px-1 rounded">{"{{item.property}}"}</code>) to access
            each item's properties.
          </p>
        </div>
      )}

      {block.type === "container" && (
        <div className="p-3 bg-gray-100 rounded-lg">
          <p className="text-xs text-gray-600">
            <strong>Tip:</strong> Containers group blocks together. Drag blocks into this container
            to nest them.
          </p>
        </div>
      )}
    </div>
  );
}


export function StyleSidebar({ className = "" }: StyleSidebarProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [activeTab, setActiveTab] = useState<TabType>("styles");
  const selectedBlockId = useEditorStore((s) => s.selectedBlockId);
  const blocks = useEditorStore((s) => s.template.blocks);

  // Find selected block recursively
  const findBlock = (blocks: Block[], id: string): Block | null => {
    for (const block of blocks) {
      if (block.id === id) return block;

      // Search in regular children
      if ("children" in block && block.children) {
        const found = findBlock(block.children, id);
        if (found) return found;
      }

      // Search in columns (for columns block)
      if (block.type === "columns" && "columns" in block) {
        for (const column of block.columns) {
          const found = findBlock(column.children, id);
          if (found) return found;
        }
      }

      // Search in table cells (for table block)
      if (block.type === "table" && "rows" in block) {
        for (const row of block.rows) {
          for (const cell of row.cells) {
            const found = findBlock(cell.children, id);
            if (found) return found;
          }
        }
      }
    }
    return null;
  };

  const selectedBlock = selectedBlockId ? findBlock(blocks, selectedBlockId) : null;

  // CHANGE: Single return with conditional rendering instead of early return
  return (
    <div
      className={`bg-white flex flex-col overflow-hidden transition-all duration-300 ease-in-out ${
        collapsed ? "w-12" : "w-82"
      } ${className}`}
    >
      {collapsed ? (
        // Collapsed state
        <>
          <button
            type="button"
            onClick={() => setCollapsed(false)}
            className="p-3 hover:bg-slate-100 transition-colors group"
            title="Expand panel"
          >
            <ArrowRightToLine className="size-4" />
          </button>
          <div className="flex-1 flex items-center justify-center py-8">
            <span className="text-xs font-medium text-slate-400 transform -rotate-90 whitespace-nowrap">
              Inspector
            </span>
          </div>
        </>
      ) : (
        // Expanded state
        <>
          {/* Header with collapse button */}
          <div className="flex items-center justify-between px-4 py-3 border-b border-slate-200 bg-gradient-to-r from-slate-50 to-white">
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-blue-500" />
              <span className="text-sm font-semibold text-slate-700 truncate">
                {selectedBlock ? "Block Inspector" : "Document Settings"}
              </span>
            </div>
            <button
              type="button"
              onClick={() => setCollapsed(true)}
              className="p-1.5 hover:bg-slate-200 rounded-md transition-colors group"
              title="Collapse panel"
            >
              <ArrowLeftToLine className="size-4" />
            </button>
          </div>

          {/* Tabs */}
          <div className="flex border-b border-slate-200 bg-slate-50">
            <button
              type="button"
              onClick={() => setActiveTab("properties")}
              className={`flex-1 px-4 py-2.5 text-xs font-medium transition-all relative ${
                activeTab === "properties"
                  ? "text-blue-600 bg-white"
                  : "text-slate-500 hover:text-slate-700 hover:bg-slate-100"
              }`}
            >
              Properties
              {activeTab === "properties" && (
                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-blue-600" />
              )}
            </button>
            <button
              type="button"
              onClick={() => setActiveTab("styles")}
              className={`flex-1 px-4 py-2.5 text-xs font-medium transition-all relative ${
                activeTab === "styles"
                  ? "text-blue-600 bg-white"
                  : "text-slate-500 hover:text-slate-700 hover:bg-slate-100"
              }`}
            >
              Styles
              {activeTab === "styles" && (
                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-blue-600" />
              )}
            </button>
          </div>

          {/* Content */}
          <div className="flex-1 overflow-y-auto">
            {activeTab === "properties" ? (
              selectedBlock ? (
                <BlockPropertiesEditor block={selectedBlock} />
              ) : (
                <DocumentPropertiesEditor />
              )
            ) : selectedBlock ? (
              <BlockStyleEditor block={selectedBlock} />
            ) : (
              <DocumentStyleEditor />
            )}
          </div>
        </>
      )}
    </div>
  );
}
