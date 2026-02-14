/**
 * Headless Editor Core Types
 *
 * Pure TypeScript types with no framework dependencies.
 * Used by both the headless core and UI adapters.
 */

// ============================================================================
// CSS Types (framework-agnostic replacement for React.CSSProperties)
// ============================================================================

/**
 * CSS property values - a subset of common CSS properties.
 * This is a framework-agnostic alternative to React.CSSProperties.
 */
export interface CSSStyles {
  // Layout
  display?: string;
  position?: string;
  top?: string | number;
  right?: string | number;
  bottom?: string | number;
  left?: string | number;
  width?: string | number;
  height?: string | number;
  minWidth?: string | number;
  minHeight?: string | number;
  maxWidth?: string | number;
  maxHeight?: string | number;
  overflow?: string;
  overflowX?: string;
  overflowY?: string;

  // Flexbox
  flex?: string | number;
  flexDirection?: string;
  flexWrap?: string;
  flexGrow?: number;
  flexShrink?: number;
  flexBasis?: string | number;
  justifyContent?: string;
  alignItems?: string;
  alignContent?: string;
  alignSelf?: string;
  gap?: string | number;
  rowGap?: string | number;
  columnGap?: string | number;

  // Grid
  gridTemplateColumns?: string;
  gridTemplateRows?: string;
  gridColumn?: string;
  gridRow?: string;
  gridArea?: string;

  // Spacing
  margin?: string | number;
  marginTop?: string | number;
  marginRight?: string | number;
  marginBottom?: string | number;
  marginLeft?: string | number;
  padding?: string | number;
  paddingTop?: string | number;
  paddingRight?: string | number;
  paddingBottom?: string | number;
  paddingLeft?: string | number;

  // Typography
  fontFamily?: string;
  fontSize?: string | number;
  fontWeight?: string | number;
  fontStyle?: string;
  lineHeight?: string | number;
  letterSpacing?: string | number;
  textAlign?: string;
  textDecoration?: string;
  textTransform?: string;
  whiteSpace?: string;
  wordBreak?: string;
  wordWrap?: string;

  // Colors
  color?: string;
  backgroundColor?: string;
  opacity?: number;

  // Borders
  border?: string;
  borderTop?: string;
  borderRight?: string;
  borderBottom?: string;
  borderLeft?: string;
  borderWidth?: string | number;
  borderStyle?: string;
  borderColor?: string;
  borderRadius?: string | number;

  // Shadows
  boxShadow?: string;
  textShadow?: string;

  // Transforms
  transform?: string;
  transformOrigin?: string;

  // Transitions
  transition?: string;
  transitionProperty?: string;
  transitionDuration?: string;
  transitionTimingFunction?: string;

  // Other
  cursor?: string;
  visibility?: string;
  zIndex?: number;
  objectFit?: string;
  objectPosition?: string;

  // Allow any additional string properties for flexibility
  [key: string]: string | number | undefined;
}

// ============================================================================
// Expression Types
// ============================================================================

/**
 * Expression language for template expressions.
 * - jsonata: Concise syntax purpose-built for JSON transformation (recommended)
 * - javascript: Full JS power for advanced use cases
 */
export type ExpressionLanguage = "jsonata" | "javascript";

/**
 * An expression that can be evaluated against input data.
 */
export interface Expression {
  raw: string;
  language?: ExpressionLanguage; // defaults to "jsonata"
}

// ============================================================================
// Block Types
// ============================================================================

/**
 * Built-in block types shipped by headless editor.
 */
export type BuiltInBlockType =
  | "text"
  | "container"
  | "conditional"
  | "loop"
  | "columns"
  | "table"
  | "pagebreak"
  | "pageheader"
  | "pagefooter";

/**
 * Runtime block discriminator.
 */
export type BlockType = BuiltInBlockType;

/**
 * Runtime list of built-in block types.
 */
export const BUILTIN_BLOCK_TYPES: ReadonlySet<BuiltInBlockType> = new Set([
  "text",
  "container",
  "conditional",
  "loop",
  "columns",
  "table",
  "pagebreak",
  "pageheader",
  "pagefooter",
]);

/**
 * Type guard for narrowing arbitrary strings to supported block types.
 */
export function isBuiltInBlockType(value: string): value is BuiltInBlockType {
  return BUILTIN_BLOCK_TYPES.has(value as BuiltInBlockType);
}

/**
 * Base block interface - all blocks extend this
 */
export interface BaseBlock {
  id: string;
  type: BlockType;
  styles?: CSSStyles;
}

/**
 * TipTap JSONContent structure for rich text
 */
export type TipTapContent = Record<string, unknown> | null;

/**
 * Text block - TipTap JSONContent for rich text (matches backend Map<String, Any>?)
 */
export interface TextBlock extends BaseBlock {
  type: "text";
  content: TipTapContent;
}

/**
 * Container block - holds child blocks
 */
export interface ContainerBlock extends BaseBlock {
  type: "container";
  children: Block[];
}

/**
 * Conditional block - shows content based on expression evaluation
 */
export interface ConditionalBlock extends BaseBlock {
  type: "conditional";
  condition: Expression;
  /** If true, render children when condition evaluates to false. */
  inverse?: boolean;
  /** Blocks rendered when condition (or inverse condition) matches. */
  children: Block[];
}

/**
 * Loop block - iterates over an array expression
 */
export interface LoopBlock extends BaseBlock {
  type: "loop";
  expression: Expression;
  itemAlias: string;
  indexAlias?: string;
  children: Block[];
}

/**
 * Column within a ColumnsBlock
 */
export interface Column {
  id: string;
  /** Relative column width weight (for example: 1, 2, 3). */
  size: number;
  children: Block[];
}

/**
 * Columns block - multi-column layout
 * Note: Uses columns[] array instead of children[]
 */
export interface ColumnsBlock extends BaseBlock {
  type: "columns";
  columns: Column[];
  /** Horizontal spacing between columns, in pixels. */
  gap?: number;
}

/**
 * Table cell
 */
export interface TableCell {
  id: string;
  children: Block[];
  /** Number of columns this cell spans in rendering. */
  colspan?: number;
  /** Number of rows this cell spans in rendering. */
  rowspan?: number;
  styles?: CSSStyles;
}

/**
 * Table row
 */
export interface TableRow {
  id: string;
  cells: TableCell[];
  /** Marks this row as a header row. */
  isHeader?: boolean;
}

/**
 * Table block - tabular layout with rows and cells
 * Note: Uses rows[]/cells[] instead of children[]
 */
export interface TableBlock extends BaseBlock {
  type: "table";
  rows: TableRow[];
  /** Relative widths for each column (for example: [2, 1, 1]). */
  columnWidths?: number[];
  /** Border rendering strategy for table output. */
  borderStyle?: "none" | "all" | "horizontal" | "vertical";
}

/**
 * Page break block - forces a new page in PDF output
 */
export interface PageBreakBlock extends BaseBlock {
  type: "pagebreak";
}

/**
 * Page header block - content repeated at top of each page
 */
export interface PageHeaderBlock extends BaseBlock {
  type: "pageheader";
  children: Block[];
}

/**
 * Page footer block - content repeated at bottom of each page
 */
export interface PageFooterBlock extends BaseBlock {
  type: "pagefooter";
  children: Block[];
}

/**
 * Union of all block types
 */
export type Block =
  | TextBlock
  | ContainerBlock
  | ConditionalBlock
  | LoopBlock
  | ColumnsBlock
  | TableBlock
  | PageBreakBlock
  | PageHeaderBlock
  | PageFooterBlock;

// ============================================================================
// Theme Types
// ============================================================================

/**
 * Theme summary - minimal info for UI theme selection
 */
export interface ThemeSummary {
  id: string;
  name: string;
  description?: string;
}

// ============================================================================
// Template Types
// ============================================================================

/**
 * Page settings for PDF output
 */
export interface PageSettings {
  format: "A4" | "Letter" | "Custom";
  orientation: "portrait" | "landscape";
  margins: { top: number; right: number; bottom: number; left: number };
}

/**
 * Document-level styles that cascade to child blocks
 */
export interface DocumentStyles {
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string;
  color?: string;
  lineHeight?: string;
  letterSpacing?: string;
  textAlign?: "left" | "center" | "right" | "justify";
  backgroundColor?: string;
}

/**
 * Template structure
 */
export interface Template {
  id: string;
  name: string;
  version?: number;
  pageSettings?: PageSettings;
  blocks: Block[];
  documentStyles?: DocumentStyles;
  themeId?: string | null;
}

// ============================================================================
// Constraint Types
// ============================================================================

/**
 * Block constraints - block declares its own rules
 */
export interface BlockConstraints {
  /** Whether this block can contain child blocks. */
  canHaveChildren: boolean;
  /**
   * Child types this block accepts.
   * - `null`: accepts any child block type
   * - `[]`: accepts no children
   */
  allowedChildTypes: BlockType[] | null;
  maxChildren?: number;

  /** Whether this block itself can be dragged. */
  canBeDragged: boolean;
  /** Whether this block can be nested under another block. */
  canBeNested: boolean;
  /**
   * Parent types this block can be nested inside.
   * Use `"root"` to allow top-level placement explicitly.
   * - `null`: unrestricted
   */
  allowedParentTypes: (BlockType | "root")[] | null;
}

/**
 * Validation result
 */

export type ValidResult = {
  readonly valid: true;
};

export type InvalidResult = {
  readonly valid: false;
  readonly errors: string[];
};

export type ValidationResult = ValidResult | InvalidResult;

/**
 * Output capability metadata declared by a block definition.
 * Used by hosts to determine whether a block participates in a generation path.
 */
export interface BlockCapabilities {
  html?: boolean;
  pdf?: boolean;
}

/**
 * Toolbar metadata declared by a block definition.
 */
export interface BlockToolbarConfig {
  visible?: boolean;
  order?: number;
  group?: string;
  label?: string;
  icon?: string;
}

/**
 * Catalog item exposed for editor toolbars.
 */
export interface BlockCatalogItem {
  type: BlockType;
  label: string;
  icon?: string;
  group: string;
  order: number;
  visible: boolean;
  addableAtRoot: boolean;
}

/**
 * Extended block definition metadata used by built-in blocks.
 */
export interface BlockDefinition {
  /** Built-in block type identifier. */
  type: BuiltInBlockType;
  /** Factory for creating a new block instance with defaults. */
  create: (id: string) => Block;
  /** Runtime validation for a block instance. */
  validate: (block: Block) => ValidationResult;
  /** Structure and placement rules for this block. */
  constraints: BlockConstraints;
  /** Optional resolver that declares drop container IDs owned by this block. */
  dropContainers?: (block: Block) => string[];
  label?: string;
  icon?: string;
  category?: string;
  /** Optional generation capabilities metadata (HTML/PDF participation). */
  capabilities?: BlockCapabilities;
  /** Toolbar behavior for block picker presentation. */
  toolbar?: boolean | BlockToolbarConfig;
}

// ============================================================================
// Drag & Drop Types
// ============================================================================

/**
 * Drop position relative to target
 */
export type DropPosition = "before" | "after" | "inside";

/**
 * Drop zone info for UI hints
 */
export interface DropZone {
  targetId: string | null; // null = root
  position: DropPosition;
  targetType: string | null;
}

/**
 * Drag & Drop Port - editor provides, user's adapter consumes
 */
export interface DragDropPort {
  /** Can this block be dragged? */
  canDrag(blockId: string): boolean;

  /** Can this block be dropped at this location? */
  canDrop(
    draggedId: string,
    targetId: string | null,
    position: DropPosition,
  ): boolean;

  /** Get valid drop zones for visual hints */
  getDropZones(draggedId: string): DropZone[];

  /** Execute the drop (position defaults to 'inside' if omitted) */
  drop(
    draggedId: string,
    targetId: string | null,
    index: number,
    position?: DropPosition,
  ): void;
}

// ============================================================================
// JSON Types
// ============================================================================

/**
 * JSON value types for test data
 */
export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonObject
  | JsonArray;

/**
 * JSON object type
 */
export interface JsonObject {
  [key: string]: JsonValue;
}

/**
 * JSON array type
 */
export interface JsonArray extends Array<JsonValue> {}

// ============================================================================
// JSON Schema Types
// ============================================================================

/**
 * Valid JSON Schema field types for data model definition
 */
export type SchemaFieldType =
  | "string"
  | "number"
  | "integer"
  | "boolean"
  | "array"
  | "object";

/**
 * JSON Schema property definition (recursive structure)
 * Supports nested objects and arrays
 */
export interface JsonSchemaProperty {
  type: SchemaFieldType;
  description?: string;
  // For array type
  items?: JsonSchemaProperty;
  // For object type - recursive structure
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
}

/**
 * JSON Schema for template data model
 * Subset of JSON Schema standard focused on data validation needs
 */
export interface JsonSchema {
  type: "object";
  properties?: Record<string, JsonSchemaProperty>;
  required?: string[];
}

// ============================================================================
// Data Example Types
// ============================================================================

/**
 * Data example for template preview
 * Matches backend's DataExample model
 */
export interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}

/**
 * Default test data used when no data example is selected
 */
export const DEFAULT_TEST_DATA: JsonObject = {
  name: "John Doe",
  email: "john@example.com",
  company: "Example Inc.",
  order: {
    id: "ORD-001",
    items: [
      { name: "Product A", price: 99.99, quantity: 2 },
      { name: "Product B", price: 49.99, quantity: 1 },
    ],
    total: 249.97,
  },
};

// ============================================================================
// Editor State Types
// ============================================================================

/**
 * Editor state
 */
export interface EditorState {
  template: Template;
  selectedBlockId: string | null;
  dataExamples: DataExample[];
  selectedDataExampleId: string | null;
  testData: JsonObject;
}

/**
 * Editor callbacks
 */
export interface EditorCallbacks {
  onTemplateChange?: (template: Template) => void;
  onBlockSelect?: (blockId: string | null) => void;
  onBeforeBlockAdd?: (block: Block, parentId: string | null) => boolean;
  onBeforeBlockDelete?: (blockId: string) => boolean;
  onError?: (error: Error) => void;
}

/**
 * Editor configuration
 */
export interface EditorConfig {
  template?: Template;
  callbacks?: EditorCallbacks;
}

// ============================================================================
// Preview Overrides Types
// ============================================================================

/**
 * Preview overrides for conditionals and loops during template preview
 * Allows forcing conditionals to show/hide and loops to iterate a fixed number of times
 */
export interface PreviewOverrides {
  conditionals: Record<string, "data" | "show" | "hide">;
  loops: Record<string, number | "data">;
}

/**
 * Default empty preview overrides state
 */
export const DEFAULT_PREVIEW_OVERRIDES: PreviewOverrides = {
  conditionals: {},
  loops: {},
};
