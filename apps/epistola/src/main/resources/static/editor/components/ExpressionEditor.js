import {
  extractPaths,
  parsePath,
  resolvePathType,
  getMethodsForType,
  formatTypeForDisplay,
  evaluateJsonataString,
} from '/headless-editor/headless-editor.js';

export class ExpressionEditor {
  constructor(options) {
    this.value = options.value || '';
    this.onChange = options.onChange || (() => {});
    this.onSave = options.onSave || (() => {});
    this.onCancel = options.onCancel || (() => {});
    this.testData = options.testData || {};
    this.scopeVariables = options.scopeVariables || [];
    this.evaluator = options.evaluator;

    this.container = null;
    this.input = null;
    this.dropdown = null;
    this.preview = null;

    this.debounceTimer = null;
    this.isOpen = false;
  }

  mount(container) {
    this.container = container;
    this.render();
    this.bindEvents();
  }

  render() {
    this.container.innerHTML = `
      <div class="expression-editor">
        <div class="expression-editor-input-wrapper">
          <input
            type="text"
            class="form-control expression-editor-input"
            value="${this.escapeHtml(this.value)}"
            placeholder="{{customer.name}}"
            autocomplete="off"
          >
          <div class="expression-editor-dropdown"></div>
        </div>
        <div class="expression-editor-preview"></div>
      </div>
    `;

    this.input = this.container.querySelector('.expression-editor-input');
    this.dropdown = this.container.querySelector('.expression-editor-dropdown');
    this.preview = this.container.querySelector('.expression-editor-preview');
  }

  bindEvents() {
    this.input.addEventListener('input', () => this.handleInput());
    this.input.addEventListener('keydown', (e) => this.handleKeydown(e));
    this.input.addEventListener('blur', () => this.handleBlur());
    this.input.addEventListener('focus', () => this.handleFocus());

    document.addEventListener('click', (e) => {
      if (!this.container.contains(e.target)) {
        this.closeDropdown();
      }
    });
  }

  handleInput() {
    this.value = this.input.value;
    this.onChange(this.value);
    this.updateDropdown();
    this.updatePreview();
  }

  handleKeydown(e) {
    if (!this.isOpen) return;

    const items = this.dropdown.querySelectorAll('.expression-suggestion-item');
    const active = this.dropdown.querySelector('.expression-suggestion-item.active');
    const activeIndex = active ? Array.from(items).indexOf(active) : -1;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      const nextIndex = activeIndex < items.length - 1 ? activeIndex + 1 : 0;
      this.highlightItem(items[nextIndex]);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const prevIndex = activeIndex > 0 ? activeIndex - 1 : items.length - 1;
      this.highlightItem(items[prevIndex]);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (active) {
        this.applySuggestion(active.dataset.value);
      } else {
        this.onSave(this.value);
      }
    } else if (e.key === 'Escape') {
      this.closeDropdown();
      this.onCancel();
    } else if (e.key === 'Tab') {
      if (active) {
        e.preventDefault();
        this.applySuggestion(active.dataset.value);
      }
    }
  }

  handleBlur() {
    if (this.dropdown.contains(document.activeElement)) {
      return;
    }
    setTimeout(() => this.closeDropdown(), 150);
  }

  handleFocus() {
    this.updateDropdown();
    this.updatePreview();
  }

  highlightItem(item) {
    this.dropdown.querySelectorAll('.expression-suggestion-item').forEach(el => el.classList.remove('active'));
    if (item) {
      item.classList.add('active');
      item.scrollIntoView({ block: 'nearest' });
    }
  }

  applySuggestion(value) {
    const beforeCursor = this.getTextBeforeCursor();
    const afterCursor = this.getTextAfterCursor();

    const lastDot = beforeCursor.lastIndexOf('.');
    const lastBracket = beforeCursor.lastIndexOf('[');

    let insertPoint;
    if (lastDot > lastBracket) {
      insertPoint = beforeCursor.lastIndexOf('.');
    } else if (lastBracket >= 0) {
      insertPoint = beforeCursor.lastIndexOf('[', lastDot);
    } else {
      insertPoint = 0;
    }

    const newBeforeCursor = beforeCursor.slice(0, insertPoint) + value;
    this.input.value = newBeforeCursor + afterCursor;

    const cursorPos = newBeforeCursor.length;
    this.input.setSelectionRange(cursorPos, cursorPos);
    this.input.focus();

    this.value = this.input.value;
    this.onChange(this.value);
    this.closeDropdown();
    this.updatePreview();
  }

  getTextBeforeCursor() {
    const pos = this.input.selectionStart;
    return this.input.value.slice(0, pos);
  }

  getTextAfterCursor() {
    const pos = this.input.selectionEnd;
    return this.input.value.slice(pos);
  }

  updateDropdown() {
    const text = this.getTextBeforeCursor();
    const { path, partial } = this.parsePartialExpression(text);

    let suggestions = [];

    if (path.length === 0) {
      suggestions = this.getTopLevelCompletions(partial);
    } else {
      suggestions = this.getPathCompletions(path, partial);
    }

    if (suggestions.length === 0) {
      this.closeDropdown();
      return;
    }

    this.renderDropdown(suggestions);
  }

  parsePartialExpression(text) {
    const trimmed = text.trim();
    if (!trimmed) {
      return { path: [], partial: '' };
    }

    if (trimmed.endsWith('.')) {
      return { path: parsePath(trimmed.slice(0, -1)), partial: '' };
    }

    const segments = parsePath(trimmed);
    if (segments.length === 0) {
      return { path: [], partial: '' };
    }

    const partial = segments[segments.length - 1];
    const path = segments.slice(0, -1);

    return { path, partial };
  }

  getTopLevelCompletions(filter) {
    const completions = [];

    for (const scopeVar of this.scopeVariables) {
      if (!filter || scopeVar.name.toLowerCase().startsWith(filter.toLowerCase())) {
        completions.push({
          label: scopeVar.name,
          type: 'variable',
          detail: scopeVar.type === 'loop-index' ? 'loop index' : `loop item from ${scopeVar.arrayPath}`,
          value: scopeVar.name,
          priority: 10,
        });
      }
    }

    const paths = extractPaths(this.testData);
    for (const p of paths) {
      if (!filter || p.path.toLowerCase().startsWith(filter.toLowerCase())) {
        completions.push({
          label: p.path,
          type: p.isArray ? 'array' : 'property',
          detail: p.type,
          value: p.path,
          priority: 5,
        });
      }
    }

    return completions.sort((a, b) => b.priority - a.priority);
  }

  getPathCompletions(path, filter) {
    const type = resolvePathType(path, this.testData, this.scopeVariables);

    if (type.kind === 'unknown' && !filter) {
      return [];
    }

    const suggestions = [];

    const methods = getMethodsForType(type);
    for (const method of methods) {
      if (!filter || method.label.toLowerCase().startsWith(filter.toLowerCase())) {
        const applyValue = method.type === 'method' ? `${method.label}()` : method.label;
        suggestions.push({
          label: method.label,
          type: method.type,
          detail: method.detail,
          value: applyValue,
          priority: method.type === 'property' ? 8 : 5,
        });
      }
    }

    if (type.kind === 'object') {
      for (const [key, propType] of Object.entries(type.properties)) {
        if (!filter || key.toLowerCase().startsWith(filter.toLowerCase())) {
          suggestions.push({
            label: key,
            type: 'property',
            detail: formatTypeForDisplay(propType),
            value: key,
            priority: 10,
          });
        }
      }
    }

    if (type.kind === 'array') {
      suggestions.push({
        label: '[0]',
        type: 'property',
        detail: `access ${formatTypeForDisplay(type.elementType)}`,
        value: '[0]',
        priority: 15,
      });
    }

    return suggestions;
  }

  renderDropdown(suggestions) {
    this.dropdown.innerHTML = suggestions.map(s => `
      <div class="expression-suggestion-item" data-value="${this.escapeHtml(s.value)}">
        <span class="expression-suggestion-label">${this.escapeHtml(s.label)}</span>
        <span class="expression-suggestion-detail">${this.escapeHtml(s.detail)}</span>
      </div>
    `).join('');

    this.dropdown.querySelectorAll('.expression-suggestion-item').forEach(item => {
      item.addEventListener('click', () => {
        this.applySuggestion(item.dataset.value);
      });
      item.addEventListener('mouseenter', () => {
        this.highlightItem(item);
      });
    });

    this.isOpen = true;
    this.dropdown.style.display = 'block';
  }

  closeDropdown() {
    this.isOpen = false;
    this.dropdown.style.display = 'none';
  }

  async updatePreview() {
    const trimmed = this.value.trim();
    if (!trimmed) {
      this.preview.innerHTML = '<span class="text-muted small">Enter an expression</span>';
      return;
    }

    this.preview.innerHTML = '<span class="text-muted small">Evaluating...</span>';

    try {
      const result = await evaluateJsonataString(trimmed, { ...this.testData });
      const formatted = this.formatPreviewValue(result);
      this.preview.innerHTML = `
        <span class="expression-preview-success">
          <span class="expression-preview-label">Preview:</span>
          <code class="expression-preview-value">${this.escapeHtml(formatted)}</code>
        </span>
      `;
    } catch (error) {
      this.preview.innerHTML = `
        <span class="expression-preview-error">
          <span class="expression-preview-label">Error:</span>
          <code class="expression-preview-value">${this.escapeHtml(error.message || String(error))}</code>
        </span>
      `;
    }
  }

  formatPreviewValue(value) {
    if (value === undefined) return 'undefined';
    if (value === null) return 'null';
    if (typeof value === 'object') {
      const json = JSON.stringify(value);
      return json.length > 50 ? json.slice(0, 50) + '...' : json;
    }
    return String(value);
  }

  escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  setTestData(data) {
    this.testData = data || {};
  }

  setScopeVariables(variables) {
    this.scopeVariables = variables || [];
  }

  getValue() {
    return this.value;
  }

  setValue(value) {
    const newValue = value || '';
    if (newValue === this.value) return;
    this.value = newValue;
    if (this.input) {
      this.input.value = this.value;
    }
  }

  focus() {
    if (this.input) {
      this.input.focus();
    }
  }

  async refreshPreview() {
    if (!this.preview) return;
    const trimmed = this.value.trim();
    if (!trimmed) {
      this.preview.innerHTML = '<span class="text-muted small">Enter an expression</span>';
      return;
    }

    this.preview.innerHTML = '<span class="text-muted small">Evaluating...</span>';

    try {
      const result = await evaluateJsonataString(trimmed, { ...this.testData });
      const formatted = this.formatPreviewValue(result);
      this.preview.innerHTML = `
        <span class="expression-preview-success">
          <span class="expression-preview-label">Preview:</span>
          <code class="expression-preview-value">${this.escapeHtml(formatted)}</code>
        </span>
      `;
    } catch (error) {
      this.preview.innerHTML = `
        <span class="expression-preview-error">
          <span class="expression-preview-label">Error:</span>
          <code class="expression-preview-value">${this.escapeHtml(error.message || String(error))}</code>
        </span>
      `;
    }
  }

  destroy() {
    if (this.container) {
      this.container.innerHTML = '';
    }
    this.container = null;
    this.input = null;
    this.dropdown = null;
    this.preview = null;
  }
}
