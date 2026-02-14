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
        <div class="panel-heading">Blocks</div>

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
                  @click=${() => this._handleInsert(def.type)}
                  title="Click to insert ${def.label}"
                >
                  <span class="palette-item-icon">+</span>
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
