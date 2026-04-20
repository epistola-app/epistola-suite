import { LitElement, html, nothing } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import type { TemplateDocument, NodeId, Node, PageSettings } from '../types/index.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import type { ComponentDefinition, InspectorField, ScopeDeclaration } from '../engine/registry.js';
import type { StyleProperty } from '@epistola.app/epistola-model/generated/style-registry';
import type { BlockStylePreset } from '@epistola.app/epistola-model/generated/theme';
import { getNestedValue, setNestedValue } from '../engine/props.js';
import {
  isValidExpression,
  validateArrayResult,
  validateBooleanResult,
} from '../engine/resolve-expression.js';
import { openExpressionDialog } from './expression-dialog.js';
import {
  renderUnitInput,
  renderColorInput,
  renderSpacingInput,
  renderSelectInput,
  COMPOUND_STYLE_TYPES,
  type BorderValue,
} from './inputs/style-inputs.js';
import './inputs/BorderInput.js';

@customElement('epistola-inspector')
export class EpistolaInspector extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property({ attribute: false }) doc?: TemplateDocument;
  @property({ attribute: false }) selectedNodeId: NodeId | null = null;

  override render() {
    if (!this.engine || !this.doc) {
      return html`<div class="panel-empty">No document</div>`;
    }

    if (!this.selectedNodeId) {
      return this._renderDocumentInspector();
    }

    const node = this.doc.nodes[this.selectedNodeId];
    if (!node) return html`<div class="panel-error">Node not found</div>`;

    const def = this.engine.registry.get(node.type);

    return html`
      <div class="epistola-inspector">
        <!-- Node info -->
        <div class="inspector-node-info">
          <div class="inspector-node-label">${def?.label ?? node.type}</div>
          <div class="inspector-node-id">${node.id}</div>
        </div>

        <!-- Component-specific inspector (columns, table, etc.) -->
        ${def?.renderInspector ? def.renderInspector({ node, engine: this.engine! }) : nothing}

        <!-- Props -->
        ${def?.inspector && def.inspector.length > 0
          ? this._renderInspectorFields(node, def)
          : nothing}

        <!-- Style preset -->
        ${this._hasStyles(def?.applicableStyles) ? this._renderStylePresetSection(node) : nothing}

        <!-- Style properties -->
        ${this._hasStyles(def?.applicableStyles)
          ? this._renderNodeStyleGroups(node, def?.applicableStyles)
          : nothing}

        <!-- Delete -->
        <div class="inspector-delete-section">
          <button class="ep-btn-danger" @click=${this._handleDelete}>Delete Block</button>
        </div>
      </div>
    `;
  }

  // -----------------------------------------------------------------------
  // Document-level inspector (no node selected)
  // -----------------------------------------------------------------------

  private _renderDocumentInspector(): unknown {
    if (!this.engine) return nothing;

    return html`
      <div class="epistola-inspector">
        <!-- Document styles (inheritable only) -->
        ${this._renderDocumentStyleGroups()}

        <!-- Page settings -->
        ${this._renderPageSettings()}
      </div>
    `;
  }

  private _renderDocumentStyleGroups(): unknown {
    if (!this.engine) return nothing;

    const groups = this.engine.styleRegistry.groups;
    const docStyles = (this.doc?.documentStylesOverride ?? {}) as Record<string, unknown>;

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Document Styles</div>
        ${groups.map((group) => {
          // Only show inheritable properties for document styles
          const inheritableProps = group.properties.filter((p) => p.inheritable);
          if (inheritableProps.length === 0) return nothing;

          return html`
            <div class="inspector-style-group">
              <div class="inspector-style-group-label">${group.label}</div>
              ${inheritableProps.map((prop) =>
                this._renderStyleProperty(prop, docStyles[prop.key], (value) =>
                  this._handleDocStyleChange(prop.key, value),
                ),
              )}
            </div>
          `;
        })}
      </div>
    `;
  }

  private _renderPageSettings(): unknown {
    if (!this.engine) return nothing;

    const settings = this.engine.resolvedPageSettings;

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Page Settings</div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="page-settings-format">Format</label>
          <select
            id="page-settings-format"
            class="ep-select"
            @change=${(e: Event) =>
              this._handlePageSettingChange('format', (e.target as HTMLSelectElement).value)}
          >
            ${['A4', 'Letter', 'Custom'].map(
              (f) => html` <option .value=${f} ?selected=${settings.format === f}>${f}</option> `,
            )}
          </select>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="page-settings-orientation">Orientation</label>
          <select
            id="page-settings-orientation"
            class="ep-select"
            @change=${(e: Event) =>
              this._handlePageSettingChange('orientation', (e.target as HTMLSelectElement).value)}
          >
            ${['portrait', 'landscape'].map(
              (o) => html`
                <option .value=${o} ?selected=${settings.orientation === o}>
                  ${o[0].toUpperCase() + o.slice(1)}
                </option>
              `,
            )}
          </select>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="page-settings-margin-top">Margins (mm)</label>
          <div class="inspector-margins-grid">
            ${(['top', 'right', 'bottom', 'left'] as const).map(
              (side) => html`
                <div class="inspector-margin-field">
                  <span class="style-spacing-label">${side[0].toUpperCase()}</span>
                  <input
                    type="number"
                    id=${`page-settings-margin-${side}`}
                    class="ep-input style-spacing-number"
                    .value=${String(settings.margins[side])}
                    @change=${(e: Event) =>
                      this._handleMarginChange(side, Number((e.target as HTMLInputElement).value))}
                  />
                </div>
              `,
            )}
          </div>
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="page-settings-background-color">
            Background Color
          </label>
          ${renderColorInput(
            settings.backgroundColor ?? '',
            (value) => this._handlePageSettingChange('backgroundColor', value),
            'page-settings-background-color',
          )}
        </div>
      </div>
    `;
  }

  // -----------------------------------------------------------------------
  // Node style editing
  // -----------------------------------------------------------------------

  private _hasStyles(applicableStyles: 'all' | string[] | undefined): boolean {
    if (!applicableStyles) return false;
    if (applicableStyles === 'all') return true;
    return applicableStyles.length > 0;
  }

  private _renderStylePresetSection(node: Node): unknown {
    if (!this.engine) return nothing;

    const theme = this.engine.theme;
    const presets = theme?.blockStylePresets;

    // If there's a theme with presets, show a dropdown
    if (presets && Object.keys(presets).length > 0) {
      const applicablePresets = this._getApplicablePresets(presets, node.type);

      if (applicablePresets.length > 0) {
        return html`
          <div class="inspector-section">
            <label class="inspector-field-label" for="style-preset-select">Style Preset</label>
            <select
              id="style-preset-select"
              class="ep-select"
              @change=${(e: Event) => {
                const value = (e.target as HTMLSelectElement).value;
                this._handleStylePreset(value || undefined);
              }}
            >
              <option value="" ?selected=${!node.stylePreset}>None</option>
              ${applicablePresets.map(
                ([name, preset]) => html`
                  <option .value=${name} ?selected=${node.stylePreset === name}>
                    ${preset.label}
                  </option>
                `,
              )}
            </select>
          </div>
        `;
      }
    }

    // Fallback: text input for preset name (no theme or no applicable presets)
    return html`
      <div class="inspector-section">
        <label class="inspector-field-label" for="style-preset-input">Style Preset</label>
        <input
          type="text"
          id="style-preset-input"
          class="ep-input"
          .value=${node.stylePreset ?? ''}
          @change=${(e: Event) => {
            const value = (e.target as HTMLInputElement).value;
            this._handleStylePreset(value || undefined);
          }}
          placeholder="None"
        />
      </div>
    `;
  }

  private _getApplicablePresets(
    presets: Record<string, BlockStylePreset>,
    nodeType: string,
  ): [string, BlockStylePreset][] {
    return Object.entries(presets).filter(([, preset]) => {
      if (!preset.applicableTo || preset.applicableTo.length === 0) return true;
      return preset.applicableTo.includes(nodeType);
    });
  }

  private _renderNodeStyleGroups(
    node: Node,
    applicableStyles: 'all' | string[] | undefined,
  ): unknown {
    if (!this.engine) return nothing;

    const groups = this.engine.styleRegistry.groups;
    const inlineStyles = (node.styles ?? {}) as Record<string, unknown>;

    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Styles</div>
        ${groups.map((group) => {
          const filteredProps = this._filterProperties(group.properties, applicableStyles);
          if (filteredProps.length === 0) return nothing;

          return html`
            <div class="inspector-style-group">
              <div class="inspector-style-group-label">${group.label}</div>
              ${filteredProps.map((prop) => {
                const compound = COMPOUND_STYLE_TYPES[prop.type];
                const value = compound
                  ? compound.read(prop.key, inlineStyles)
                  : inlineStyles[prop.key];
                return this._renderStyleProperty(prop, value, (v) =>
                  this._handleNodeStyleChange(prop.key, v, prop.type),
                );
              })}
            </div>
          `;
        })}
      </div>
    `;
  }

  private _filterProperties(
    properties: StyleProperty[],
    applicableStyles: 'all' | string[] | undefined,
  ): StyleProperty[] {
    if (!applicableStyles || applicableStyles === 'all') return properties;
    if (applicableStyles.length === 0) return [];
    return properties.filter((p) => applicableStyles.includes(p.key));
  }

  private _renderStyleProperty(
    prop: StyleProperty,
    value: unknown,
    onChange: (value: unknown) => void,
  ): unknown {
    const inputId = `inspector-style-${prop.key}`;
    return html`
      <div class="inspector-field">
        ${prop.type !== 'boolean'
          ? html`<label class="inspector-field-label" for=${inputId}>${prop.label}</label>`
          : nothing}
        ${this._renderStyleInput(prop, value, onChange, inputId)}
      </div>
    `;
  }

  private _renderStyleInput(
    prop: StyleProperty,
    value: unknown,
    onChange: (value: unknown) => void,
    inputId: string,
  ): unknown {
    switch (prop.type) {
      case 'select':
        return renderSelectInput(
          value,
          prop.options ?? [],
          (v) => onChange(v || undefined),
          inputId,
        );
      case 'unit':
        return renderUnitInput(value, prop.units ?? ['px'], (v) => onChange(v), undefined, inputId);
      case 'color':
        return renderColorInput(value, (v) => onChange(v || undefined), inputId);
      case 'spacing':
        return renderSpacingInput(
          value,
          prop.units ?? ['px'],
          (v) => onChange(v),
          undefined,
          inputId,
        );
      case 'border':
        return html`
          <epistola-border-input
            .value=${value as BorderValue | undefined}
            .units=${prop.units ?? ['pt', 'sp']}
            @change=${(e: CustomEvent) => onChange(e.detail)}
          ></epistola-border-input>
        `;
      case 'number':
        return html`
          <input
            type="number"
            class="ep-input"
            id=${inputId}
            .value=${String(value ?? '')}
            @change=${(e: Event) => onChange(Number((e.target as HTMLInputElement).value))}
          />
        `;
      case 'boolean':
        return html`
          <label class="style-boolean-input">
            <input
              type="checkbox"
              id=${inputId}
              .checked=${value === true || value === 'true'}
              @change=${(e: Event) => {
                const checked = (e.target as HTMLInputElement).checked;
                onChange(checked || undefined);
              }}
            />
            ${prop.label}
          </label>
        `;
      case 'text':
      default:
        return html`
          <input
            type="text"
            class="ep-input"
            id=${inputId}
            .value=${String(value ?? '')}
            @change=${(e: Event) => onChange((e.target as HTMLInputElement).value || undefined)}
          />
        `;
    }
  }

  // -----------------------------------------------------------------------
  // Props fields (existing)
  // -----------------------------------------------------------------------

  private _renderInspectorFields(node: Node, def: ComponentDefinition): unknown {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Properties</div>
        ${def.inspector.map((field) => this._renderField(node, field))}
      </div>
    `;
  }

  private _renderField(node: Node, field: InspectorField): unknown {
    const props = node.props ?? {};
    const value = getNestedValue(props, field.key);
    const fieldId = `inspector-prop-${field.key}`;

    switch (field.type) {
      case 'text':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            <input
              type="text"
              class="ep-input"
              id=${fieldId}
              .value=${String(value ?? '')}
              @change=${(e: Event) =>
                this._handlePropChange(field.key, (e.target as HTMLInputElement).value)}
            />
          </div>
        `;
      case 'number':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            <input
              type="number"
              class="ep-input"
              id=${fieldId}
              .value=${String(value ?? 0)}
              @change=${(e: Event) =>
                this._handlePropChange(field.key, Number((e.target as HTMLInputElement).value))}
            />
          </div>
        `;
      case 'boolean':
        return html`
          <div class="inspector-checkbox-field">
            <input
              type="checkbox"
              class="ep-checkbox"
              id=${fieldId}
              .checked=${Boolean(value)}
              @change=${(e: Event) =>
                this._handlePropChange(field.key, (e.target as HTMLInputElement).checked)}
            />
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
          </div>
        `;
      case 'unit':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            ${renderUnitInput(
              value,
              field.units ?? ['pt'],
              (v) => this._handlePropChange(field.key, v),
              undefined,
              fieldId,
            )}
          </div>
        `;
      case 'select':
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            <select
              id=${fieldId}
              class="ep-select"
              @change=${(e: Event) =>
                this._handlePropChange(field.key, (e.target as HTMLSelectElement).value)}
            >
              ${(field.options ?? []).map(
                (opt) => html`
                  <option .value=${String(opt.value)} ?selected=${value === opt.value}>
                    ${opt.label}
                  </option>
                `,
              )}
            </select>
          </div>
        `;
      case 'expression': {
        const exprValue = String(value ?? '');
        const validClass = exprValue
          ? isValidExpression(exprValue)
            ? 'valid'
            : 'invalid'
          : 'empty';

        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            <button
              id=${fieldId}
              class="inspector-expression-trigger ${validClass}"
              @click=${() => this._openExpressionDialog(field.key, exprValue, node)}
            >
              ${exprValue
                ? html`<code class="inspector-expression-value"
                    >${exprValue.length > 40 ? exprValue.slice(0, 40) + '...' : exprValue}</code
                  >`
                : html`<span class="inspector-expression-placeholder"
                    >Click to set expression...</span
                  >`}
            </button>
          </div>
        `;
      }
      default:
        return html`
          <div class="inspector-field">
            <label class="inspector-field-label" for=${fieldId}>${field.label}</label>
            <div class="inspector-unsupported">Unsupported field type: ${field.type}</div>
          </div>
        `;
    }
  }

  // -----------------------------------------------------------------------
  // Event handlers
  // -----------------------------------------------------------------------

  private _handlePropChange(key: string, value: unknown) {
    if (!this.engine || !this.selectedNodeId) return;

    const node = this.doc!.nodes[this.selectedNodeId];
    if (!node) return;

    let newProps = structuredClone(node.props ?? {});
    setNestedValue(newProps, key, value);

    const def = this.engine.registry.get(node.type);
    if (def?.onPropChange) {
      newProps = def.onPropChange(key, value, newProps);
    }

    // Validate scope conflicts if this component provides scoped variables
    if (def?.scopeProvider) {
      const tempNode = { ...node, props: newProps };
      const proposed = def.scopeProvider(tempNode, { schemaFieldPaths: this.engine.fieldPaths });
      if (proposed) {
        const conflict = this._findScopeConflict(node, def, proposed);
        if (conflict) {
          // TODO: show inline validation error in the inspector
          return;
        }
      }
    }

    // Detect alias rename for expression auto-rewrite
    const oldAlias = (node.props ?? {})[key];
    const metadata =
      key === 'itemAlias' &&
      typeof oldAlias === 'string' &&
      typeof value === 'string' &&
      oldAlias !== value
        ? { aliasRename: { oldAlias, newAlias: value } }
        : undefined;

    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.selectedNodeId,
      props: newProps,
      metadata,
    });
  }

  /**
   * Check if proposed scope variables conflict with any existing variable in scope.
   * Returns the conflicting variable path, or null if no conflict.
   */
  private _findScopeConflict(
    node: Node,
    def: ComponentDefinition,
    proposed: ScopeDeclaration,
  ): string | null {
    if (!this.engine || !this.selectedNodeId) return null;

    // Get all visible variables at this position
    const existing = this.engine.getAvailableVariablesAt(this.selectedNodeId);

    // Exclude the current node's own scope (it's being replaced)
    const currentScope = def.scopeProvider?.(node, { schemaFieldPaths: this.engine.fieldPaths });
    const currentScopePaths = new Set(currentScope?.variables.map((v) => v.path) ?? []);
    const existingWithoutSelf = existing.filter((f) => !currentScopePaths.has(f.path));

    // Check for any proposed variable that collides with an existing one
    const conflict = proposed.variables.find((v) =>
      existingWithoutSelf.some((e) => e.path === v.path),
    );
    return conflict?.path ?? null;
  }

  private _handleStylePreset(value: string | undefined) {
    if (!this.engine || !this.selectedNodeId) return;
    this.engine.dispatch({
      type: 'SetStylePreset',
      nodeId: this.selectedNodeId,
      stylePreset: value,
    });
  }

  private _handleNodeStyleChange(key: string, value: unknown, type?: string) {
    if (!this.engine || !this.selectedNodeId) return;

    const node = this.doc!.nodes[this.selectedNodeId];
    if (!node) return;

    const newStyles = structuredClone(node.styles ?? {}) as Record<string, unknown>;

    const compound = type ? COMPOUND_STYLE_TYPES[type] : undefined;
    if (compound && value != null && typeof value === 'object') {
      compound.write(key, value, newStyles);
    } else if (value === undefined || value === '') {
      delete newStyles[key];
    } else {
      newStyles[key] = value;
    }

    this.engine.dispatch({
      type: 'UpdateNodeStyles',
      nodeId: this.selectedNodeId,
      styles: newStyles,
    });
  }

  private _handleDocStyleChange(key: string, value: unknown) {
    if (!this.engine || !this.doc) return;

    const newStyles = structuredClone(this.doc.documentStylesOverride ?? {}) as Record<
      string,
      unknown
    >;

    if (value === undefined || value === '') {
      delete newStyles[key];
    } else {
      newStyles[key] = value;
    }

    this.engine.dispatch({
      type: 'UpdateDocumentStyles',
      styles: Object.keys(newStyles).length > 0 ? newStyles : undefined,
    });
  }

  private _handlePageSettingChange(key: string, value: unknown) {
    if (!this.engine || !this.doc) return;

    const current = this.doc.pageSettingsOverride ?? ({} as Partial<PageSettings>);
    const newSettings = { ...current, [key]: value } as PageSettings;

    this.engine.dispatch({
      type: 'UpdatePageSettings',
      settings: newSettings,
    });
  }

  private _handleMarginChange(side: 'top' | 'right' | 'bottom' | 'left', value: number) {
    if (!this.engine || !this.doc) return;

    const currentSettings = this.engine.resolvedPageSettings;
    const newMargins = { ...currentSettings.margins, [side]: value };
    const newSettings = {
      ...(this.doc.pageSettingsOverride ?? {}),
      margins: newMargins,
    } as PageSettings;

    this.engine.dispatch({
      type: 'UpdatePageSettings',
      settings: newSettings,
    });
  }

  private _openExpressionDialog(key: string, currentValue: string, node: Node): void {
    if (!this.engine || !this.selectedNodeId) return;

    // For loop/datatable expressions, highlight array-type fields
    const isLoopExpr =
      (node.type === 'loop' || node.type === 'datatable' || node.type === 'datalist') &&
      key === 'expression.raw';
    const isConditionalExpr = node.type === 'conditional' && key === 'condition.raw';
    const placeholder = isLoopExpr
      ? 'e.g. items'
      : isConditionalExpr
        ? 'e.g. customer.active'
        : 'e.g. customer.name';

    const resultValidator = isLoopExpr
      ? validateArrayResult
      : isConditionalExpr
        ? validateBooleanResult
        : undefined;

    openExpressionDialog({
      initialValue: currentValue,
      fieldPaths: this.engine.getAvailableVariablesAt(this.selectedNodeId!),
      getExampleData: () => this.engine?.getEvaluationContextAt(this.selectedNodeId!) ?? {},
      label: isLoopExpr ? 'Loop Expression' : isConditionalExpr ? 'Condition' : 'Expression',
      placeholder,
      fieldPathFilter: isLoopExpr ? (fp) => fp.type === 'array' : undefined,
      resultValidator,
    }).then(({ value }) => {
      if (value !== null) {
        this._handlePropChange(key, value);
      }
    });
  }

  private _handleDelete() {
    if (!this.engine || !this.selectedNodeId) return;
    const nextSelection = this.engine.getNextSelectionAfterRemove(this.selectedNodeId);
    const result = this.engine.dispatch({
      type: 'RemoveNode',
      nodeId: this.selectedNodeId,
    });
    if (result.ok) {
      this.engine.selectNode(nextSelection);
    }
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-inspector': EpistolaInspector;
  }
}
