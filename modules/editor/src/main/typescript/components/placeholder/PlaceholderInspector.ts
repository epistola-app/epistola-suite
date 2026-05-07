/**
 * PlaceholderInspector — name + description editor for a `placeholder` node.
 *
 * Names must be kebab-case slugs (`^[a-z][a-z0-9-]{0,63}$`) and unique within
 * the document — both validated live.
 *
 * Renders read-only in template-fill mode (placeholder is inside a published
 * stencil): the placeholder's identity is the stencil author's contract, so
 * editing it from a template would silently break the name-keyed link to the
 * stencil's default and to upgrade preservation. The CSS-level pointer-events
 * lock prevents *canvas* selection of the placeholder; this read-only view
 * covers keyboard / programmatic / tree-view selections that bypass CSS.
 */

import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { Node } from '../../types/index.js';
import type { EditorEngine } from '../../engine/EditorEngine.js';
import { placeholderContext } from '../stencil/ancestry.js';
import { isPlaceholder } from './node-types.js';

const SLUG_RE = /^[a-z][a-z0-9-]{0,63}$/;

@customElement('placeholder-inspector')
export class PlaceholderInspector extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) node!: Node;
  @property({ attribute: false }) engine!: EditorEngine;

  @state() private _nameError: string | null = null;

  private get _name(): string {
    return isPlaceholder(this.node) ? this.node.props.name : '';
  }

  private get _description(): string {
    return isPlaceholder(this.node) ? (this.node.props.description ?? '') : '';
  }

  override render() {
    const context = placeholderContext(this.engine.doc, this.node.id, this.engine.indexes);
    if (context === 'template-fill') {
      return this._renderReadOnly();
    }
    return this._renderEditable();
  }

  private _renderReadOnly() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Placeholder (from stencil)</div>
        <div class="inspector-field">
          <div class="inspector-field-label">Name</div>
          <code class="placeholder-inspector-readonly-name">${this._name || '(unnamed)'}</code>
        </div>
        ${this._description
          ? html`<div class="inspector-field">
              <div class="inspector-field-label">Description</div>
              <div class="placeholder-inspector-readonly-description">${this._description}</div>
            </div>`
          : nothing}
        <div
          class="inspector-field-hint"
          style="font-size: var(--ep-font-size-xs); color: var(--ep-color-text-muted); margin-top: var(--ep-space-2);"
        >
          Managed by the stencil definition. Edit the stencil to change.
        </div>
      </div>
    `;
  }

  private _renderEditable() {
    return html`
      <div class="inspector-section">
        <div class="inspector-section-label">Placeholder</div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="placeholder-name">Name</label>
          <input
            id="placeholder-name"
            class="ep-input"
            type="text"
            .value=${this._name}
            placeholder="kebab-case-slug"
            @input=${this._onNameInput}
            style=${this._nameError ? 'border-color: var(--ep-color-destructive, #dc2626);' : ''}
          />
          ${this._nameError
            ? html`<div
                class="inspector-field-error"
                style="color: var(--ep-color-destructive, #dc2626); font-size: var(--ep-font-size-xs); margin-top: var(--ep-space-1);"
              >
                ${this._nameError}
              </div>`
            : ''}
        </div>

        <div class="inspector-field">
          <label class="inspector-field-label" for="placeholder-description">Description</label>
          <input
            id="placeholder-description"
            class="ep-input"
            type="text"
            .value=${this._description}
            placeholder="optional"
            @input=${this._onDescriptionInput}
          />
        </div>
      </div>
    `;
  }

  private _onNameInput = (e: InputEvent) => {
    const value = (e.target as HTMLInputElement).value;
    this._nameError = this._validateName(value);
    if (this._nameError) return;
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: { ...(this.node.props ?? {}), name: value },
    });
  };

  private _onDescriptionInput = (e: InputEvent) => {
    const value = (e.target as HTMLInputElement).value;
    this.engine.dispatch({
      type: 'UpdateNodeProps',
      nodeId: this.node.id,
      props: { ...(this.node.props ?? {}), description: value },
    });
  };

  /** Returns an error message if the name is invalid, otherwise null. */
  private _validateName(value: string): string | null {
    if (!value) return 'Name is required';
    if (!SLUG_RE.test(value)) return 'Use kebab-case: lowercase letters, digits, and hyphens';
    const duplicates = Object.values(this.engine.doc.nodes).filter(
      (n) => n.id !== this.node.id && isPlaceholder(n) && n.props.name === value,
    );
    if (duplicates.length > 0) return `Name '${value}' is already used in this document`;
    return null;
  }
}
