/**
 * Plugin type definitions for the Epistola template editor.
 *
 * Plugins extend the editor with additional sidebar tabs, toolbar
 * actions, and lifecycle hooks. See docs/plugins.md for the full
 * design document.
 */

import type { TemplateResult } from 'lit'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { TemplateDocument, NodeId } from '../types/index.js'

// ---------------------------------------------------------------------------
// Plugin context — passed to plugins during init and sidebar rendering
// ---------------------------------------------------------------------------

export interface PluginContext {
  /** The editor engine instance — plugins can dispatch commands via engine.dispatch() */
  engine: EditorEngine

  /** Current document state */
  doc: TemplateDocument

  /** Currently selected node, or null */
  selectedNodeId: NodeId | null
}

// ---------------------------------------------------------------------------
// Plugin contributions
// ---------------------------------------------------------------------------

export interface SidebarTabContribution {
  /** Tab identifier (used as the active tab key) */
  id: string

  /** Display label shown on the tab button */
  label: string

  /** Optional icon identifier (Lucide icon name) */
  icon?: string

  /** Renders the tab content. Called reactively when context changes. */
  render: (context: PluginContext) => TemplateResult
}

export interface ToolbarAction {
  /** Action identifier */
  id: string

  /** Tooltip / aria label */
  label: string

  /** Icon identifier (Lucide icon name) */
  icon: string

  /** Called when the toolbar button is clicked */
  onClick: () => void
}

// ---------------------------------------------------------------------------
// Plugin lifecycle
// ---------------------------------------------------------------------------

/** Cleanup function returned by plugin init(). */
export type PluginDisposeFn = () => void

// ---------------------------------------------------------------------------
// Main plugin interface
// ---------------------------------------------------------------------------

export interface EditorPlugin {
  /** Unique plugin identifier (matches backend plugin id) */
  id: string

  /** Sidebar tab contributed by this plugin (optional) */
  sidebarTab?: SidebarTabContribution

  /** Toolbar actions contributed by this plugin (optional) */
  toolbarActions?: ToolbarAction[]

  /**
   * Called when the editor engine is ready. Returns a dispose function
   * for cleanup when the editor unmounts.
   */
  init(context: PluginContext): PluginDisposeFn
}
