import type {LucideIcon} from "lucide-react";
import {
    BetweenVerticalStart,
    CircleQuestionMark,
    Grid3x3,
    Minus,
    PanelBottom,
    PanelTop,
    RefreshCcw,
    Square,
    TextAlignStart,
} from "lucide-react";
import {v4 as uuidv4} from "uuid";
import type {Block, TableCell, TextBlock} from "../../types/template";

export interface BlockTypeConfig {
  type: Block["type"];
  label: string;
  description: string;
  icon: LucideIcon;
  createBlock: () => Block;
}

// Helper: Create a text block with given content
function createTextBlock(text: string): TextBlock {
  return {
    id: uuidv4(),
    type: "text",
    content: {
      type: "doc",
      content: [
        {
          type: "paragraph",
          content: [{ type: "text", text }],
        },
      ],
    },
  };
}

// Helper: Create a table cell with text content
function createTableCell(text: string): TableCell {
  return {
    id: uuidv4(),
    children: [createTextBlock(text)],
  };
}

export const blockTypes: BlockTypeConfig[] = [
  {
    type: "text",
    label: "Text",
    description: "Rich text content",
    icon: TextAlignStart,
    createBlock: () => createTextBlock("Enter text here..."),
  },
  {
    type: "container",
    label: "Container",
    description: "Group blocks",
    icon: Square,
    createBlock: () => ({
      id: uuidv4(),
      type: "container",
      children: [],
    }),
  },
  {
    type: "conditional",
    label: "Conditional",
    description: "If/else logic",
    icon: CircleQuestionMark,
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
    description: "Repeat content",
    icon: RefreshCcw,
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
    description: "Multi-column layout",
    icon: BetweenVerticalStart,
    createBlock: () => ({
      id: uuidv4(),
      type: "columns",
      gap: 16,
      columns: [
        { id: uuidv4(), size: 1, children: [] },
        { id: uuidv4(), size: 1, children: [] },
      ],
    }),
  },
  {
    type: "table",
    label: "Table",
    description: "Data table",
    icon: Grid3x3,
    createBlock: () => ({
      id: uuidv4(),
      type: "table",
      borderStyle: "all",
      rows: [
        {
          id: uuidv4(),
          isHeader: true,
          cells: [createTableCell("Header 1"), createTableCell("Header 2")],
        },
        {
          id: uuidv4(),
          isHeader: false,
          cells: [createTableCell("Cell 1"), createTableCell("Cell 2")],
        },
      ],
    }),
  },
  {
    type: "pagebreak",
    label: "Page Break",
    description: "Force new page",
    icon: Minus,
    createBlock: () => ({
      id: uuidv4(),
      type: "pagebreak",
    }),
  },
  {
    type: "pageheader",
    label: "Page Header",
    description: "Header that appears on every page",
    icon: PanelTop,
    createBlock: () => ({
      id: uuidv4(),
      type: "pageheader",
      children: [],
    }),
  },
  {
    type: "pagefooter",
    label: "Page Footer",
    description: "Footer that appears on every page",
    icon: PanelBottom,
    createBlock: () => ({
      id: uuidv4(),
      type: "pagefooter",
      children: [],
    }),
  },
];
