/**
 * TypeScript types for the Epistola template document model.
 *
 * These are the authoritative TS types used by the editor and other
 * frontend code. They match the JSON Schemas in ../schemas/ 1:1.
 *
 * NodeId and SlotId use branded types to prevent accidentally mixing
 * them up at the type level (the runtime representation is plain string).
 */

// ---------------------------------------------------------------------------
// Branded ID types
// ---------------------------------------------------------------------------

export type NodeId = string & { readonly __brand: 'NodeId' }
export type SlotId = string & { readonly __brand: 'SlotId' }

// ---------------------------------------------------------------------------
// Document root
// ---------------------------------------------------------------------------

export interface TemplateDocument {
  modelVersion: 1
  root: NodeId
  nodes: Record<NodeId, Node>
  slots: Record<SlotId, Slot>
  themeRef: ThemeRef
  pageSettingsOverride?: PageSettings
  documentStylesOverride?: DocumentStyles
}

// ---------------------------------------------------------------------------
// Nodes
// ---------------------------------------------------------------------------

export interface Node {
  id: NodeId
  type: string
  slots: SlotId[]
  styles?: Record<string, unknown>
  stylePreset?: string
  props?: Record<string, unknown>
}

// ---------------------------------------------------------------------------
// Slots
// ---------------------------------------------------------------------------

export interface Slot {
  id: SlotId
  nodeId: NodeId
  name: string
  children: NodeId[]
}

// ---------------------------------------------------------------------------
// Theme reference
// ---------------------------------------------------------------------------

export type ThemeRef = ThemeRefInherit | ThemeRefOverride

export interface ThemeRefInherit {
  type: 'inherit'
}

export interface ThemeRefOverride {
  type: 'override'
  themeId: string
}

// ---------------------------------------------------------------------------
// Page settings
// ---------------------------------------------------------------------------

export type PageFormat = 'A4' | 'Letter' | 'Custom'
export type Orientation = 'portrait' | 'landscape'
export type TextAlign = 'left' | 'center' | 'right' | 'justify'

export interface Margins {
  top: number
  right: number
  bottom: number
  left: number
}

export interface PageSettings {
  format: PageFormat
  orientation: Orientation
  margins: Margins
}

// ---------------------------------------------------------------------------
// Document styles
// ---------------------------------------------------------------------------

export interface DocumentStyles {
  fontFamily?: string
  fontSize?: string
  fontWeight?: string
  color?: string
  lineHeight?: string
  letterSpacing?: string
  textAlign?: TextAlign
  backgroundColor?: string
}

// ---------------------------------------------------------------------------
// Expression
// ---------------------------------------------------------------------------

export type ExpressionLanguage = 'jsonata' | 'javascript' | 'simple_path'

export interface Expression {
  raw: string
  language: ExpressionLanguage
}
