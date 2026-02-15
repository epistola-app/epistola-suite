import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import { draggable } from '@atlaskit/pragmatic-drag-and-drop/element/adapter'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { ComponentDefinition, ComponentCategory } from '../engine/registry.js'
import type { DragData } from '../dnd/types.js'
import { icon, type IconName, ICONS } from './icons.js'

const CATEGORY_ORDER: ComponentCategory[] = ['content', 'layout', 'logic', 'page']
const CATEGORY_LABELS: Record<ComponentCategory, string> = {
  content: 'Content',
  layout: 'Layout',
  logic: 'Logic',
  page: 'Page',
}

@customElement('epistola-palette')
export class EpistolaPalette extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine

  private _dndCleanup: (() => void) | null = null

  private _handleInsert(type: string) {
    if (!this.engine) return

    const doc = this.engine.doc
    const rootNode = doc.nodes[doc.root]
    if (!rootNode || rootNode.slots.length === 0) return

    const targetSlotId = rootNode.slots[0]
    const { node, slots } = this.engine.registry.createNode(type)

    this.engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId,
      index: -1,
    })

    // Select the newly inserted node
    this.engine.selectNode(node.id)
  }

  override updated() {
    this._dndCleanup?.()
    this._dndCleanup = this._setupDnD()
  }

  override disconnectedCallback() {
    this._dndCleanup?.()
    this._dndCleanup = null
    super.disconnectedCallback()
  }

  private _setupDnD(): (() => void) | null {
    const items = this.querySelectorAll<HTMLElement>('.palette-item[data-block-type]')
    if (items.length === 0) return null

    const cleanups: (() => void)[] = []

    for (const item of items) {
      const blockType = item.dataset.blockType
      if (!blockType) continue

      const cleanup = draggable({
        element: item,
        getInitialData: (): DragData => ({ source: 'palette', blockType }),
        onDragStart: () => item.classList.add('dragging'),
        onDrop: () => item.classList.remove('dragging'),
      })
      cleanups.push(cleanup)
    }

    return () => cleanups.forEach(fn => fn())
  }

  private _renderIcon(def: ComponentDefinition) {
    if (def.icon && def.icon in ICONS) {
      return icon(def.icon as IconName, 14)
    }
    return html`<span style="font-size: 11px; color: var(--ep-gray-400)">+</span>`
  }

  override render() {
    if (!this.engine) {
      return html`<div class="panel-empty">No engine</div>`
    }

    const definitions = this.engine.registry
      .insertable()
      .filter(d => d.type !== 'root') // Don't show root in palette

    const grouped = new Map<ComponentCategory, ComponentDefinition[]>()
    for (const cat of CATEGORY_ORDER) {
      grouped.set(cat, [])
    }
    for (const def of definitions) {
      const list = grouped.get(def.category)
      if (list) list.push(def)
    }

    return html`
      <div class="epistola-palette">
        ${CATEGORY_ORDER.map(cat => {
          const items = grouped.get(cat)
          if (!items || items.length === 0) return ''

          return html`
            <div class="palette-category">
              <div class="palette-category-label">
                ${CATEGORY_LABELS[cat]}
              </div>
              ${items.map(def => html`
                <button
                  class="palette-item"
                  data-block-type=${def.type}
                  @click=${() => this._handleInsert(def.type)}
                  title="Click to insert ${def.label}"
                >
                  <span class="palette-item-icon">${this._renderIcon(def)}</span>
                  <span>${def.label}</span>
                </button>
              `)}
            </div>
          `
        })}
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-palette': EpistolaPalette
  }
}
