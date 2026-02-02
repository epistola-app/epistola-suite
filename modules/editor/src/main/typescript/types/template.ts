import type {JSONContent} from "@tiptap/react";
import type {CSSProperties} from "react";
import {z} from "zod";

export interface Template {
  id: string;
  name: string;
  version: number;
  themeId?: string | null;
  pageSettings: PageSettings;
  blocks: Block[];
  documentStyles: DocumentStyles;
}

// Simplified theme for editor dropdown
export interface ThemeSummary {
  id: string;
  name: string;
  description?: string;
}

// Document-level styles that cascade to child blocks
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

export interface PageSettings {
  format: "A4" | "Letter" | "Custom";
  orientation: "portrait" | "landscape";
  margins: { top: number; right: number; bottom: number; left: number };
}

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

export interface BaseBlock {
  id: string;
  type: string;
  styles?: CSSProperties;
}

export interface ContainerBlock extends BaseBlock {
  type: "container";
  children: Block[];
}

export interface TextBlock extends BaseBlock {
  type: "text";
  content: JSONContent;
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
  styles?: CSSProperties;
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

export interface PreviewOverrides {
  conditionals: Record<string, "data" | "show" | "hide">;
  loops: Record<string, number | "data">;
}

// JSON types (matches ObjectNode on backend)
export const JsonValueSchema: z.ZodType<JsonValue> = z.lazy(() =>
  z.union([
    z.string(),
    z.number(),
    z.boolean(),
    z.null(),
    z.array(JsonValueSchema),
    z.record(z.string(), JsonValueSchema),
  ]),
);
export type JsonValue = string | number | boolean | null | JsonObject | JsonArray;

export const JsonObjectSchema: z.ZodType<JsonObject> = z.record(z.string(), JsonValueSchema);
export interface JsonObject {
  [key: string]: JsonValue;
}

export const JsonArraySchema: z.ZodType<JsonArray> = z.array(JsonValueSchema);
export type JsonArray = JsonValue[];

// Named example data set that adheres to the template's dataModel schema
export interface DataExample {
  id: string;
  name: string;
  data: JsonObject;
}
// Zod schema for runtime validation
// Uses z.record with z.unknown() to avoid recursive type depth issues while still
// validating the structure is a valid object (not array/primitive)
export const DataExampleSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  data: z.record(z.string(), z.unknown()),
});

// Full document template response from API
export interface DocumentTemplateResponse {
  id: number;
  name: string;
  templateModel: Template | null;
  dataModel: JsonObject | null; // JSON Schema
  dataExamples: DataExample[];
  createdAt: string;
  lastModified: string;
}

// Validation error from schema validation
export interface ValidationError {
  message: string;
  path: string;
}

// Error response when validation fails
export interface ValidationErrorResponse {
  errors: Record<string, ValidationError[]>;
}
