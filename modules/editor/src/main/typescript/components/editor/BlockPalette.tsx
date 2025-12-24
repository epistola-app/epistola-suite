import { useDraggable } from "@dnd-kit/core";
import { v4 as uuidv4 } from "uuid";
import type { Block } from "../../types/template";

interface BlockTypeConfig {
  type: Block["type"];
  label: string;
  icon: string;
  createBlock: () => Block;
}

const blockTypes: BlockTypeConfig[] = [
  {
    type: "text",
    label: "Text",
    icon: "T",
    createBlock: () => ({
      id: uuidv4(),
      type: "text",
      content: {
        type: "doc",
        content: [{ type: "paragraph", content: [{ type: "text", text: "Enter text here..." }] }],
      },
    }),
  },
  {
    type: "container",
    label: "Container",
    icon: "▢",
    createBlock: () => ({
      id: uuidv4(),
      type: "container",
      children: [],
    }),
  },
  {
    type: "conditional",
    label: "If",
    icon: "?",
    createBlock: () => ({
      id: uuidv4(),
      type: "conditional",
      condition: { raw: "true" },
      children: [],
    }),
  },
  {
    type: "loop",
    label: "Loop",
    icon: "↻",
    createBlock: () => ({
      id: uuidv4(),
      type: "loop",
      expression: { raw: "items" },
      itemAlias: "item",
      children: [],
    }),
  },
  {
    type: "columns",
    label: "Columns",
    icon: "▦",
    createBlock: () => ({
      id: uuidv4(),
      type: "columns",
      gap: 16,
      columns: [
        {
          id: uuidv4(),
          size: 1,
          children: [],
        },
        {
          id: uuidv4(),
          size: 1,
          children: [],
        },
      ],
    }),
  },
  {
    type: "table",
    label: "Table",
    icon: "⊞",
    createBlock: () => ({
      id: uuidv4(),
      type: "table",
      borderStyle: "all",
      rows: [
        {
          id: uuidv4(),
          isHeader: true,
          cells: [
            {
              id: uuidv4(),
              children: [
                {
                  id: uuidv4(),
                  type: "text",
                  content: {
                    type: "doc",
                    content: [{ type: "paragraph", content: [{ type: "text", text: "Header 1" }] }],
                  },
                },
              ],
            },
            {
              id: uuidv4(),
              children: [
                {
                  id: uuidv4(),
                  type: "text",
                  content: {
                    type: "doc",
                    content: [{ type: "paragraph", content: [{ type: "text", text: "Header 2" }] }],
                  },
                },
              ],
            },
          ],
        },
        {
          id: uuidv4(),
          isHeader: false,
          cells: [
            {
              id: uuidv4(),
              children: [
                {
                  id: uuidv4(),
                  type: "text",
                  content: {
                    type: "doc",
                    content: [{ type: "paragraph", content: [{ type: "text", text: "Cell 1" }] }],
                  },
                },
              ],
            },
            {
              id: uuidv4(),
              children: [
                {
                  id: uuidv4(),
                  type: "text",
                  content: {
                    type: "doc",
                    content: [{ type: "paragraph", content: [{ type: "text", text: "Cell 2" }] }],
                  },
                },
              ],
            },
          ],
        },
      ],
    }),
  },
];

function DraggableBlockType({ config }: { config: BlockTypeConfig }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `palette-${config.type}`,
    data: {
      type: "palette",
      blockType: config.type,
      createBlock: config.createBlock,
    },
  });

  return (
    <div
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      className={`
        flex flex-col items-center justify-center p-3 rounded-lg border border-gray-200
        bg-white hover:border-blue-400 hover:shadow-sm cursor-grab transition-all
        ${isDragging ? "opacity-50" : ""}
      `}
    >
      <span className="text-xl mb-1">{config.icon}</span>
      <span className="text-xs text-gray-600">{config.label}</span>
    </div>
  );
}

export function BlockPalette() {
  return (
    <div className="p-3 border-b border-gray-200 bg-gray-50">
      <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Blocks</h3>
      <div className="grid grid-cols-4 gap-2">
        {blockTypes.map((config) => (
          <DraggableBlockType key={config.type} config={config} />
        ))}
      </div>
    </div>
  );
}
