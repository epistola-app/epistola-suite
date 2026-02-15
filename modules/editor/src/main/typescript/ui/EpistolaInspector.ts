import { LitElement, html, nothing } from 'lit'
import { customElement, property } from 'lit/decorators.js'
import type { TemplateDocument, NodeId, Node, PageSettings } from '../types/index.js'
import type { EditorEngine } from '../engine/EditorEngine.js'
import type { ComponentDefinition, InspectorField } from '../engine/registry.js'
import type { StyleProperty } from '@epistola/template-model/generated/style-registry.js'
import type { BlockStylePreset } from '@epistola/template-model/generated/theme.js'
import { getNestedValue, setNestedValue } from '../engine/props.js'
import { isValidExpression } from '../engine/resolve-expression.js'
import { openExpressionDialog } from './expression-dialog.js'
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
} from './inputs/style-inputs.js'

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
      return this._renderDocumentInspector()
    }

    const node = this.doc.nodes[this.selectedNodeId]
    if (!node) return html`<div class="panel-error">Node not found</div>`

    const def = this.engine.registry.get(node.type)

    return html`
      <div class="epistola-inspector">
        <!-- Node info -->
        <div class="inspector-node-info">
          <div class="inspector-node-label">${def?.label ?? node.type}</div>
          <div class="inspector-node-id">${node.id}</div>
        </div>

        <!-- Columns layout editor -->
        ${node.type === 'columns'
          ? this._renderColumnsEditor(node)
          : nothing
        }

        <!-- Props -->
        ${def?.inspector && def.inspector.length > 0
          ? this._renderInspectorFields(node, def)
          : nothing
        }

        <!-- Style preset -->
        ${this._hasStyles(def?.applicableStyles)
          ? this._renderStylePresetSection(node)
          : nothing
        }

        <!-- Style properties -->
        ${this._hasStyles(def?.applicableStyles)
          ? this._renderNodeStyleGroups(node, def?.applicableStyles)
          : nothing
        }

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

  // -----------------------------------------------------------------------
  // Columns layout editor
  // -----------------------------------------------------------------------

  private _renderColumnsEditor(node: Node): unknown {
    if (!this.engine) return nothing

    const props = node.props ?? {}
    const columnSizes = (props.columnSizes as number[] | undefined) ?? []
    const gap = (props.gap as number | undefined) ?? 0
    const count = columnSizes.length

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Column Layout</div>

        <!-- Column count -->
        <div class="inspector-field">
          <label class="inspector-field-label">Columns</label>
          <div class="inspector-column-count">
            <button
              class="inspector-column-btn"
              ?disabled=${count <= 1}
              @click=${() => this._handleRemoveColumn(node.id)}
            >&minus;</button>
            <span class="inspector-column-count-value">${count}</span>
            <button
              class="inspector-column-btn"
              ?disabled=${count >= 6}
              @click=${() => this._handleAddColumn(node.id)}
            >+</button>
          </div>
        </div>

        <!-- Per-column sizes -->
        <div class="inspector-field">
          <label class="inspector-field-label">Column Sizes</label>
          <div class="inspector-column-sizes">
            ${columnSizes.map((size, i) => html`
              <div class="inspector-column-size">
                <span class="inspector-column-size-label">${i + 1}</span>
                <input
                  type="number"
                  class="ep-input inspector-column-size-input"
                  min="1"
                  .value=${String(size)}
                  @change=${(e: Event) => this._handleColumnSizeChange(node.id, i, Number((e.target as HTMLInputElement).value))}
                />
              </div>
            `)}
          </div>
        </div>

        <!-- Gap -->
        <div class="inspector-field">
          <label class="inspector-field-label">Gap (pt)</label>
          <input
            type="number"
            class="ep-input"
            min="0"
            .value=${String(gap)}
            @change=${(e: Event) => this._handlePropChange('gap', Number((e.target as HTMLInputElement).value))}
          />
        </div>
      </div>
    `
  }

  private _handleAddColumn(nodeId: NodeId): void {
    if (!this.engine) return
    this.engine.dispatch({
      type: 'AddColumnSlot',
      nodeId,
      size: 1,
    })
  }

  private _handleRemoveColumn(nodeId: NodeId): void {
    if (!this.engine) return
    this.engine.dispatch({
      type: 'RemoveColumnSlot',
      nodeId,
    })
  }

  private _handleColumnSizeChange(nodeId: NodeId, index: number, size: number): void {
    if (!this.engine || !this.doc) return

    const node = this.doc.nodes[nodeId]
    if (!node) return

    const props = node.props ?? {}
    const columnSizes = [...((props.columnSizes as number[] | undefined) ?? [])]
    columnSizes[index] = Math.max(1, size)

    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId,
      props: { ...props, columnSizes },
    })
  }

  // -----------------------------------------------------------------------
  // Document-level inspector (no node selected)
  // -----------------------------------------------------------------------

  private _renderDocumentInspector(): unknown {
    if (!this.engine) return nothing

    return html`
      <div class="epistola-inspector">
        <!-- Document styles (inheritable only) -->
        ${this._renderDocumentStyleGroups()}

        <!-- Page settings -->
        ${this._renderPageSettings()}
      </div>
    `
  }

  private _renderDocumentStyleGroups(): unknown {
    if (!this.engine) return nothing

    const groups = this.engine.styleRegistry.groups
    const docStyles = (this.doc?.documentStylesOverride ?? {}) as Record<string, unknown>

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Document Styles</div>
        ${groups.map(group => {
          // Only show inheritable properties for document styles
          const inheritableProps = group.properties.filter(p => p.inheritable)
          if (inheritableProps.length === 0) return nothing

          return html`
            <div class="inspector-style-group">
              <div class="inspector-style-group-label">${group.label}</div>
              ${inheritableProps.map(prop => this._renderStyleProperty(
                prop,
                docStyles[prop.key],
                (value) => this._handleDocStyleChange(prop.key, value),
              ))}
            </div>
          `
        })}
      </div>
    `
  }

  private _renderPageSettings(): unknown {
    if (!this.engine) return nothing

    const settings = this.engine.resolvedPageSettings

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Page Settings</div>

        <div class="inspector-field">
          <label class="inspector-field-label">Format</label>
          <select
            class="ep-select"
            @change=${(e: Event) => this._handlePageSettingChange('format', (e.target as HTMLSelectElement).value)}
          >
            ${['A4', 'Letter', 'Custom'].map(f => html`
              <option .value=${f} ?selected=${settings.format === f}>${f}</option>
            `)}
          </select>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label">Orientation</label>
          <select
            class="ep-select"
            @change=${(e: Event) => this._handlePageSettingChange('orientation', (e.target as HTMLSelectElement).value)}
          >
            ${['portrait', 'landscape'].map(o => html`
              <option .value=${o} ?selected=${settings.orientation === o}>${o[0].toUpperCase() + o.slice(1)}</option>
            `)}
          </select>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label">Margins (mm)</label>
          <div class="inspector-margins-grid">
            ${(['top', 'right', 'bottom', 'left'] as const).map(side => html`
              <div class="inspector-margin-field">
                <span class="style-spacing-label">${side[0].toUpperCase()}</span>
                <input
                  type="number"
                  class="ep-input style-spacing-number"
                  .value=${String(settings.margins[side])}
                  @change=${(e: Event) => this._handleMarginChange(side, Number((e.target as HTMLInputElement).value))}
                />
              </div>
            `)}
          </div>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label">Background Color</label>
          ${renderColorInput(
            settings.backgroundColor ?? '',
            (value) => this._handlePageSettingChange('backgroundColor', value),
          )}
        </div>
      </div>
    `
  }

  // -----------------------------------------------------------------------
  // Node style editing
  // -----------------------------------------------------------------------

  private _hasStyles(applicableStyles: 'all' | string[] | undefined): boolean {
    if (!applicableStyles) return false
    if (applicableStyles === 'all') return true
    return applicableStyles.length > 0
  }

  private _renderStylePresetSection(node: Node): unknown {
    if (!this.engine) return nothing

    const theme = this.engine.theme
    const presets = theme?.blockStylePresets

    // If there's a theme with presets, show a dropdown
    if (presets && Object.keys(presets).length > 0) {
      const applicablePresets = this._getApplicablePresets(presets, node.type)

      if (applicablePresets.length > 0) {
        return html`
          <div class="inspector-section">
            <label class="inspector-field-label">Style Preset</label>
            <select
              class="ep-select"
              @change=${(e: Event) => {
                const value = (e.target as HTMLSelectElement).value
                this._handleStylePreset(value || undefined)
              }}
            >
              <option value="" ?selected=${!node.stylePreset}>None</option>
              ${applicablePresets.map(([name, preset]) => html`
                <option .value=${name} ?selected=${node.stylePreset === name}>${preset.label}</option>
              `)}
            </select>
          </div>
        `
      }
    }

    // Fallback: text input for preset name (no theme or no applicable presets)
    return html`
      <div class="inspector-section">
        <label class="inspector-field-label">Style Preset</label>
        <input
          type="text"
          class="ep-input"
          .value=${node.stylePreset ?? ''}
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value
            this._handleStylePreset(value || undefined)
          }}
          placeholder="None"
        />
      </div>
    `
  }

  private _getApplicablePresets(
    presets: Record<string, BlockStylePreset>,
    nodeType: string,
  ): [string, BlockStylePreset][] {
    return Object.entries(presets).filter(([, preset]) => {
      if (!preset.applicableTo || preset.applicableTo.length === 0) return true
      return preset.applicableTo.includes(nodeType)
    })
  }

  private _renderNodeStyleGroups(node: Node, applicableStyles: 'all' | string[] | undefined): unknown {
    if (!this.engine) return nothing

    const groups = this.engine.styleRegistry.groups
    const inlineStyles = (node.styles ?? {}) as Record<string, unknown>

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Styles</div>
        ${groups.map(group => {
          const filteredProps = this._filterProperties(group.properties, applicableStyles)
          if (filteredProps.length === 0) return nothing

          return html`
            <div class="inspector-style-group">
              <div class="inspector-style-group-label">${group.label}</div>
              ${filteredProps.map(prop => this._renderStyleProperty(
                prop,
                inlineStyles[prop.key],
                (value) => this._handleNodeStyleChange(prop.key, value),
              ))}
            </div>
          `
        })}
      </div>
    `
  }

  private _filterProperties(properties: StyleProperty[], applicableStyles: 'all' | string[] | undefined): StyleProperty[] {
    if (!applicableStyles || applicableStyles === 'all') return properties
    if (applicableStyles.length === 0) return []
    return properties.filter(p => applicableStyles.includes(p.key))
  }

  private _renderStyleProperty(
    prop: StyleProperty,
    value: unknown,
    onChange: (value: unknown) => void,
  ): unknown {
    return html`
      <div class="inspector-field">
        <label class="inspector-field-label">${prop.label}</label>
        ${this._renderStyleInput(prop, value, onChange)}
      </div>
    `
  }

  private _renderStyleInput(
    prop: StyleProperty,
    value: unknown,
    onChange: (value: unknown) => void,
  ): unknown {
    switch (prop.type) {
      case 'select':
        return renderSelectInput(
          value,
          prop.options ?? [],
          (v) => onChange(v || undefined),
        )
      case 'unit':
        return renderUnitInput(
          value,
          prop.units ?? ['px'],
          (v) => onChange(v),
        )
      case 'color':
        return renderColorInput(
          value,
          (v) => onChange(v || undefined),
        )
      case 'spacing':
        return renderSpacingInput(
          value,
          prop.units ?? ['px'],
          (v) => onChange(v),
        )
      case 'number':
        return html`
          <input
            type="number"
            class="ep-input"
            .value=${String(value ?? '')}
            @change=${(e: Event) => onChange(Number((e.target as HTMLInputElement).value))}
          />
        `
      case 'text':
      default:
        return html`
          <input
            type="text"
            class="ep-input"
            .value=${String(value ?? '')}
            @change=${(e: Event) => onChange((e.target as HTMLInputElement).value || undefined)}
          />
        `
    }
  }

  // -----------------------------------------------------------------------
  // Props fields (existing)
  // -----------------------------------------------------------------------

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
      case 'expression': {
        const exprValue = String(value ?? '')
        const validClass = exprValue
          ? (isValidExpression(exprValue) ? 'valid' : 'invalid')
          : 'empty'

        return html`
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <button
              class="inspector-expression-trigger ${validClass}"
              @click=${() => this._openExpressionDialog(field.key, exprValue, node)}
            >
              ${exprValue
                ? html`<code class="inspector-expression-value">${exprValue.length > 40 ? exprValue.slice(0, 40) + '...' : exprValue}</code>`
                : html`<span class="inspector-expression-placeholder">Click to set expression...</span>`
              }
            </button>
          </div>
        `
      }
      default:
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label">${field.label}</label>
            <div class="inspector-unsupported">Unsupported field type: ${field.type}</div>
          </div>
        `
    }
  }

  // -----------------------------------------------------------------------
  // Event handlers
  // -----------------------------------------------------------------------

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

  private _handleStylePreset(value: string | undefined) {
    if (!this.engine || !this.selectedNodeId) return
    this.engine.dispatch({
      type: 'SetStylePreset',
      nodeId: this.selectedNodeId,
      stylePreset: value,
    })
  }

  private _handleNodeStyleChange(key: string, value: unknown) {
    if (!this.engine || !this.selectedNodeId) return

    const node = this.doc!.nodes[this.selectedNodeId]
    if (!node) return

    const newStyles = structuredClone(node.styles ?? {}) as Record<string, unknown>
    if (value === undefined || value === '') {
      delete newStyles[key]
    } else {
      newStyles[key] = value
    }

    this.engine.dispatch({
      type: 'UpdateNodeStyles',
      nodeId: this.selectedNodeId,
      styles: newStyles,
    })
  }

  private _handleDocStyleChange(key: string, value: unknown) {
    if (!this.engine || !this.doc) return

    const newStyles = structuredClone(this.doc.documentStylesOverride ?? {}) as Record<string, unknown>
    if (value === undefined || value === '') {
      delete newStyles[key]
    } else {
      newStyles[key] = value
    }

    this.engine.dispatch({
      type: 'UpdateDocumentStyles',
      styles: Object.keys(newStyles).length > 0 ? newStyles : undefined,
    })
  }

  private _handlePageSettingChange(key: string, value: unknown) {
    if (!this.engine || !this.doc) return

    const current = this.doc.pageSettingsOverride ?? {} as Partial<PageSettings>
    const newSettings = { ...current, [key]: value } as PageSettings

    this.engine.dispatch({
      type: 'UpdatePageSettings',
      settings: newSettings,
    })
  }

  private _handleMarginChange(side: 'top' | 'right' | 'bottom' | 'left', value: number) {
    if (!this.engine || !this.doc) return

    const currentSettings = this.engine.resolvedPageSettings
    const newMargins = { ...currentSettings.margins, [side]: value }
    const newSettings = {
      ...(this.doc.pageSettingsOverride ?? {}),
      margins: newMargins,
    } as PageSettings

    this.engine.dispatch({
      type: 'UpdatePageSettings',
      settings: newSettings,
    })
  }

  private _openExpressionDialog(key: string, currentValue: string, node: Node): void {
    if (!this.engine || !this.selectedNodeId) return

    // For loop expressions, highlight array-type fields
    const isLoopExpr = node.type === 'loop' && key === 'expression.raw'
    const placeholder = isLoopExpr
      ? 'e.g. items'
      : node.type === 'conditional'
        ? 'e.g. customer.active'
        : 'e.g. customer.name'

    openExpressionDialog({
      initialValue: currentValue,
      fieldPaths: this.engine.fieldPaths,
      getExampleData: () => this.engine?.getExampleData(),
      label: node.type === 'loop' ? 'Loop Expression' : 'Condition',
      placeholder,
      fieldPathFilter: isLoopExpr ? (fp) => fp.type === 'array' : undefined,
    }).then(({ value }) => {
      if (value !== null) {
        this._handlePropChange(key, value)
      }
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
