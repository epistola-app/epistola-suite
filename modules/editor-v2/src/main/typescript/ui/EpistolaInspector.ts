import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, Node } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { ComponentDefinition, InspectorField } from '../engine/registry.js'
import { getNestedValue, setNestedValue } from '../engine/props.js'

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
      return html`<div class="panel-empty">No document</div>`
    }

    if (!this.selectedNodeId) {
      return html`
        <div class="epistola-inspector">
          <div class="panel-heading">Inspector</div>
          <div class="panel-empty">Select a block to inspect</div>
        </div>
      `
    }

    const node = this.doc.nodes[this.selectedNodeId]
    if (!node) return html`<div class="panel-error">Node not found</div>`

    const def = this.engine.registry.get(node.type)

    return html`
      <div class="epistola-inspector">
        <div class="panel-heading">Inspector</div>

        <!-- Node info -->
        <div class="inspector-node-info">
          <div class="inspector-node-label">${def?.label ?? node.type}</div>
          <div class="inspector-node-id">${node.id}</div>
        </div>

        <!-- Props -->
        ${def?.inspector && def.inspector.length > 0
          ? this._renderInspectorFields(node, def)
          : nothing
        }

        <!-- Style preset -->
        <div class="inspector-section">
          <label class="inspector-field-label">Style Preset</label>
          <input
            type="text"
            class="ep-input"
            .value=${node.stylePreset ?? ''}
            @change=${(e: Event) => this._handleStylePreset(e)}
            placeholder="None"
          />
        </div>

        <!-- Delete -->
        <div class="inspector-delete-section">
          <button
            class="ep-btn-danger"
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
      <div class="inspector-section">
        <div class="inspector-section-label">Properties</div>
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
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <input
              type="text"
              class="ep-input"
              .value=${String(value ?? '')}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).value)}
            />
          </div>
        `
      case 'number':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <input
              type="number"
              class="ep-input"
              .value=${String(value ?? 0)}
              @change=${(e: Event) => this._handlePropChange(field.key, Number((e.target as HTMLInputElement).value))}
            />
          </div>
        `
      case 'boolean':
        return html`
          <div class="inspector-checkbox-field">
            <input
              type="checkbox"
              class="ep-checkbox"
              .checked=${Boolean(value)}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).checked)}
            />
            <label class="inspector-field-label">${field.label}</label>
          </div>
        `
      case 'select':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <select
              class="ep-select"
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
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <input
              type="text"
              class="ep-input mono"
              .value=${String(value ?? '')}
              @change=${(e: Event) => this._handlePropChange(field.key, (e.target as HTMLInputElement).value)}
              placeholder="Expression..."
            />
          </div>
        `
      default:
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <div class="inspector-unsupported">Unsupported field type: ${field.type}</div>
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

declare global {
  interface HTMLElementTagNameMap {
    'epistola-inspector': EpistolaInspector
  }
}
