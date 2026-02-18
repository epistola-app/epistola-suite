/**
 * Typed EventEmitter for the editor engine.
 *
 * Provides type-safe event subscription and emission. The event map
 * is defined via a generic parameter, so listeners are fully typed.
 */

import type { TemplateDocument, NodeId } from '../types/index.js'
import type { DocumentIndexes } from './indexes.js'

// ---------------------------------------------------------------------------
// Event map for the editor engine
// ---------------------------------------------------------------------------

export type EngineEvents = {
  'doc:change': { doc: TemplateDocument; indexes: DocumentIndexes }
  'selection:change': { nodeId: NodeId | null }
  'example:change': { index: number; example: object | undefined }
  'component-state:change': { key: string; value: unknown }
}

// ---------------------------------------------------------------------------
// Generic typed EventEmitter
// ---------------------------------------------------------------------------

export class EventEmitter<Events extends Record<string, unknown>> {
  private _listeners = new Map<keyof Events, Set<(data: never) => void>>()

  /** Subscribe to an event. Returns an unsubscribe function. */
  on<K extends keyof Events>(event: K, listener: (data: Events[K]) => void): () => void {
    let set = this._listeners.get(event)
    if (!set) {
      set = new Set()
      this._listeners.set(event, set)
    }
    set.add(listener as (data: never) => void)
    return () => { this.off(event, listener) }
  }

  /** Remove a specific listener for an event. */
  off<K extends keyof Events>(event: K, listener: (data: Events[K]) => void): void {
    const set = this._listeners.get(event)
    if (set) {
      set.delete(listener as (data: never) => void)
    }
  }

  /** Emit an event to all registered listeners. */
  emit<K extends keyof Events>(event: K, data: Events[K]): void {
    const set = this._listeners.get(event)
    if (!set) return
    for (const listener of set) {
      (listener as (data: Events[K]) => void)(data)
    }
  }
}
