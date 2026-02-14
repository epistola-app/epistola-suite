import { LitElement, html } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { ComponentDefinition, ComponentCategory } from '../engine/registry.js'

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

  override render() {
    if (!this.engine) {
      return html`<div class="p-3 text-sm text-gray-400">No engine</div>`
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
      <div class="epistola-palette p-2">
        <div class="text-xs font-semibold text-gray-500 uppercase tracking-wider px-2 py-1 mb-1">
          Blocks
        </div>

        ${CATEGORY_ORDER.map(cat => {
          const items = grouped.get(cat)
          if (!items || items.length === 0) return ''

          return html`
            <div class="mb-3">
              <div class="text-xs text-gray-400 px-2 py-1 font-medium">
                ${CATEGORY_LABELS[cat]}
              </div>
              ${items.map(def => html`
                <button
                  class="w-full text-left px-2 py-1.5 rounded text-sm hover:bg-gray-100 flex items-center gap-2 text-gray-700"
                  @click=${() => this._handleInsert(def.type)}
                  title="Click to insert ${def.label}"
                >
                  <span class="text-xs text-gray-400 w-4 text-center">+</span>
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
