/**
 * Core template types - framework-agnostic.
 * These define the data structures for document templates.
 */

import type { CSSStyles } from "./styles.ts";
import type { RichTextContent } from "./richtext.ts";

// ============================================================================
// Template Structure
// ============================================================================

export interface Template {
  id: string;
  name: string;
  version: number;
  themeId?: string | null;
  pageSettings: PageSettings;
  blocks: Block[];
  documentStyles: DocumentStyles;
}

export interface PageSettings {
  format: "A4" | "Letter" | "Custom";
  orientation: "portrait" | "landscape";
  margins: { top: number; right: number; bottom: number; left: number };
}

/**
 * Document-level styles that cascade to child blocks.
 */
export interface DocumentStyles {
  // Typography
  fontFamily?: string;
  fontSize?: string;
  fontWeight?: string;
  color?: string;
  lineHeight?: string;
  letterSpacing?: string;
  textAlign?: "left" | "center" | "right" | "justify";
  // Background
  backgroundColor?: string;
}

// ============================================================================
// Block Types
// ============================================================================

export type Block =
  | ContainerBlock
  | TextBlock
  | ConditionalBlock
  | LoopBlock
  | ColumnsBlock
  | TableBlock
  | PageBreakBlock
  | PageHeaderBlock
  | PageFooterBlock;

export type BlockType = Block["type"];

export interface BaseBlock {
  id: string;
  type: string;
  styles?: CSSStyles;
}

export interface ContainerBlock extends BaseBlock {
  type: "container";
  children: Block[];
}

export interface TextBlock extends BaseBlock {
  type: "text";
  content: RichTextContent;
}

export interface ConditionalBlock extends BaseBlock {
  type: "conditional";
  condition: Expression;
  inverse?: boolean; // if true, shows content when condition is FALSE
  children: Block[]; // blocks shown when condition matches (or inverse matches)
}

export interface LoopBlock extends BaseBlock {
  type: "loop";
  expression: Expression;
  itemAlias: string;
  indexAlias?: string;
  children: Block[];
}

export interface ColumnsBlock extends BaseBlock {
  type: "columns";
  columns: Column[];
  gap?: number; // spacing between columns in pixels
}

export interface Column {
  id: string;
  size: number; // ratio/weight for flexbox (e.g., 1, 2, 3)
  children: Block[];
}

export interface TableBlock extends BaseBlock {
  type: "table";
  rows: TableRow[];
  columnWidths?: number[]; // ratio-based widths for each column
  borderStyle?: "none" | "all" | "horizontal" | "vertical";
}

export interface TableRow {
  id: string;
  cells: TableCell[];
  isHeader?: boolean;
}

export interface TableCell {
  id: string;
  children: Block[]; // Blocks inside the cell (text, conditional, etc.)
  colspan?: number;
  rowspan?: number;
  styles?: CSSStyles;
}

export interface PageBreakBlock extends BaseBlock {
  type: "pagebreak";
}

export interface PageHeaderBlock extends BaseBlock {
  type: "pageheader";
  children: Block[];
}

export interface PageFooterBlock extends BaseBlock {
  type: "pagefooter";
  children: Block[];
}

// ============================================================================
// Expressions
// ============================================================================

/**
 * Expression language for template expressions.
 *
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
// Preview & Testing
// ============================================================================

export interface PreviewOverrides {
  conditionals: Record<string, "data" | "show" | "hide">;
  loops: Record<string, number | "data">;
}

// ============================================================================
// JSON Types
// ============================================================================

export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonObject
  | JsonArray;

export interface JsonObject {
  [key: string]: JsonValue;
}

export type JsonArray = JsonValue[];

// ============================================================================
// Data Examples
// ============================================================================

/**
 * Named example data set that adheres to the template's dataModel schema.
 */
export interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}

// ============================================================================
// Theme
// ============================================================================

/**
 * Simplified theme for editor dropdown.
 */
export interface ThemeSummary {
  id: string;
  name: string;
  description?: string;
}

// ============================================================================
// Validation
// ============================================================================

export interface ValidationError {
  message: string;
  path: string;
}

export interface ValidationErrorResponse {
  errors: Record<string, ValidationError[]>;
}

// ============================================================================
// API Response Types
// ============================================================================

export interface DocumentTemplateResponse {
  id: number;
  name: string;
  templateModel: Template | null;
  dataModel: JsonObject | null; // JSON Schema
  dataExamples: DataExample[];
  createdAt: string;
  lastModified: string;
}
