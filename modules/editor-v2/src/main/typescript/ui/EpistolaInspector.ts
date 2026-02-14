import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, Node } from '../types/model.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { ComponentDefinition, InspectorField } from '../engine/registry.js'

@customElement('epistola-inspector')
export class EpistolaInspector extends LitElement {
  override createRenderRoot() {
    return this
  }

  @property({ attribute: false }) engine?: EditorEngine
  @property({ attribute: false }) doc?: TemplateDocument
  @property({ attribute: false }) selectedNodeId: NodeId | null = null

  override render() {
    if (!this.engine || !this.doc) {
      return html`<div class="p-3 text-sm text-gray-400">No document</div>`
    }

    if (!this.selectedNodeId) {
      return html`
        <div class="p-3">
          <div class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
            Inspector
          </div>
          <div class="text-sm text-gray-400">Select a block to inspect</div>
        </div>
      `
    }

    const node = this.doc.nodes[this.selectedNodeId]
    if (!node) return html`<div class="p-3 text-sm text-red-400">Node not found</div>`

    const def = this.engine.registry.get(node.type)

    return html`
      <div class="epistola-inspector p-3">
        <div class="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
          Inspector
        </div>

        <!-- Node info -->
        <div class="mb-4 pb-3 border-b border-gray-200">
          <div class="text-sm font-medium text-gray-800">${def?.label ?? node.type}</div>
          <div class="text-xs text-gray-400 font-mono mt-0.5">${node.id}</div>
        </div>

        <!-- Props -->
        ${def?.inspector && def.inspector.length > 0
          ? this._renderInspectorFields(node, def)
          : nothing
        }

        <!-- Style preset -->
        <div class="mb-4">
          <label class="block text-xs font-medium text-gray-600 mb-1">Style Preset</label>
          <input
            type="text"
            class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:ring-1 focus:ring-blue-500 focus:border-blue-500"
            .value=${node.stylePreset ?? ''}
            @change=${(e: Event) => this._handleStylePreset(e)}
            placeholder="None"
          />
        </div>

        <!-- Delete -->
        <div class="mt-6 pt-3 border-t border-gray-200">
          <button
            class="w-full px-3 py-1.5 text-sm bg-red-50 text-red-600 hover:bg-red-100 rounded border border-red-200"
            @click=${this._handleDelete}
          >
            Delete Block
          </button>
        </div>
      </div>
    `
  }

  private _renderInspectorFields(node: Node, def: ComponentDefinition): unknown {
    return html`
      <div class="mb-4">
        <div class="text-xs font-medium text-gray-600 mb-2">Properties</div>
        ${def.inspector.map(field => this._renderField(node, field))}
      </div>
    `
  }

  private _renderField(node: Node, field: InspectorField): unknown {
    const props = node.props ?? {}
    const value = getNestedValue(props, field.key)

    switch (field.type) {
      case 'text':
        return html`
          <div class="mb-2">
            <label class="block text-xs text-gray-500 mb-0.5">${field.label}</label>
            <input
              type="text"
              class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:ring-1 focus:ring-blue-500"
              .value=${String(value ?? '')}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).value)}
            />
          </div>
        `
      case 'number':
        return html`
          <div class="mb-2">
            <label class="block text-xs text-gray-500 mb-0.5">${field.label}</label>
            <input
              type="number"
              class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:ring-1 focus:ring-blue-500"
              .value=${String(value ?? 0)}
              @change=${(e: Event) => this._handlePropChange(field.key, Number((e.target as HTMLInputElement).value))}
            />
          </div>
        `
      case 'boolean':
        return html`
          <div class="mb-2 flex items-center gap-2">
            <input
              type="checkbox"
              class="rounded border-gray-300"
              .checked=${Boolean(value)}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).checked)}
            />
            <label class="text-xs text-gray-500">${field.label}</label>
          </div>
        `
      case 'select':
        return html`
          <div class="mb-2">
            <label class="block text-xs text-gray-500 mb-0.5">${field.label}</label>
            <select
              class="w-full px-2 py-1 text-sm border border-gray-300 rounded focus:ring-1 focus:ring-blue-500"
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLSelectElement).value)}
            >
              ${(field.options ?? []).map(opt => html`
                <option .value=${String(opt.value)} ?selected=${value === opt.value}>${opt.label}</option>
              `)}
            </select>
          </div>
        `
      case 'expression':
        return html`
          <div class="mb-2">
            <label class="block text-xs text-gray-500 mb-0.5">${field.label}</label>
            <input
              type="text"
              class="w-full px-2 py-1 text-sm border border-gray-300 rounded font-mono focus:ring-1 focus:ring-blue-500"
              .value=${String(value ?? '')}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).value)}
              placeholder="Expression..."
            />
          </div>
        `
      default:
        return html`
          <div class="mb-2">
            <label class="block text-xs text-gray-500 mb-0.5">${field.label}</label>
            <div class="text-xs text-gray-400">Unsupported field type: ${field.type}</div>
          </div>
        `
    }
  }

  private _handlePropChange(key: string, value: unknown) {
    if (!this.engine || !this.selectedNodeId) return

    const node = this.doc!.nodes[this.selectedNodeId]
    if (!node) return

    const newProps = structuredClone(node.props ?? {})
    setNestedValue(newProps, key, value)

    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.selectedNodeId,
      props: newProps,
    })
  }

  private _handleStylePreset(e: Event) {
    if (!this.engine || !this.selectedNodeId) return
    const value = (e.target as HTMLInputElement).value
    this.engine.dispatch({
      type: 'SetStylePreset',
      nodeId: this.selectedNodeId,
      stylePreset: value || undefined,
    })
  }

  private _handleDelete() {
    if (!this.engine || !this.selectedNodeId) return
    this.engine.dispatch({
      type: 'RemoveNode',
      nodeId: this.selectedNodeId,
    })
    this.engine.selectNode(null)
  }
}

// ---------------------------------------------------------------------------
// Nested value helpers
// ---------------------------------------------------------------------------

function getNestedValue(obj: Record<string, unknown>, path: string): unknown {
  const keys = path.split('.')
  let current: unknown = obj
  for (const key of keys) {
    if (current == null || typeof current !== 'object') return undefined
    current = (current as Record<string, unknown>)[key]
  }
  return current
}

function setNestedValue(obj: Record<string, unknown>, path: string, value: unknown): void {
  const keys = path.split('.')
  let current: Record<string, unknown> = obj
  for (let i = 0; i < keys.length - 1; i++) {
    const key = keys[i]
    if (typeof current[key] !== 'object' || current[key] == null) {
      current[key] = {}
    }
    current = current[key] as Record<string, unknown>
  }
  current[keys[keys.length - 1]] = value
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-inspector': EpistolaInspector
  }
}
