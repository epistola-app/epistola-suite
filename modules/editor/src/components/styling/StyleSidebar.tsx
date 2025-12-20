import { useState } from 'react';
import { useEditorStore } from '../../store/editorStore';
import type { Block } from '../../types/template';
import { DocumentStyleEditor } from './DocumentStyleEditor';
import { BlockStyleEditor } from './BlockStyleEditor';

type TabType = 'properties' | 'styles';

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
        <code className="text-xs bg-gray-100 px-2 py-1 rounded block">
          {block.id}
        </code>
      </div>

      {block.type === 'text' && (
        <div className="p-3 bg-blue-50 rounded-lg">
          <p className="text-xs text-blue-600">
            <strong>Tip:</strong> Type <code className="bg-blue-100 px-1 rounded">{'{{expression}}'}</code> in the text to insert a dynamic placeholder.
          </p>
        </div>
      )}

      {block.type === 'conditional' && (
        <div className="p-3 bg-amber-50 rounded-lg">
          <p className="text-xs text-amber-600">
            <strong>Tip:</strong> Click on the condition expression above to edit it. Use the Invert button to show content when the condition is false.
          </p>
        </div>
      )}

      {block.type === 'loop' && (
        <div className="p-3 bg-purple-50 rounded-lg">
          <p className="text-xs text-purple-600">
            <strong>Tip:</strong> The loop iterates over an array. Use the item alias (e.g., <code className="bg-purple-100 px-1 rounded">{'{{item.property}}'}</code>) to access each item's properties.
          </p>
        </div>
      )}

      {block.type === 'container' && (
        <div className="p-3 bg-gray-100 rounded-lg">
          <p className="text-xs text-gray-600">
            <strong>Tip:</strong> Containers group blocks together. Drag blocks into this container to nest them.
          </p>
        </div>
      )}
    </div>
  );
}

function DocumentPropertiesEditor() {
  const template = useEditorStore((s) => s.template);
  const updatePageSettings = useEditorStore((s) => s.updatePageSettings);

  const handleMarginChange = (side: 'top' | 'right' | 'bottom' | 'left', value: string) => {
    const numValue = parseFloat(value);
    if (!isNaN(numValue) && numValue >= 0) {
      updatePageSettings({
        margins: {
          ...template.pageSettings.margins,
          [side]: numValue,
        },
      });
    }
  };

  return (
    <div className="p-3 space-y-4">
      <div>
        <label className="block text-xs text-gray-500 mb-1">Template Name</label>
        <div className="text-sm font-medium text-gray-700">{template.name}</div>
      </div>

      <div>
        <label className="block text-xs text-gray-500 mb-1">Page Format</label>
        <div className="text-sm text-gray-700">
          {template.pageSettings.format} - {template.pageSettings.orientation}
        </div>
      </div>

      <div>
        <label className="block text-xs text-gray-500 mb-2">Margins (mm)</label>
        <div className="grid grid-cols-3 gap-1 items-center">
          {/* Top */}
          <div className="col-start-2">
            <input
              type="number"
              value={template.pageSettings.margins.top}
              onChange={(e) => handleMarginChange('top', e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              title="Top margin"
            />
          </div>

          {/* Left */}
          <div className="col-start-1 row-start-2">
            <input
              type="number"
              value={template.pageSettings.margins.left}
              onChange={(e) => handleMarginChange('left', e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              title="Left margin"
            />
          </div>

          {/* Center label */}
          <div className="col-start-2 row-start-2 flex justify-center">
            <span className="text-xs text-gray-400">mm</span>
          </div>

          {/* Right */}
          <div className="col-start-3 row-start-2">
            <input
              type="number"
              value={template.pageSettings.margins.right}
              onChange={(e) => handleMarginChange('right', e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              title="Right margin"
            />
          </div>

          {/* Bottom */}
          <div className="col-start-2 row-start-3">
            <input
              type="number"
              value={template.pageSettings.margins.bottom}
              onChange={(e) => handleMarginChange('bottom', e.target.value)}
              min={0}
              className="w-full px-2 py-1 text-xs text-center border border-gray-200 rounded
                         focus:outline-none focus:border-blue-400
                         [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
              title="Bottom margin"
            />
          </div>
        </div>
      </div>

      <div>
        <label className="block text-xs text-gray-500 mb-1">Blocks</label>
        <div className="text-sm text-gray-700">{template.blocks.length} top-level block(s)</div>
      </div>
    </div>
  );
}

export function StyleSidebar({ className = '' }: StyleSidebarProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [activeTab, setActiveTab] = useState<TabType>('styles');
  const selectedBlockId = useEditorStore((s) => s.selectedBlockId);
  const blocks = useEditorStore((s) => s.template.blocks);

  // Find selected block recursively
  const findBlock = (blocks: Block[], id: string): Block | null => {
    for (const block of blocks) {
      if (block.id === id) return block;

      // Search in regular children
      if ('children' in block && block.children) {
        const found = findBlock(block.children, id);
        if (found) return found;
      }

      // Search in columns (for columns block)
      if (block.type === 'columns' && 'columns' in block) {
        for (const column of block.columns) {
          const found = findBlock(column.children, id);
          if (found) return found;
        }
      }

      // Search in table cells (for table block)
      if (block.type === 'table' && 'rows' in block) {
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

  if (collapsed) {
    return (
      <div className={`w-10 bg-white flex flex-col ${className}`}>
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          className="p-2 hover:bg-gray-100 transition-colors"
          title="Expand panel"
        >
          <svg
            className="w-5 h-5 text-gray-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M13 5l7 7-7 7M5 5l7 7-7 7"
            />
          </svg>
        </button>
        <div className="flex-1 flex items-center justify-center">
          <span
            className="text-xs text-gray-400 transform -rotate-90 whitespace-nowrap"
            style={{ writingMode: 'vertical-rl' }}
          >
            Inspector
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className={`w-64 bg-white flex flex-col overflow-hidden ${className}`}>
      {/* Header with collapse button */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-gray-200 bg-gray-50">
        <span className="text-sm font-medium text-gray-700">
          {selectedBlock ? 'Block Inspector' : 'Document'}
        </span>
        <button
          type="button"
          onClick={() => setCollapsed(true)}
          className="p-1 hover:bg-gray-200 rounded transition-colors"
          title="Collapse panel"
        >
          <svg
            className="w-4 h-4 text-gray-500"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M11 19l-7-7 7-7m8 14l-7-7 7-7"
            />
          </svg>
        </button>
      </div>

      {/* Tabs */}
      <div className="flex border-b border-gray-200">
        <button
          type="button"
          onClick={() => setActiveTab('properties')}
          className={`flex-1 px-3 py-2 text-xs font-medium transition-colors ${
            activeTab === 'properties'
              ? 'text-blue-600 border-b-2 border-blue-600 bg-blue-50/50'
              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-50'
          }`}
        >
          Properties
        </button>
        <button
          type="button"
          onClick={() => setActiveTab('styles')}
          className={`flex-1 px-3 py-2 text-xs font-medium transition-colors ${
            activeTab === 'styles'
              ? 'text-blue-600 border-b-2 border-blue-600 bg-blue-50/50'
              : 'text-gray-500 hover:text-gray-700 hover:bg-gray-50'
          }`}
        >
          Styles
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {activeTab === 'properties' ? (
          selectedBlock ? (
            <BlockPropertiesEditor block={selectedBlock} />
          ) : (
            <DocumentPropertiesEditor />
          )
        ) : (
          selectedBlock ? (
            <BlockStyleEditor block={selectedBlock} />
          ) : (
            <DocumentStyleEditor />
          )
        )}
      </div>
    </div>
  );
}
