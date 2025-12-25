import type { Block } from "@/types/template";

export function BlockPropertiesEditor({ block }: { block: Block }) {
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
