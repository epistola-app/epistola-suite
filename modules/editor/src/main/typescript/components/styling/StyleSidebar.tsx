import { ArrowLeftToLine, ArrowRightToLine } from "lucide-react";
import { useState } from "react";
import { useEditorStore } from "../../store/editorStore";
import type { Block } from "../../types/template";
import { BlockStyleEditor } from "./BlockStyleEditor";
import { DocumentPropertiesEditor } from "./DocumentPropertiesEditor";
import { DocumentStyleEditor } from "./DocumentStyleEditor";
import { BlockPropertiesEditor } from "./BlockPropertiesEditor";

type TabType = "properties" | "styles";

interface StyleSidebarProps {
  className?: string;
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
            <SidebarContent activeTab={activeTab} selectedBlock={selectedBlock} />
          </div>
        </>
      )}
    </div>
  );
}

interface SidebarContentProps {
  activeTab: TabType;
  selectedBlock: Block | null;
}

const SidebarContent = ({ activeTab, selectedBlock }: SidebarContentProps) => {
  const isProperties = activeTab === "properties";

  // prettier-ignore
  if (selectedBlock)     {
    return isProperties 
    ? ( <BlockPropertiesEditor block={selectedBlock} /> ) 
    : ( <BlockStyleEditor block={selectedBlock} /> );
  }

  // prettier-ignore
  return isProperties 
    ? <DocumentPropertiesEditor /> 
    : <DocumentStyleEditor />;
};
