import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { EditorEngine } from '../engine/EditorEngine.js';
import type { SaveState } from './save-service.js';
import type { ToolbarAction } from '../plugins/types.js';
import { icon } from './icons.js';
import { buildShortcutGroupsProjection, type ShortcutGroup } from './shortcuts.js';
import { normalizeShortcutEvent } from '../shortcuts/resolver.js';
import {
  EDITOR_SHORTCUT_COMMAND_IDS,
  getShortcutDisplayForCommandId,
} from '../shortcuts/editor-runtime.js';

function toTooltipShortcutLabel(helpKeys: string): string {
  return helpKeys.replaceAll('{cmd}', 'Ctrl/Cmd');
}

const UNDO_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.undo);
const REDO_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.redo);
const SAVE_SHORTCUT_HELP = getShortcutDisplayForCommandId(EDITOR_SHORTCUT_COMMAND_IDS.save);

const SHORTCUT_ACTIVE_FEEDBACK_MS = 650;
const SHORTCUTS_TRIGGER_SELECTOR = '.toolbar-shortcuts-trigger';
const SHORTCUTS_SEARCH_SELECTOR = '.toolbar-shortcuts-search-input';
const SHORTCUTS_POPOVER_ID = 'epistola-toolbar-shortcuts-popover';
const DATA_PREVIEW_TRIGGER_SELECTOR = '.toolbar-data-preview-trigger';
const DATA_PREVIEW_POPOVER_SELECTOR = '.toolbar-data-preview-popover';
const DATA_PREVIEW_POPOVER_ID = 'epistola-toolbar-data-preview-popover';
const DATA_PREVIEW_PINNED_STORAGE_KEY = 'ep:data-preview:pinned';
const DATA_PREVIEW_POSITION_STORAGE_KEY = 'ep:data-preview:position';
const DATA_PREVIEW_VIEWPORT_MARGIN = 12;
const DATA_PREVIEW_DEFAULT_WIDTH = 640;
const DATA_PREVIEW_DEFAULT_HEIGHT = 400;
const DATA_PREVIEW_COPY_FEEDBACK_MS = 1200;

type JsonObject = Record<string, unknown>;
type DataPreviewPosition = { x: number; y: number };

function isJsonObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null;
}

@customElement('epistola-toolbar')
export class EpistolaToolbar extends LitElement {
  override createRenderRoot() {
    return this;
  }

  @property({ attribute: false }) engine?: EditorEngine;
  @property({ type: Boolean }) previewOpen = false;
  @property({ type: Boolean }) hasPreview = false;
  @property({ type: Boolean }) cleanMode = false;
  @property({ type: Boolean }) hasSave = false;
  @property({ type: Boolean }) hasDataContract = false;
  @property({ attribute: false }) saveState?: SaveState;
  @property({ attribute: false }) pluginActions?: ToolbarAction[];

  @state() private _currentExampleIndex = 0;
  @state() private _shortcutsOpen = false;
  @state() private _dataPreviewOpen = false;
  @state() private _dataPreviewPinned = false;
  @state() private _dataPreviewPosition: DataPreviewPosition | null = null;
  @state() private _dataPreviewCopyState: 'idle' | 'copied' | 'error' = 'idle';
  @state() private _shortcutsQuery = '';
  @state() private _activeShortcutStrokes: string[] = [];

  private _unsubExample?: () => void;
  private _unsubDoc?: () => void;
  private _activeShortcutClearTimeout: ReturnType<typeof setTimeout> | null = null;
  private _dataPreviewCopyTimeout: ReturnType<typeof setTimeout> | null = null;
  private _dataPreviewDragging = false;
  private _dataPreviewDragOffsetX = 0;
  private _dataPreviewDragOffsetY = 0;
  private _onWindowKeydown = (e: KeyboardEvent) => {
    if (!this._shortcutsOpen && !this._dataPreviewOpen) return;

    if (e.key === 'Escape') {
      if (this._shortcutsOpen) {
        e.preventDefault();
        this._closeShortcuts({ restoreFocus: true });
        return;
      }

      if (!this._dataPreviewPinned) {
        e.preventDefault();
        this._closeDataPreview({ restoreFocus: true });
      }
      return;
    }

    if (!this._shortcutsOpen) return;

    if (e.key === 'Shift' || e.key === 'Control' || e.key === 'Alt' || e.key === 'Meta') {
      return;
    }

    const normalized = normalizeShortcutEvent(e);
    const activeStrokes = [normalized.keyStroke, normalized.codeStroke]
      .map((stroke) => stroke.trim().toLowerCase())
      .filter((stroke) => stroke.length > 0);
    this._activeShortcutStrokes = [...new Set(activeStrokes)];
    this._scheduleClearActiveShortcutFeedback();
  };
  private _onWindowPointerDown = (e: PointerEvent) => {
    if (!this._shortcutsOpen && !this._dataPreviewOpen) return;
    const target = e.target;
    if (target instanceof Element && target.closest('.toolbar-shortcuts, .toolbar-data-preview'))
      return;
    this._closeShortcuts();
    if (!this._dataPreviewPinned) {
      this._closeDataPreview();
    }
  };

  private _onWindowResize = () => {
    if (!this._dataPreviewOpen || !this._dataPreviewPinned) return;
    this._normalizeDataPreviewPositionAfterRender();
  };

  private _onDataPreviewDragMove = (e: PointerEvent) => {
    if (!this._dataPreviewDragging || !this._dataPreviewPinned) return;

    const size = this._getDataPreviewSize();
    const next = this._clampDataPreviewPosition(
      {
        x: e.clientX - this._dataPreviewDragOffsetX,
        y: e.clientY - this._dataPreviewDragOffsetY,
      },
      size,
    );

    this._dataPreviewPosition = next;
  };

  private _onDataPreviewDragEnd = () => {
    this._stopDataPreviewDrag();
    this._persistDataPreviewState();
  };

  override connectedCallback(): void {
    super.connectedCallback();
    this._subscribeToEngine();
    this._restoreDataPreviewState();
    window.addEventListener('keydown', this._onWindowKeydown);
    window.addEventListener('pointerdown', this._onWindowPointerDown, true);
    window.addEventListener('resize', this._onWindowResize);
  }

  override updated(changed: Map<string, unknown>): void {
    if (changed.has('engine')) {
      this._unsubscribeAll();
      this._subscribeToEngine();
    }
  }

  override disconnectedCallback(): void {
    this._unsubscribeAll();
    window.removeEventListener('keydown', this._onWindowKeydown);
    window.removeEventListener('pointerdown', this._onWindowPointerDown, true);
    window.removeEventListener('resize', this._onWindowResize);
    this._stopDataPreviewDrag();
    this._clearDataPreviewCopyFeedback();
    this._clearActiveShortcutFeedback();
    super.disconnectedCallback();
  }

  private _unsubscribeAll(): void {
    this._unsubExample?.();
    this._unsubDoc?.();
  }

  private _subscribeToEngine(): void {
    if (!this.engine) return;
    this._currentExampleIndex = this.engine.currentExampleIndex;
    this._unsubExample = this.engine.events.on('example:change', ({ index }) => {
      this._currentExampleIndex = index;
      this._clearDataPreviewCopyFeedback();
    });
    // Re-render on doc changes so undo/redo button state stays in sync
    this._unsubDoc = this.engine.events.on('doc:change', () => {
      this.requestUpdate();
    });
  }

  private _handleUndo() {
    this.engine?.undo();
    this.requestUpdate();
  }

  private _handleRedo() {
    this.engine?.redo();
    this.requestUpdate();
  }

  private _handleForceSave() {
    this.dispatchEvent(new CustomEvent('force-save', { bubbles: true, composed: true }));
  }

  private _handleTogglePreview() {
    this.dispatchEvent(new CustomEvent('toggle-preview', { bubbles: true, composed: true }));
  }

  private _handleToggleCleanMode() {
    this.dispatchEvent(new CustomEvent('toggle-clean-mode', { bubbles: true, composed: true }));
  }

  private _handleOpenDataContract() {
    this.dispatchEvent(new CustomEvent('open-data-contract', { bubbles: true, composed: true }));
  }

  private _handleExampleChange(e: Event) {
    const select = e.target as HTMLSelectElement;
    const index = parseInt(select.value, 10);
    this.engine?.setCurrentExample(index);
  }

  private _scheduleClearActiveShortcutFeedback(): void {
    if (this._activeShortcutClearTimeout) {
      clearTimeout(this._activeShortcutClearTimeout);
    }
    this._activeShortcutClearTimeout = setTimeout(() => {
      this._activeShortcutStrokes = [];
      this._activeShortcutClearTimeout = null;
    }, SHORTCUT_ACTIVE_FEEDBACK_MS);
  }

  private _clearActiveShortcutFeedback(): void {
    if (this._activeShortcutClearTimeout) {
      clearTimeout(this._activeShortcutClearTimeout);
      this._activeShortcutClearTimeout = null;
    }
    this._activeShortcutStrokes = [];
  }

  private _clearDataPreviewCopyFeedback(): void {
    if (this._dataPreviewCopyTimeout) {
      clearTimeout(this._dataPreviewCopyTimeout);
      this._dataPreviewCopyTimeout = null;
    }
    this._dataPreviewCopyState = 'idle';
  }

  private _scheduleDataPreviewCopyFeedbackReset(): void {
    if (this._dataPreviewCopyTimeout) {
      clearTimeout(this._dataPreviewCopyTimeout);
    }
    this._dataPreviewCopyTimeout = setTimeout(() => {
      this._dataPreviewCopyState = 'idle';
      this._dataPreviewCopyTimeout = null;
    }, DATA_PREVIEW_COPY_FEEDBACK_MS);
  }

  private _focusShortcutSearchAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const input = this.querySelector<HTMLInputElement>(SHORTCUTS_SEARCH_SELECTOR);
        input?.focus({ preventScroll: true });
      });
    });
  }

  private _focusShortcutTriggerAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const trigger = this.querySelector<HTMLElement>(SHORTCUTS_TRIGGER_SELECTOR);
        trigger?.focus({ preventScroll: true });
      });
    });
  }

  private _focusDataPreviewAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const popover = this.querySelector<HTMLElement>(DATA_PREVIEW_POPOVER_SELECTOR);
        popover?.focus({ preventScroll: true });
      });
    });
  }

  private _focusDataPreviewTriggerAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const trigger = this.querySelector<HTMLElement>(DATA_PREVIEW_TRIGGER_SELECTOR);
        trigger?.focus({ preventScroll: true });
      });
    });
  }

  private _restoreDataPreviewState(): void {
    try {
      const pinned = localStorage.getItem(DATA_PREVIEW_PINNED_STORAGE_KEY) === 'true';
      const position = this._readStoredDataPreviewPosition();
      this._dataPreviewPinned = pinned;
      this._dataPreviewPosition = position;

      if (pinned) {
        this._dataPreviewOpen = true;
        if (!position) {
          this._dataPreviewPosition = this._deriveDefaultDataPreviewPosition();
        }
        this._normalizeDataPreviewPositionAfterRender();
      }
    } catch {
      // localStorage may be unavailable
    }
  }

  private _readStoredDataPreviewPosition(): DataPreviewPosition | null {
    const raw = localStorage.getItem(DATA_PREVIEW_POSITION_STORAGE_KEY);
    if (!raw) return null;

    try {
      const parsed = JSON.parse(raw) as { x?: unknown; y?: unknown };
      if (typeof parsed.x !== 'number' || typeof parsed.y !== 'number') {
        return null;
      }

      return this._clampDataPreviewPosition(
        { x: parsed.x, y: parsed.y },
        { width: DATA_PREVIEW_DEFAULT_WIDTH, height: DATA_PREVIEW_DEFAULT_HEIGHT },
      );
    } catch {
      return null;
    }
  }

  private _persistDataPreviewState(): void {
    try {
      localStorage.setItem(DATA_PREVIEW_PINNED_STORAGE_KEY, String(this._dataPreviewPinned));

      if (this._dataPreviewPosition) {
        localStorage.setItem(
          DATA_PREVIEW_POSITION_STORAGE_KEY,
          JSON.stringify(this._dataPreviewPosition),
        );
      } else {
        localStorage.removeItem(DATA_PREVIEW_POSITION_STORAGE_KEY);
      }
    } catch {
      // localStorage may be unavailable
    }
  }

  private _getDataPreviewSize(): { width: number; height: number } {
    const popover = this.querySelector<HTMLElement>(DATA_PREVIEW_POPOVER_SELECTOR);
    if (popover) {
      const rect = popover.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        return { width: rect.width, height: rect.height };
      }
    }

    return {
      width: DATA_PREVIEW_DEFAULT_WIDTH,
      height: DATA_PREVIEW_DEFAULT_HEIGHT,
    };
  }

  private _clampDataPreviewPosition(
    position: DataPreviewPosition,
    size: { width: number; height: number },
  ): DataPreviewPosition {
    const maxX = Math.max(
      DATA_PREVIEW_VIEWPORT_MARGIN,
      window.innerWidth - size.width - DATA_PREVIEW_VIEWPORT_MARGIN,
    );
    const maxY = Math.max(
      DATA_PREVIEW_VIEWPORT_MARGIN,
      window.innerHeight - size.height - DATA_PREVIEW_VIEWPORT_MARGIN,
    );

    return {
      x: Math.round(Math.min(Math.max(position.x, DATA_PREVIEW_VIEWPORT_MARGIN), maxX)),
      y: Math.round(Math.min(Math.max(position.y, DATA_PREVIEW_VIEWPORT_MARGIN), maxY)),
    };
  }

  private _deriveDefaultDataPreviewPosition(): DataPreviewPosition {
    const size = this._getDataPreviewSize();
    const trigger = this.querySelector<HTMLElement>(DATA_PREVIEW_TRIGGER_SELECTOR);
    if (trigger) {
      const rect = trigger.getBoundingClientRect();
      return this._clampDataPreviewPosition(
        {
          x: Math.round(rect.right - size.width),
          y: Math.round(rect.bottom + 8),
        },
        size,
      );
    }

    return this._clampDataPreviewPosition(
      {
        x: window.innerWidth - size.width - 24,
        y: 72,
      },
      size,
    );
  }

  private _normalizeDataPreviewPositionAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        if (!this._dataPreviewOpen || !this._dataPreviewPinned) return;

        const size = this._getDataPreviewSize();
        const next = this._clampDataPreviewPosition(
          this._dataPreviewPosition ?? this._deriveDefaultDataPreviewPosition(),
          size,
        );

        const current = this._dataPreviewPosition;
        if (!current || current.x !== next.x || current.y !== next.y) {
          this._dataPreviewPosition = next;
          this._persistDataPreviewState();
        }
      });
    });
  }

  private _startDataPreviewDrag(e: PointerEvent): void {
    if (!this._dataPreviewPinned || e.button !== 0) return;

    const popover = this.querySelector<HTMLElement>(DATA_PREVIEW_POPOVER_SELECTOR);
    if (!popover) return;

    const rect = popover.getBoundingClientRect();
    this._dataPreviewDragging = true;
    this._dataPreviewDragOffsetX = e.clientX - rect.left;
    this._dataPreviewDragOffsetY = e.clientY - rect.top;

    document.body.style.userSelect = 'none';
    window.addEventListener('pointermove', this._onDataPreviewDragMove);
    window.addEventListener('pointerup', this._onDataPreviewDragEnd);
    window.addEventListener('pointercancel', this._onDataPreviewDragEnd);
    e.preventDefault();
    e.stopPropagation();
  }

  private _stopDataPreviewDrag(): void {
    if (!this._dataPreviewDragging) return;
    this._dataPreviewDragging = false;
    this._dataPreviewDragOffsetX = 0;
    this._dataPreviewDragOffsetY = 0;
    document.body.style.userSelect = '';
    window.removeEventListener('pointermove', this._onDataPreviewDragMove);
    window.removeEventListener('pointerup', this._onDataPreviewDragEnd);
    window.removeEventListener('pointercancel', this._onDataPreviewDragEnd);
  }

  private _openShortcuts(): void {
    this._closeDataPreview();
    this._shortcutsOpen = true;
    this._clearActiveShortcutFeedback();
    this._focusShortcutSearchAfterRender();
  }

  private _closeShortcuts(options: { restoreFocus?: boolean } = {}): void {
    if (!this._shortcutsOpen) return;
    this._shortcutsOpen = false;
    this._shortcutsQuery = '';
    this._clearActiveShortcutFeedback();
    if (options.restoreFocus) {
      this._focusShortcutTriggerAfterRender();
    }
  }

  private _openDataPreview(): void {
    this._closeShortcuts();

    if (this._dataPreviewPinned && !this._dataPreviewPosition) {
      this._dataPreviewPosition = this._deriveDefaultDataPreviewPosition();
    }

    this._dataPreviewOpen = true;
    this._clearDataPreviewCopyFeedback();
    if (this._dataPreviewPinned) {
      this._normalizeDataPreviewPositionAfterRender();
    }
    this._focusDataPreviewAfterRender();
  }

  private _closeDataPreview(options: { restoreFocus?: boolean; force?: boolean } = {}): void {
    if (!this._dataPreviewOpen) return;
    if (this._dataPreviewPinned && !options.force) return;

    this._stopDataPreviewDrag();
    this._clearDataPreviewCopyFeedback();
    this._dataPreviewOpen = false;
    if (options.restoreFocus) {
      this._focusDataPreviewTriggerAfterRender();
    }
  }

  private _toggleShortcutHelp(e: Event) {
    e.stopPropagation();
    if (this._shortcutsOpen) {
      this._closeShortcuts();
      return;
    }
    this._openShortcuts();
  }

  private _toggleDataPreview(e: Event): void {
    e.stopPropagation();
    if (this._dataPreviewOpen) {
      if (this._dataPreviewPinned) {
        this._focusDataPreviewAfterRender();
        return;
      }
      this._closeDataPreview();
      return;
    }
    this._openDataPreview();
  }

  private _toggleDataPreviewPinned(e: Event): void {
    e.stopPropagation();

    const popover = this.querySelector<HTMLElement>(DATA_PREVIEW_POPOVER_SELECTOR);
    const currentRect = popover?.getBoundingClientRect();

    this._dataPreviewPinned = !this._dataPreviewPinned;
    if (this._dataPreviewPinned) {
      const size = this._getDataPreviewSize();
      const derivedPosition = currentRect
        ? this._clampDataPreviewPosition({ x: currentRect.left, y: currentRect.top }, size)
        : this._deriveDefaultDataPreviewPosition();

      this._dataPreviewPosition = derivedPosition;
      this._dataPreviewOpen = true;
      this._normalizeDataPreviewPositionAfterRender();
      this._focusDataPreviewAfterRender();
    } else {
      this._stopDataPreviewDrag();
    }

    this._persistDataPreviewState();
  }

  private async _copyDataPreviewJson(e: Event): Promise<void> {
    e.stopPropagation();

    const preview = this._resolveCurrentExamplePreview();
    if (preview.json === null) {
      return;
    }

    try {
      if (!navigator.clipboard?.writeText) {
        throw new Error('Clipboard API unavailable');
      }

      await navigator.clipboard.writeText(preview.json);
      this._dataPreviewCopyState = 'copied';
    } catch {
      this._dataPreviewCopyState = 'error';
    }

    this._scheduleDataPreviewCopyFeedbackReset();
  }

  private _dataPreviewCopyButtonLabel(): string {
    if (this._dataPreviewCopyState === 'copied') {
      return 'Copied';
    }
    if (this._dataPreviewCopyState === 'error') {
      return 'Copy failed';
    }
    return 'Copy JSON';
  }

  openShortcuts(): void {
    this._openShortcuts();
  }

  openDataPreview(): void {
    this._openDataPreview();
  }

  private _resolveCurrentExampleRaw(): JsonObject | undefined {
    const examples = this.engine?.dataExamples;
    if (!examples || examples.length === 0) {
      return undefined;
    }

    const example = examples[this._currentExampleIndex];
    return isJsonObject(example) ? example : undefined;
  }

  private _resolveCurrentExampleName(example: JsonObject | undefined): string {
    if (!example) {
      return `Example ${this._currentExampleIndex + 1}`;
    }

    if (typeof example.name === 'string' && example.name.trim().length > 0) {
      return example.name;
    }

    if (typeof example.label === 'string' && example.label.trim().length > 0) {
      return example.label;
    }

    return `Example ${this._currentExampleIndex + 1}`;
  }

  private _resolveCurrentExamplePreview(): {
    header: string;
    json: string | null;
  } {
    const examples = this.engine?.dataExamples;
    if (!examples || examples.length === 0) {
      return {
        header: 'No examples available',
        json: null,
      };
    }

    const current = this._resolveCurrentExampleRaw();
    const name = this._resolveCurrentExampleName(current);
    const position = `${this._currentExampleIndex + 1}/${examples.length}`;
    const payload = this.engine?.getExampleData();

    if (payload === undefined) {
      return {
        header: `${name} (${position})`,
        json: null,
      };
    }

    try {
      return {
        header: `${name} (${position})`,
        json: JSON.stringify(payload, null, 2),
      };
    } catch {
      return {
        header: `${name} (${position})`,
        json: String(payload),
      };
    }
  }

  private _handleShortcutSearchInput(e: Event): void {
    const target = e.target as HTMLInputElement;
    this._shortcutsQuery = target.value;
  }

  private _renderShortcutKeys(keys: string) {
    const parts = keys.split('{cmd}');
    return html`${parts.map(
      (part, i) => html`
        ${i > 0
          ? html`<span class="shortcut-cmd-icon">${icon('command', 12)}</span>`
          : nothing}${part}
      `,
    )}`;
  }

  override render() {
    const canUndo = this.engine?.canUndo ?? false;
    const canRedo = this.engine?.canRedo ?? false;
    const examples = this.engine?.dataExamples;
    const hasExamples = examples && examples.length > 0;

    return html`
      <div class="epistola-toolbar">
        <span class="toolbar-title">Template Editor</span>

        <div class="toolbar-separator"></div>

        <div class="toolbar-actions">
          <button
            class="toolbar-btn"
            ?disabled=${!canUndo}
            @click=${this._handleUndo}
            title=${`Undo (${toTooltipShortcutLabel(UNDO_SHORTCUT_HELP)})`}
          >
            ${icon('undo-2')} Undo
          </button>
          <button
            class="toolbar-btn"
            ?disabled=${!canRedo}
            @click=${this._handleRedo}
            title=${`Redo (${toTooltipShortcutLabel(REDO_SHORTCUT_HELP)})`}
          >
            ${icon('redo-2')} Redo
          </button>
        </div>

        ${this.hasSave ? this._renderSaveButton() : nothing}
        ${this.hasPreview
          ? html`
              <div class="toolbar-separator"></div>
              <button
                class="toolbar-btn ${this.previewOpen ? 'active' : ''}"
                @click=${this._handleTogglePreview}
                title="${this.previewOpen ? 'Hide preview' : 'Show preview'}"
              >
                ${this.previewOpen ? icon('eye-off') : icon('eye')} Preview
              </button>
            `
          : nothing}
        <div class="toolbar-separator"></div>
        <button
          class="toolbar-btn ${this.cleanMode ? 'active' : ''}"
          @click=${this._handleToggleCleanMode}
          title="${this.cleanMode ? 'Show block chrome' : 'Hide block chrome'}"
        >
          ${this.cleanMode ? icon('eye') : icon('sparkles')} Clean
        </button>

        ${hasExamples ? this._renderExampleSelector(examples!) : nothing}
        ${this.hasDataContract
          ? html`
              <div class="toolbar-separator"></div>
              <button
                class="btn btn-outline btn-sm toolbar-data-contract-trigger"
                @click=${this._handleOpenDataContract}
                title="Edit data contract schema and examples"
              >
                ${icon('braces')} Data Contract
              </button>
            `
          : nothing}
        ${this._renderPluginActions()}
      </div>
    `;
  }

  private _renderPluginActions() {
    if (!this.pluginActions || this.pluginActions.length === 0) return nothing;

    return html`
      <div class="toolbar-separator"></div>
      ${this.pluginActions.map(
        (action) => html`
          <button class="toolbar-btn" @click=${action.onClick} title=${action.label}>
            ${icon(action.icon as Parameters<typeof icon>[0])} ${action.label}
          </button>
        `,
      )}
    `;
  }

  private _renderSaveButton() {
    const status = this.saveState?.status ?? 'idle';
    const isError = status === 'error';
    const errorMsg = isError
      ? (this.saveState as { status: 'error'; message: string }).message
      : '';

    // Determine button attributes based on state
    const disabled = status === 'idle' || status === 'saving' || status === 'saved';
    const cssClass = `toolbar-btn ${status === 'saving' ? 'saving' : ''} ${status === 'saved' ? 'saved' : ''} ${isError ? 'save-error' : ''}`;

    let iconName: 'save' | 'check' | 'loader-2' | 'triangle-alert';
    let label: string;
    let title: string;

    switch (status) {
      case 'idle':
        iconName = 'check';
        label = 'Saved';
        title = 'All changes saved';
        break;
      case 'dirty':
        iconName = 'save';
        label = 'Save';
        title = `Save (${toTooltipShortcutLabel(SAVE_SHORTCUT_HELP)})`;
        break;
      case 'saving':
        iconName = 'loader-2';
        label = 'Saving...';
        title = 'Saving changes...';
        break;
      case 'saved':
        iconName = 'check';
        label = 'Saved';
        title = 'All changes saved';
        break;
      case 'error':
        iconName = 'triangle-alert';
        label = 'Save';
        title = `Save failed: ${errorMsg}. Click to retry.`;
        break;
    }

    return html`
      <div class="toolbar-separator"></div>
      <button
        class=${cssClass}
        ?disabled=${disabled}
        @click=${this._handleForceSave}
        title=${title}
      >
        ${icon(iconName)} ${label}
      </button>
    `;
  }

  private _renderExampleSelector(examples: object[]) {
    const shortcutProjection = buildShortcutGroupsProjection({
      query: this._shortcutsQuery,
      activeStrokes: this._activeShortcutStrokes,
    });
    const shortcutGroups = shortcutProjection.groups;
    const currentExamplePreview = this._resolveCurrentExamplePreview();
    const dataPreviewPopoverClass = [
      'toolbar-data-preview-popover',
      this._dataPreviewPinned ? 'is-pinned' : '',
    ]
      .filter(Boolean)
      .join(' ');
    const dataPreviewPopoverStyle = this._dataPreviewPinned
      ? (() => {
          const position = this._dataPreviewPosition;

          if (!position) {
            return '';
          }

          return `left: ${position.x}px; top: ${position.y}px;`;
        })()
      : '';
    const dataPreviewPinLabel = this._dataPreviewPinned
      ? 'Unpin data example viewer'
      : 'Pin data example viewer';
    const canCopyDataPreview = currentExamplePreview.json !== null;
    const dataPreviewCopyLabel = this._dataPreviewCopyButtonLabel();
    const dataPreviewCopyClass = [
      'toolbar-data-preview-copy',
      this._dataPreviewCopyState === 'copied' ? 'is-success' : '',
      this._dataPreviewCopyState === 'error' ? 'is-error' : '',
    ]
      .filter(Boolean)
      .join(' ');

    return html`
      <div class="toolbar-example-selector">
        <label class="toolbar-example-label" for="example-select">Data</label>
        <select
          id="example-select"
          class="toolbar-example-select"
          .value=${String(this._currentExampleIndex)}
          @change=${this._handleExampleChange}
        >
          ${examples.map((ex, i) => {
            const label = this._getExampleLabel(ex, i);
            return html`<option value=${i} ?selected=${i === this._currentExampleIndex}>
              ${label}
            </option>`;
          })}
        </select>

        <div class="toolbar-data-preview">
          <button
            class="btn btn-outline btn-sm btn-icon toolbar-shortcuts-trigger toolbar-data-preview-trigger"
            data-testid="data-example-trigger"
            type="button"
            title="Current data example"
            aria-label="Current data example"
            aria-haspopup="dialog"
            aria-controls=${DATA_PREVIEW_POPOVER_ID}
            aria-expanded=${String(this._dataPreviewOpen)}
            @click=${this._toggleDataPreview}
          >
            ${icon('file-text')}
          </button>

          ${this._dataPreviewOpen
            ? html`
                <div
                  id=${DATA_PREVIEW_POPOVER_ID}
                  class=${dataPreviewPopoverClass}
                  style=${dataPreviewPopoverStyle}
                  data-testid="data-example-popover"
                  role="dialog"
                  aria-label="Current data example"
                  tabindex="-1"
                >
                  <div class="toolbar-data-preview-header">
                    <div class="toolbar-data-preview-title">Current Data Example</div>

                    <div class="toolbar-data-preview-actions">
                      ${this._dataPreviewPinned
                        ? html`
                            <button
                              class="btn btn-outline btn-sm toolbar-data-preview-drag-handle"
                              data-testid="data-example-drag-handle"
                              type="button"
                              title="Drag to move"
                              aria-label="Drag to move"
                              @pointerdown=${(e: PointerEvent) => this._startDataPreviewDrag(e)}
                            >
                              ${icon('grip-vertical', 14)} Drag
                            </button>
                          `
                        : nothing}

                      <button
                        class=${`btn btn-outline btn-sm ${dataPreviewCopyClass}`}
                        data-testid="data-example-copy"
                        type="button"
                        ?disabled=${!canCopyDataPreview}
                        @click=${(e: Event) => void this._copyDataPreviewJson(e)}
                      >
                        ${dataPreviewCopyLabel}
                      </button>

                      <button
                        class="btn btn-outline btn-sm btn-icon toolbar-data-preview-pin ${this
                          ._dataPreviewPinned
                          ? 'is-active'
                          : ''}"
                        data-testid="data-example-pin"
                        type="button"
                        title=${dataPreviewPinLabel}
                        aria-label=${dataPreviewPinLabel}
                        aria-pressed=${String(this._dataPreviewPinned)}
                        @click=${this._toggleDataPreviewPinned}
                      >
                        ${icon('paperclip', 14)}
                      </button>
                    </div>
                  </div>

                  ${!this._dataPreviewPinned
                    ? html`
                        <div class="toolbar-data-preview-drag-hint">
                          ${icon('paperclip', 12)} Pin to keep this viewer open and movable
                        </div>
                      `
                    : nothing}

                  <div class="toolbar-data-preview-meta">${currentExamplePreview.header}</div>

                  ${currentExamplePreview.json !== null
                    ? html`
                        <textarea
                          class="toolbar-data-preview-json"
                          data-testid="data-example-json"
                          readonly
                          spellcheck="false"
                          aria-label="Current data example JSON"
                          .value=${currentExamplePreview.json}
                        ></textarea>
                      `
                    : html`
                        <div class="toolbar-data-preview-empty">
                          No payload available for this example.
                        </div>
                      `}
                </div>
              `
            : nothing}
        </div>

        <div class="toolbar-shortcuts">
          <button
            class="btn btn-outline btn-sm btn-icon toolbar-shortcuts-trigger"
            data-testid="shortcuts-trigger"
            type="button"
            title="Keyboard shortcuts"
            aria-label="Keyboard shortcuts"
            aria-haspopup="dialog"
            aria-controls=${SHORTCUTS_POPOVER_ID}
            aria-expanded=${String(this._shortcutsOpen)}
            @click=${this._toggleShortcutHelp}
          >
            ${icon('command')}
          </button>

          ${this._shortcutsOpen
            ? html`
                <div
                  id=${SHORTCUTS_POPOVER_ID}
                  class="toolbar-shortcuts-popover"
                  data-testid="shortcuts-popover"
                  role="dialog"
                  aria-label="Keyboard shortcuts"
                >
                  <div class="toolbar-shortcuts-title">Keyboard Shortcuts</div>
                  <div class="toolbar-shortcuts-search">
                    <input
                      class="toolbar-shortcuts-search-input"
                      type="search"
                      placeholder="Search shortcuts"
                      .value=${this._shortcutsQuery}
                      aria-label="Filter keyboard shortcuts"
                      @input=${this._handleShortcutSearchInput}
                    />
                  </div>
                  <div class="toolbar-shortcuts-groups">
                    ${shortcutGroups.length === 0
                      ? html`<div class="toolbar-shortcuts-empty">
                          No shortcuts found for this filter.
                        </div>`
                      : shortcutGroups.map(
                          (group: ShortcutGroup) => html`
                            <div
                              class="toolbar-shortcuts-group ${group.fullWidth
                                ? 'is-full-width'
                                : ''}"
                            >
                              <div class="toolbar-shortcuts-group-title">${group.title}</div>
                              <div
                                class="toolbar-shortcuts-items ${group.layout === 'two-column'
                                  ? 'layout-two-column'
                                  : 'layout-one-column'}"
                              >
                                ${group.items.map(
                                  (item) => html`
                                    <div
                                      class="toolbar-shortcuts-row ${item.active
                                        ? 'is-active'
                                        : ''}"
                                    >
                                      <span class="toolbar-shortcuts-keys"
                                        >${this._renderShortcutKeys(item.keys)}</span
                                      >
                                      <span class="toolbar-shortcuts-action">${item.action}</span>
                                    </div>
                                  `,
                                )}
                              </div>
                            </div>
                          `,
                        )}
                  </div>
                  <div class="toolbar-shortcuts-footer">
                    Tip: ${shortcutProjection.footerTip} opens this help
                  </div>
                </div>
              `
            : nothing}
        </div>
      </div>
    `;
  }

  /**
   * Derive a display label for an example. Uses `name` or `label` field
   * if present, otherwise falls back to "Example N".
   */
  private _getExampleLabel(example: object, index: number): string {
    const obj = example as Record<string, unknown>;
    if (typeof obj.name === 'string' && obj.name) return obj.name;
    if (typeof obj.label === 'string' && obj.label) return obj.label;
    return `Example ${index + 1}`;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-toolbar': EpistolaToolbar;
  }
}
