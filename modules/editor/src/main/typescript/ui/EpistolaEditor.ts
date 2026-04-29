import { LitElement, html, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { keyed } from 'lit/directives/keyed.js';
import type { TemplateDocument, NodeId, SlotId } from '../types/index.js';
import { EditorEngine } from '../engine/EditorEngine.js';
import {
  createDefaultRegistry,
  PAGE_HEADER_TYPE,
  PAGE_FOOTER_TYPE,
  isAnchoredPageBlock,
} from '../engine/registry.js';
import type { ComponentRegistry, ComponentDefinition } from '../engine/registry.js';
import type { FetchPreviewFn } from './preview-service.js';
import { SaveService, type SaveState, type SaveFn } from './save-service.js';
import { EpistolaResizeHandle } from './EpistolaResizeHandle.js';
import type { DataExample, JsonSchema, SaveCallbacks } from '../data-contract/types.js';
import type { EpistolaDataContractEditor } from '../data-contract/EpistolaDataContractEditor.js';
import type {
  EditorPlugin,
  PluginContext,
  PluginDisposeFn,
  SidebarTabContribution,
  ToolbarAction,
} from '../plugins/types.js';
import type { EpistolaSidebar } from './EpistolaSidebar.js';
import type { EpistolaToolbar } from './EpistolaToolbar.js';
import { EDITOR_SHORTCUTS_CONFIG } from '../shortcuts-config.js';
import {
  getAllLeaderIdleTokens,
  getEditorShortcutRegistry,
  getLeaderIdleTokensForCommandIds,
  type EditorShortcutRuntimeContext,
} from '../shortcuts/editor-runtime.js';
import {
  getInsertDialogShortcutRegistry,
  INSERT_DIALOG_KEYS,
  type InsertDialogShortcutRuntimeContext,
} from '../shortcuts/insert-dialog-runtime.js';
import { mergeRegistries } from '../shortcuts/foundation.js';
import {
  ShortcutResolver,
  applyResolutionEventPolicy,
  startShortcutCommandExecution,
} from '../shortcuts/resolver.js';
import { LeaderModeController, type LeaderModeState } from '../shortcuts/leader-controller.js';
import { validateCoreShortcutRegistriesOnStartup } from '../shortcuts/startup-validation.js';
import {
  extractBlockSubtree,
  readBlockClipboardData,
  rekeyBlockSubtree,
  writeBlockClipboardData,
  type BlockSubtree,
} from './block-clipboard.js';
import { icon } from './icons.js';

import './EpistolaSidebar.js';
import './EpistolaCanvas.js';
import './EpistolaToolbar.js';
import './EpistolaPreview.js';
import './EpistolaResizeHandle.js';
import '../data-contract/EpistolaDataContractEditor.js';

type InsertMode = 'after' | 'before' | 'inside' | 'start' | 'end';
type PasteDialogMode = 'placement' | 'slot';
type ClosestCapableTarget = {
  closest(selector: string): Element | null;
};

interface InsertSlotOption {
  slotId: SlotId;
  label: string;
}

interface InsertTarget {
  slotId: SlotId;
  index: number;
  parentType: string;
}

function isDataExampleArray(value: unknown): value is DataExample[] {
  if (!Array.isArray(value)) return false;
  return value.every((item) => typeof item === 'object' && item !== null && 'id' in item);
}

export interface EditorDataContractOptions {
  initialSchema: JsonSchema | null;
  initialExamples: DataExample[];
  callbacks: SaveCallbacks;
  readonly?: boolean;
}

const INSERT_DIALOG_SHORTCUTS = INSERT_DIALOG_KEYS;

const EDITABLE_TARGET_SELECTOR = 'input, textarea, select, [contenteditable="true"], .ProseMirror';

function isClosestCapableTarget(value: unknown): value is ClosestCapableTarget {
  return (
    !!value &&
    typeof value === 'object' &&
    'closest' in value &&
    typeof value.closest === 'function'
  );
}

function getClosestCapableTarget(target: EventTarget | null): ClosestCapableTarget | null {
  if (isClosestCapableTarget(target)) {
    return target;
  }

  if (!target || typeof target !== 'object' || !('parentElement' in target)) {
    return null;
  }

  const parentElement = target.parentElement;
  return isClosestCapableTarget(parentElement) ? parentElement : null;
}

/**
 * <epistola-editor> — Root editor element.
 *
 * Shadow DOM is disabled (createRenderRoot returns this) so that
 * the global editor.css styles apply without scoping issues. This is
 * intentional — the editor is not a reusable web component library,
 * it's an application component embedded in a specific Thymeleaf page.
 */
@customElement('epistola-editor')
export class EpistolaEditor extends LitElement {
  /** Disable Shadow DOM — use light DOM for global CSS compatibility. */
  override createRenderRoot() {
    return this;
  }

  private _engine?: EditorEngine;
  private _unsubEngine?: () => void;
  private _unsubSelection?: () => void;
  private _saveService?: SaveService;
  private _pluginDisposers: PluginDisposeFn[] = [];
  private _onKeydown = this._handleKeydown.bind(this);
  private _onCopy = this._handleCopy.bind(this);
  private _onPaste = this._handlePaste.bind(this);
  private _onBeforeUnload = this._handleBeforeUnload.bind(this);
  private readonly _shortcutResolver = new ShortcutResolver<unknown>(
    mergeRegistries(getEditorShortcutRegistry(), getInsertDialogShortcutRegistry()),
    {
      fallbackContexts: [],
      chord: {
        timeoutMs: EDITOR_SHORTCUTS_CONFIG.leader.timeout.idleHideMs,
        cancelKeys: ['escape'],
      },
    },
  );
  private readonly _leaderController = new LeaderModeController({
    timing: EDITOR_SHORTCUTS_CONFIG.leader.timeout,
    getIdleTokens: getLeaderIdleTokensForCommandIds,
    fallbackTokens: getAllLeaderIdleTokens(),
    onStateChange: (state) => this._applyLeaderState(state),
    cancelActiveChord: () => this._shortcutResolver.cancelActiveChord(),
    blurEditingTarget: () => {
      const active = document.activeElement as HTMLElement | null;
      if (active && this.contains(active) && active.matches(EDITABLE_TARGET_SELECTOR)) {
        active.blur();
      }
    },
  });

  @property({ attribute: false }) fetchPreview?: FetchPreviewFn;
  @property({ attribute: false }) onSave?: SaveFn;
  @property({ attribute: false }) plugins?: EditorPlugin[];
  @property({ attribute: false }) dataContractOptions?: EditorDataContractOptions;
  @state() private _doc?: TemplateDocument;
  @state() private _selectedNodeId: NodeId | null = null;
  @state() private _previewOpen = false;
  @state() private _cleanMode = false;
  @state() private _saveState: SaveState = { status: 'idle' };
  @state() private _leaderVisible = false;
  @state() private _leaderStatus: 'idle' | 'success' | 'error' = 'idle';
  @state() private _leaderMessage = '';
  @state() private _insertDialogOpen = false;
  @state() private _insertDialogMode: InsertMode | null = null;
  @state() private _insertDialogSlotOptions: InsertSlotOption[] = [];
  @state() private _insertDialogBlockOptions: ComponentDefinition[] = [];
  @state() private _insertDialogQuery = '';
  @state() private _insertDialogHighlight = 0;
  @state() private _insertDialogError = '';
  @state() private _pasteDialogOpen = false;
  @state() private _pasteDialogMode: PasteDialogMode = 'placement';
  @state() private _pasteDialogSlotOptions: InsertSlotOption[] = [];
  @state() private _pasteDialogError = '';
  @state() private _dataContractOpen = false;

  private _insertTarget: InsertTarget | null = null;
  private _pasteSubtree: BlockSubtree | null = null;
  private _dataContractMounted = false;

  private static readonly PREVIEW_OPEN_KEY = 'ep:preview-open';
  private static readonly CLEAN_MODE_KEY = 'ep:clean-mode';

  get engine(): EditorEngine | undefined {
    return this._engine;
  }

  /**
   * Initialize the engine with a template document.
   */
  initEngine(
    doc: TemplateDocument,
    registry?: ComponentRegistry,
    options?: { dataModel?: object; dataExamples?: object[] },
  ): void {
    // Clean up previous engine and save service
    this._unsubEngine?.();
    this._unsubSelection?.();
    this._saveService?.dispose();
    this._saveService = undefined;

    const reg = registry ?? createDefaultRegistry();
    this._engine = new EditorEngine(doc, reg, {
      dataModel: options?.dataModel,
      dataExamples: options?.dataExamples,
    });
    this._doc = this._engine.doc;

    this._unsubEngine = this._engine.events.on('doc:change', ({ doc }) => {
      this._doc = doc;
      // Notify save service of changes
      if (this._saveService) {
        this._saveService.markDirty();
        this._saveService.scheduleAutoSave(doc);
      }
    });

    this._unsubSelection = this._engine.events.on('selection:change', ({ nodeId }) => {
      this._selectedNodeId = nodeId;
      // Clear component state when selecting a different node
      this._engine?.setComponentState('table:cellSelection', null);
    });

    // Create save service if onSave callback is provided
    if (this.onSave) {
      this._saveService = new SaveService(this.onSave, (state) => {
        this._saveState = state;
      });
    }

    // Initialize plugins
    this._disposePlugins();
    if (this.plugins) {
      const context: PluginContext = {
        engine: this._engine,
        doc: this._doc!,
        selectedNodeId: this._selectedNodeId,
      };
      this._pluginDisposers = this.plugins.map((p) => p.init(context));
    }
  }

  private _disposePlugins(): void {
    this._pluginDisposers.forEach((dispose) => dispose());
    this._pluginDisposers = [];
  }

  /** Sidebar tab contributions from plugins. */
  private get _pluginSidebarTabs(): SidebarTabContribution[] {
    if (!this.plugins) return [];
    return this.plugins.filter((p) => p.sidebarTab).map((p) => p.sidebarTab!);
  }

  /** Toolbar action contributions from plugins. */
  private get _pluginToolbarActions(): ToolbarAction[] {
    if (!this.plugins) return [];
    return this.plugins.flatMap((p) => p.toolbarActions ?? []);
  }

  /** Current plugin context for reactive sidebar tab rendering. */
  private get _pluginContext(): PluginContext | undefined {
    if (!this._engine || !this._doc) return undefined;
    return {
      engine: this._engine,
      doc: this._doc,
      selectedNodeId: this._selectedNodeId,
    };
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override connectedCallback(): void {
    super.connectedCallback();
    validateCoreShortcutRegistriesOnStartup();
    window.addEventListener('keydown', this._onKeydown);
    window.addEventListener('copy', this._onCopy);
    window.addEventListener('paste', this._onPaste);
    this.addEventListener('toggle-preview', this._handleTogglePreview);
    this.addEventListener('toggle-clean-mode', this._handleToggleCleanMode);
    this.addEventListener('open-data-contract', this._handleOpenDataContract);
    this.addEventListener('force-save', this._handleForceSave);
    window.addEventListener('beforeunload', this._onBeforeUnload);

    // Restore preview open state from localStorage
    try {
      this._previewOpen = localStorage.getItem(EpistolaEditor.PREVIEW_OPEN_KEY) === 'true';
      this._cleanMode = localStorage.getItem(EpistolaEditor.CLEAN_MODE_KEY) === 'true';
    } catch {
      // localStorage may be unavailable
    }
  }

  override disconnectedCallback(): void {
    window.removeEventListener('keydown', this._onKeydown);
    window.removeEventListener('copy', this._onCopy);
    window.removeEventListener('paste', this._onPaste);
    this.removeEventListener('toggle-preview', this._handleTogglePreview);
    this.removeEventListener('toggle-clean-mode', this._handleToggleCleanMode);
    this.removeEventListener('open-data-contract', this._handleOpenDataContract);
    this.removeEventListener('force-save', this._handleForceSave);
    window.removeEventListener('beforeunload', this._onBeforeUnload);
    super.disconnectedCallback();
    this._disposePlugins();
    this._unsubEngine?.();
    this._unsubSelection?.();
    this._saveService?.dispose();
    this._shortcutResolver.cancelActiveChord();
    this._leaderController.dispose();
  }

  // ---------------------------------------------------------------------------
  // Keyboard Handling
  // ---------------------------------------------------------------------------

  private _isShortcutEditingTarget(target: ClosestCapableTarget | null): boolean {
    return !!target && !!target.closest(EDITABLE_TARGET_SELECTOR);
  }

  private _isCopyPasteEventInsideEditor(target: EventTarget | null): boolean {
    const targetElement = getClosestCapableTarget(target);
    const ownerDocument = this.ownerDocument;
    const defaultView = ownerDocument ? ownerDocument.defaultView : null;
    const nodeCtor = defaultView ? defaultView.Node : null;
    if (targetElement && nodeCtor && target instanceof nodeCtor && this.contains(target)) {
      return true;
    }

    const active = globalThis.document ? globalThis.document.activeElement : null;
    return !!active && this.contains(active);
  }

  private _handleCopy(e: ClipboardEvent): void {
    if (!this._engine || !this._doc) return;
    if (!this._isCopyPasteEventInsideEditor(e.target)) return;
    if (this._isShortcutEditingTarget(getClosestCapableTarget(e.target))) return;

    const selectedNodeId = this._selectedNodeId;
    if (!selectedNodeId || selectedNodeId === this._doc.root) return;

    const subtree = extractBlockSubtree(this._doc, selectedNodeId);
    if (!subtree) return;
    if (!writeBlockClipboardData(e.clipboardData, subtree)) return;

    e.preventDefault();
  }

  private _handlePaste(e: ClipboardEvent): void {
    if (!this._engine || !this._doc) return;
    if (!this._isCopyPasteEventInsideEditor(e.target)) return;
    if (this._isShortcutEditingTarget(getClosestCapableTarget(e.target))) return;

    const subtree = readBlockClipboardData(e.clipboardData);
    if (!subtree) return;

    e.preventDefault();
    this._openPasteDialog(subtree);
  }

  private _canDeleteSelectedBlock(): boolean {
    if (!this._engine || !this._doc) return false;
    if (!this._selectedNodeId) return false;
    return this._selectedNodeId !== this._doc.root;
  }

  private _deleteSelectedBlockByShortcut(): boolean {
    if (!this._engine || !this._doc) return false;
    const selectedNodeId = this._selectedNodeId;
    if (!selectedNodeId || selectedNodeId === this._doc.root) return false;

    const nextSelection = this._engine.getNextSelectionAfterRemove(selectedNodeId);
    const result = this._engine.dispatch({ type: 'RemoveNode', nodeId: selectedNodeId });
    if (!result.ok) return false;

    this._engine.selectNode(nextSelection);
    this._focusCanvasBlock(nextSelection);
    return true;
  }

  private _deselectSelectedBlockByShortcut(): boolean {
    if (!this._engine || !this._selectedNodeId) return false;
    this._engine.selectNode(null);
    return true;
  }

  private _buildShortcutRuntimeContext(): EditorShortcutRuntimeContext {
    return {
      save: () => {
        if (this._saveService && this._doc) {
          this._saveService.forceSave(this._doc);
        }
      },
      undo: () => {
        this._engine?.undo();
      },
      redo: () => {
        this._engine?.redo();
      },
      canDeleteSelectedBlock: this._canDeleteSelectedBlock(),
      deleteSelectedBlock: () => this._deleteSelectedBlockByShortcut(),
      canDeselectSelectedBlock: !!this._selectedNodeId,
      deselectSelectedBlock: () => this._deselectSelectedBlockByShortcut(),
      togglePreview: () => {
        const openingPreview = !this._previewOpen;
        this._handleTogglePreview();
        if (openingPreview) {
          this._focusResizeHandleAfterRender();
        }
      },
      duplicateSelectedBlock: () => this._duplicateSelectedNode(),
      openInsertDialog: () => this._openInsertDialog(),
      openShortcutsHelp: () => this._openShortcutsHelp(),
      openDataPreview: () => this._openDataPreview(),
      focusBlocksPanel: () => this._focusPalette(),
      focusStructurePanel: () => this._focusTree(),
      focusInspectorPanel: () => this._focusInspector(),
      focusResizeHandle: () => this._focusResizeHandle(),
      moveSelectedBlockUp: () => this._moveSelectedNode(-1),
      moveSelectedBlockDown: () => this._moveSelectedNode(1),
      toggleCleanMode: () => {
        this._handleToggleCleanMode();
      },
    };
  }

  private _handleKeydown(e: KeyboardEvent): void {
    if (!this._engine) return;

    if (this._dataContractOpen && e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      this._closeDataContract();
      return;
    }

    if (this._pasteDialogOpen) {
      this._handlePasteDialogKeydown(e);
      return;
    }

    // Escape in table cell-mode: clear the cell selection so the inspector
    // returns to table-level controls, without deselecting the table itself.
    // `EditorEngine.selectNode` is a no-op when re-selecting the already
    // selected table, so the normal `selection:change` path can't clear this.
    // Guard: only intercept when the selected node is actually a table to avoid
    // swallowing Escape for unrelated selections if state is ever set spuriously.
    if (
      e.key === 'Escape' &&
      !e.ctrlKey &&
      !e.metaKey &&
      !e.altKey &&
      !e.shiftKey &&
      this._engine.getComponentState('table:cellSelection') != null &&
      this._selectedNodeId != null &&
      this._engine.getNode(this._selectedNodeId)?.type === 'table'
    ) {
      this._engine.setComponentState('table:cellSelection', null);
      e.preventDefault();
      e.stopPropagation();
      return;
    }

    const inInsertDialog = this._insertDialogOpen;
    const activeContexts = inInsertDialog
      ? (['insertDialog'] as const)
      : this._isShortcutEditingTarget(e.target as HTMLElement | null)
        ? (['global'] as const)
        : (['global', 'editor'] as const);
    const runtimeContext: unknown = inInsertDialog
      ? this._buildInsertDialogShortcutRuntimeContext()
      : this._buildShortcutRuntimeContext();

    const resolution = this._shortcutResolver.resolve({
      event: e,
      activeContexts,
      runtimeContext,
    });

    if (resolution.kind === 'none') {
      return;
    }

    // Leader mode chord handling (editor mode only — insert dialog has no chords)
    if (
      !inInsertDialog &&
      resolution.kind === 'chord-cancelled' &&
      (resolution.reason === 'cancel-key' || resolution.reason === 'mismatch')
    ) {
      e.preventDefault();
    } else {
      applyResolutionEventPolicy(e, resolution);
    }

    if (!inInsertDialog && resolution.kind === 'chord-awaiting') {
      this._leaderController.showAwaiting(resolution.state.commandIds);
      return;
    }

    if (!inInsertDialog && resolution.kind === 'chord-cancelled') {
      this._leaderController.handleChordCancelled(resolution.reason);
      return;
    }

    if (resolution.kind !== 'command') {
      return;
    }

    const execution = startShortcutCommandExecution(resolution.match.command, runtimeContext);

    if (!inInsertDialog && resolution.fromChord) {
      this._leaderController.handleCommandExecution(execution.initial, execution.completion);
    } else if (inInsertDialog && execution.initial.status === 'pending') {
      void execution.completion;
    }
  }

  private _applyLeaderState(state: LeaderModeState): void {
    this._leaderVisible = state.visible;
    this._leaderStatus = state.status;
    this._leaderMessage = state.message;
  }

  private _focusCanvasBlock(nodeId: NodeId | null): void {
    if (!nodeId || typeof this.querySelector !== 'function') return;
    const schedule =
      globalThis.requestAnimationFrame ??
      ((callback: FrameRequestCallback) => setTimeout(callback, 0));

    schedule(() => {
      const block = this.querySelector<HTMLElement>(`.canvas-block[data-node-id="${nodeId}"]`);
      block?.focus({ preventScroll: true });
    });
  }

  private _focusPalette(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar');
    if (!sidebar) return false;
    sidebar.focusPalette();
    return true;
  }

  private _openShortcutsHelp(): boolean {
    const toolbar = this.querySelector<EpistolaToolbar>('epistola-toolbar');
    if (!toolbar) return false;
    toolbar.openShortcuts();
    return true;
  }

  private _openDataPreview(): boolean {
    const toolbar = this.querySelector<EpistolaToolbar>('epistola-toolbar');
    if (!toolbar) return false;
    toolbar.openDataPreview();
    return true;
  }

  private _focusTree(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar');
    if (!sidebar) return false;
    sidebar.focusTree();
    return true;
  }

  private _focusInspector(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar');
    if (!sidebar) return false;
    sidebar.focusInspector();
    return true;
  }

  // _focusResizeHandle() for cases where the handle is already mounted (Leader + R)
  // _focusResizeHandleAfterRender() for preview-open flows where the handle is added next render
  private _focusResizeHandle(): boolean {
    const resizeHandle = this.querySelector<HTMLElement>('epistola-resize-handle');
    if (!resizeHandle) return false;
    resizeHandle.focus({ preventScroll: true });
    return true;
  }

  private _focusResizeHandleAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        this._focusResizeHandle();
      });
    });
  }

  private _moveSelectedNode(delta: number): boolean {
    if (!this._engine || !this._doc) return false;
    const nodeId = this._selectedNodeId;
    if (!nodeId || nodeId === this._doc.root) return false;

    const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(nodeId);
    if (!parentSlotId) return false;
    const parentSlot = this._doc.slots[parentSlotId];
    if (!parentSlot) return false;

    const index = parentSlot.children.indexOf(nodeId);
    if (index < 0) return false;
    const nextIndex = index + delta;
    if (nextIndex < 0 || nextIndex >= parentSlot.children.length) return false;

    const result = this._engine.dispatch({
      type: 'MoveNode',
      nodeId,
      targetSlotId: parentSlotId,
      index: nextIndex,
    });
    if (result.ok) {
      this._engine.selectNode(nodeId);
      this._focusCanvasBlock(nodeId);
      return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Paste Dialog
  // ---------------------------------------------------------------------------

  private _openPasteDialog(subtree: BlockSubtree): void {
    this._pasteSubtree = subtree;
    this._pasteDialogOpen = true;
    this._pasteDialogMode = 'placement';
    this._pasteDialogSlotOptions = [];
    this._pasteDialogError = '';
  }

  private _closePasteDialog(): void {
    this._pasteDialogOpen = false;
    this._pasteDialogMode = 'placement';
    this._pasteDialogSlotOptions = [];
    this._pasteDialogError = '';
    this._pasteSubtree = null;
  }

  private _returnToPastePlacement(): void {
    this._pasteDialogMode = 'placement';
    this._pasteDialogSlotOptions = [];
    this._pasteDialogError = '';
  }

  private _handlePasteDialogKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault();
      if (this._pasteDialogMode === 'slot') {
        this._returnToPastePlacement();
      } else {
        this._closePasteDialog();
      }
      return;
    }

    if (!/^[1-9]$/.test(e.key)) {
      return;
    }

    e.preventDefault();
    const index = Number(e.key);

    if (this._pasteDialogMode === 'slot') {
      const slot = this._pasteDialogSlotOptions[index - 1];
      if (!slot) {
        this._pasteDialogError = 'Invalid slot number';
        return;
      }
      this._handlePasteSlotSelection(slot.slotId);
      return;
    }

    if (index === 1) {
      this._handlePastePlacement('after');
      return;
    }
    if (index === 2) {
      this._handlePastePlacement('before');
      return;
    }
    if (index === 3) {
      this._handlePastePlacement('inside');
    }
  }

  private _handlePastePlacement(placement: 'after' | 'before' | 'inside'): void {
    this._pasteDialogError = '';

    if (placement === 'inside') {
      const slotOptions = this._getInsertSlotOptionsForInside();
      if (slotOptions.length === 0) {
        this._pasteDialogError = 'No valid inside slot for selected block';
        return;
      }

      if (slotOptions.length === 1) {
        this._handlePasteSlotSelection(slotOptions[0].slotId);
        return;
      }

      this._pasteDialogMode = 'slot';
      this._pasteDialogSlotOptions = slotOptions;
      return;
    }

    const target = this._getPasteTargetForPlacement(placement);
    if (!target) {
      this._pasteDialogError = `No valid ${placement} target`;
      return;
    }

    this._insertPastedSubtreeAtTarget(target);
  }

  private _handlePasteSlotSelection(slotId: SlotId): void {
    const target = this._buildInsideTargetFromSlot(slotId);
    if (!target) {
      this._pasteDialogError = 'Cannot paste into selected slot';
      return;
    }

    this._insertPastedSubtreeAtTarget(target);
  }

  private _getPasteTargetForPlacement(placement: 'after' | 'before'): InsertTarget | null {
    if (placement === 'after') {
      return this._isDocumentInsertContext()
        ? this._getInsertTargetDocumentEnd()
        : this._getInsertTargetAfterSelected();
    }

    return this._isDocumentInsertContext()
      ? this._getInsertTargetDocumentStart()
      : this._getInsertTargetBeforeSelected();
  }

  private _canPastePlacement(placement: 'after' | 'before'): boolean {
    return this._getPasteTargetForPlacement(placement) !== null;
  }

  private _canPasteInside(): boolean {
    return this._getInsertSlotOptionsForInside().length > 0;
  }

  private _getPasteDialogHint(): string {
    if (this._pasteDialogMode === 'slot') {
      return '1-9=Choose slot  Esc=Back';
    }

    return '1=After  2=Before  3=Inside  Esc=Close';
  }

  private _getPasteDialogContext(): string {
    if (this._pasteDialogMode === 'slot') {
      return `Choose a slot inside ${this._selectedNodeLabel()}`;
    }

    return this._isDocumentInsertContext()
      ? 'Paste into the document'
      : `Paste relative to ${this._selectedNodeLabel()}`;
  }

  private _getPastePlacementDetail(placement: 'after' | 'before' | 'inside'): string {
    if (this._isDocumentInsertContext()) {
      if (placement === 'after') return 'document end';
      if (placement === 'before') return 'document start';
      return 'document body';
    }

    if (placement === 'after') return `after ${this._selectedNodeLabel()}`;
    if (placement === 'before') return `before ${this._selectedNodeLabel()}`;
    return `inside ${this._selectedNodeLabel()}`;
  }

  private _insertPastedSubtreeAtTarget(target: InsertTarget): boolean {
    if (!this._engine || !this._pasteSubtree) return false;

    const cloned = rekeyBlockSubtree(this._pasteSubtree);
    const result = this._engine.dispatch({
      type: 'InsertNode',
      node: cloned.node,
      slots: cloned.slots,
      targetSlotId: target.slotId,
      index: target.index,
      _restoreNodes: cloned.extraNodes,
    });

    if (!result.ok) {
      this._pasteDialogError = result.error;
      return false;
    }

    this._engine.selectNode(cloned.node.id);
    this._focusCanvasBlock(cloned.node.id);
    this._closePasteDialog();
    return true;
  }

  // ---------------------------------------------------------------------------
  // Insert Dialog
  // ---------------------------------------------------------------------------

  private _openInsertDialog(): boolean {
    if (!this._engine || !this._doc) return false;
    this._insertDialogOpen = true;
    this._resetInsertDialogToPlacement();
    return true;
  }

  private _closeInsertDialog(): void {
    this._insertDialogOpen = false;
    this._resetInsertDialogToPlacement();
  }

  private _resetInsertDialogToPlacement(): void {
    this._insertDialogMode = null;
    this._insertDialogSlotOptions = [];
    this._insertDialogBlockOptions = [];
    this._insertDialogQuery = '';
    this._insertDialogHighlight = 0;
    this._insertDialogError = '';
    this._insertTarget = null;
  }

  private _buildInsertDialogShortcutRuntimeContext(): InsertDialogShortcutRuntimeContext {
    return {
      hasPlacementMode: !this._insertDialogMode,
      hasSelectionMode: !!this._insertDialogMode,
      isDocumentContext: this._isDocumentInsertContext(),
      optionCount: this._getInsertDialogOptionCount(),
      highlight: this._insertDialogHighlight,
      closeOrBack: () => {
        if (this._insertDialogMode) {
          this._resetInsertDialogToPlacement();
          return;
        }
        this._closeInsertDialog();
      },
      selectMode: (mode) => {
        this._selectInsertMode(mode);
      },
      setHighlight: (index) => {
        this._insertDialogHighlight = index;
      },
      selectOption: (index) => {
        this._selectInsertDialogOption(index);
      },
      setOptionOutOfRange: () => {
        this._insertDialogError = 'Option out of range';
      },
    };
  }

  private _selectInsertDialogOption(selectedIndex: number): void {
    this._insertDialogHighlight = selectedIndex;

    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      const selectedSlot = this._insertDialogSlotOptions[selectedIndex - 1];
      if (!selectedSlot) {
        this._insertDialogError = 'Invalid slot number';
        return;
      }

      const insideTarget = this._buildInsideTargetFromSlot(selectedSlot.slotId);
      if (!insideTarget) {
        this._insertDialogError = 'Cannot insert into selected slot';
        return;
      }

      this._insertDialogSlotOptions = [];
      this._insertDialogQuery = '';
      this._setInsertDialogTarget(insideTarget);
      return;
    }

    const definition = this._getInsertDialogVisibleBlockOptions()[selectedIndex - 1];
    if (!definition || !this._insertTarget) {
      this._insertDialogError = 'Invalid block number';
      return;
    }

    const ok = this._insertNodeAtTarget(
      definition.type,
      this._insertTarget.slotId,
      this._insertTarget.index,
    );
    if (ok) {
      this._closeInsertDialog();
    } else {
      this._insertDialogError = 'Failed to insert block';
    }
  }

  private _focusInsertDialogSearchAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const searchInput = this.querySelector<HTMLInputElement>('.insert-dialog-search');
        if (!searchInput) return;
        if (document.activeElement !== searchInput) {
          searchInput.focus({ preventScroll: true });
        }
      });
    });
  }

  private _setInsertDialogTarget(target: InsertTarget): void {
    this._insertTarget = target;
    this._insertDialogBlockOptions = this._buildInsertableOptions(target.parentType);
    this._insertDialogHighlight = this._insertDialogBlockOptions.length > 0 ? 1 : 0;
    this._insertDialogError =
      this._insertDialogBlockOptions.length > 0 ? '' : 'No blocks can be inserted at this location';
    this._focusInsertDialogSearchAfterRender();
  }

  private _selectInsertMode(mode: InsertMode): void {
    this._insertDialogMode = mode;
    this._insertTarget = null;
    this._insertDialogSlotOptions = [];
    this._insertDialogBlockOptions = [];
    this._insertDialogQuery = '';
    this._insertDialogHighlight = 0;
    this._insertDialogError = '';

    if (mode === 'start') {
      const target = this._getInsertTargetDocumentStart();
      if (!target) {
        this._insertDialogError = 'No valid document start target';
        return;
      }
      this._setInsertDialogTarget(target);
      return;
    }

    if (mode === 'end') {
      const target = this._getInsertTargetDocumentEnd();
      if (!target) {
        this._insertDialogError = 'No valid document end target';
        return;
      }
      this._setInsertDialogTarget(target);
      return;
    }

    if (mode === 'after') {
      const target = this._getInsertTargetAfterSelected();
      if (!target) {
        this._insertDialogError = 'No valid "after" target';
        return;
      }
      this._setInsertDialogTarget(target);
      return;
    }

    if (mode === 'before') {
      const target = this._getInsertTargetBeforeSelected();
      if (!target) {
        this._insertDialogError = 'No valid "before" target';
        return;
      }
      this._setInsertDialogTarget(target);
      return;
    }

    this._insertDialogSlotOptions = this._getInsertSlotOptionsForInside();
    this._insertDialogHighlight = this._insertDialogSlotOptions.length > 0 ? 1 : 0;
    if (this._insertDialogSlotOptions.length === 0) {
      this._insertDialogError = 'No valid inside slot for selected block';
      return;
    }
    if (this._insertDialogSlotOptions.length === 1) {
      const target = this._buildInsideTargetFromSlot(this._insertDialogSlotOptions[0].slotId);
      if (!target) {
        this._insertDialogError = 'No valid inside slot for selected block';
        return;
      }
      this._insertDialogSlotOptions = [];
      this._setInsertDialogTarget(target);
    }
  }

  private _getInsertDialogOptionCount(): number {
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.length;
    }
    return this._getInsertDialogVisibleBlockOptions().length;
  }

  private _setInsertDialogQuery(query: string): void {
    this._insertDialogQuery = query;
    const optionCount = this._getInsertDialogVisibleBlockOptions().length;
    if (optionCount === 0) {
      this._insertDialogHighlight = 0;
      return;
    }
    if (this._insertDialogHighlight <= 0 || this._insertDialogHighlight > optionCount) {
      this._insertDialogHighlight = 1;
    }
  }

  private _isInsertBlockSelectionStage(): boolean {
    return !!this._insertTarget;
  }

  private _getInsertDialogVisibleBlockOptions(): ComponentDefinition[] {
    const query = this._insertDialogQuery.trim().toLowerCase();
    if (!query) return this._insertDialogBlockOptions;
    return this._insertDialogBlockOptions.filter((def) => {
      return (
        def.label.toLowerCase().includes(query) ||
        def.type.toLowerCase().includes(query) ||
        def.category.toLowerCase().includes(query)
      );
    });
  }

  private _insertNodeAtTarget(type: string, slotId: SlotId, index: number): boolean {
    if (!this._engine || !this._doc) return false;

    const { node, slots, extraNodes } = this._engine.registry.createNode(type);
    const result = this._engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: slotId,
      index,
      _restoreNodes: extraNodes,
    });

    if (result.ok) {
      this._engine.selectNode(node.id);
      this._focusCanvasBlock(node.id);
      return true;
    }
    return false;
  }

  private _buildInsertableOptions(parentType: string): ComponentDefinition[] {
    if (!this._engine) return [];
    return (
      this._engine.registry
        .insertable(this._doc)
        // Root is the single document container and must never be insertable as a block.
        .filter((def) => def.type !== 'root')
        .filter((def) => parentType === 'root' || !isAnchoredPageBlock(def.type))
        .filter((def) => this._engine!.registry.canContain(parentType, def.type))
    );
  }

  private _getInsertTargetAfterSelected(): InsertTarget | null {
    if (!this._engine || !this._doc) return null;

    const selectedNodeId = this._selectedNodeId;
    if (selectedNodeId) {
      const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(selectedNodeId);
      if (parentSlotId) {
        const parentSlot = this._doc.slots[parentSlotId];
        if (!parentSlot) return null;
        const index = parentSlot.children.indexOf(selectedNodeId);
        if (index >= 0) {
          const parentNode = this._doc.nodes[parentSlot.nodeId];
          if (!parentNode) return null;
          return { slotId: parentSlotId, index: index + 1, parentType: parentNode.type };
        }
      }
    }

    const rootNode = this._doc.nodes[this._doc.root];
    if (!rootNode || rootNode.slots.length === 0) return null;
    const bounds = this._getRootInsertBounds();
    if (!bounds) return null;
    return { slotId: bounds.slotId, index: bounds.endIndex, parentType: rootNode.type };
  }

  private _getInsertTargetDocumentStart(): InsertTarget | null {
    if (!this._doc) return null;
    const rootNode = this._doc.nodes[this._doc.root];
    if (!rootNode) return null;
    const bounds = this._getRootInsertBounds();
    if (!bounds) return null;
    return { slotId: bounds.slotId, index: bounds.startIndex, parentType: rootNode.type };
  }

  private _getInsertTargetDocumentEnd(): InsertTarget | null {
    if (!this._doc) return null;
    const rootNode = this._doc.nodes[this._doc.root];
    if (!rootNode) return null;
    const bounds = this._getRootInsertBounds();
    if (!bounds) return null;
    return { slotId: bounds.slotId, index: bounds.endIndex, parentType: rootNode.type };
  }

  private _getInsertTargetBeforeSelected(): InsertTarget | null {
    if (!this._engine || !this._doc) return null;

    const selectedNodeId = this._selectedNodeId;
    if (selectedNodeId) {
      const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(selectedNodeId);
      if (parentSlotId) {
        const parentSlot = this._doc.slots[parentSlotId];
        if (!parentSlot) return null;
        const index = parentSlot.children.indexOf(selectedNodeId);
        if (index >= 0) {
          const parentNode = this._doc.nodes[parentSlot.nodeId];
          if (!parentNode) return null;
          return { slotId: parentSlotId, index, parentType: parentNode.type };
        }
      }
    }

    const rootNode = this._doc.nodes[this._doc.root];
    if (!rootNode || rootNode.slots.length === 0) return null;
    const bounds = this._getRootInsertBounds();
    if (!bounds) return null;
    return { slotId: bounds.slotId, index: bounds.startIndex, parentType: rootNode.type };
  }

  private _getRootInsertBounds(): { slotId: SlotId; startIndex: number; endIndex: number } | null {
    if (!this._doc) return null;

    const rootNode = this._doc.nodes[this._doc.root];
    if (!rootNode || rootNode.slots.length === 0) return null;

    const slotId = rootNode.slots[0];
    const rootSlot = this._doc.slots[slotId];
    if (!rootSlot) return null;

    const headerIndex = rootSlot.children.findIndex(
      (nodeId) => this._doc?.nodes[nodeId]?.type === PAGE_HEADER_TYPE,
    );
    const footerIndex = rootSlot.children.findIndex(
      (nodeId) => this._doc?.nodes[nodeId]?.type === PAGE_FOOTER_TYPE,
    );
    const startIndex = headerIndex >= 0 ? headerIndex + 1 : 0;
    const endIndex = footerIndex >= 0 ? footerIndex : rootSlot.children.length;

    return {
      slotId,
      startIndex,
      endIndex: Math.max(startIndex, endIndex),
    };
  }

  private _getInsertSlotOptionsForInside(): InsertSlotOption[] {
    if (!this._engine || !this._doc) return [];

    const selectedNodeId = this._selectedNodeId ?? this._doc.root;
    const selectedNode = this._doc.nodes[selectedNodeId];
    if (!selectedNode) return [];

    const insertable = this._buildInsertableOptions(selectedNode.type);
    if (insertable.length === 0) return [];

    return selectedNode.slots
      .map((slotId, index) => {
        const slot = this._doc?.slots[slotId];
        if (!slot) return null;
        const slotName = slot.name && slot.name.trim().length > 0 ? slot.name : `slot-${index + 1}`;
        return { slotId, label: `${slotName} (${slot.children.length} items)` };
      })
      .filter((value): value is InsertSlotOption => value !== null);
  }

  private _buildInsideTargetFromSlot(slotId: SlotId): InsertTarget | null {
    if (!this._engine || !this._doc) return null;
    const slot = this._doc.slots[slotId];
    if (!slot) return null;
    const ownerNode = this._doc.nodes[slot.nodeId];
    if (!ownerNode) return null;
    return { slotId, index: slot.children.length, parentType: ownerNode.type };
  }

  private _insertDialogKeyLabel(key: string): string {
    if (key === 'escape') return 'Esc';
    if (key === 'arrowup') return '\u2191';
    if (key === 'arrowdown') return '\u2193';
    if (key === 'enter') return 'Enter';
    if (key.length === 1) return key.toUpperCase();
    return key;
  }

  private _insertDialogQuickSelectLabel(): string {
    const quickSelectKeys = INSERT_DIALOG_SHORTCUTS.navigation.quickSelect as readonly string[];
    if (quickSelectKeys.length === 0) return '';

    const singleDigitSequence = quickSelectKeys.every((value) => /^[0-9]$/.test(value));
    if (!singleDigitSequence) {
      return quickSelectKeys.join('/');
    }

    const first = Number(quickSelectKeys[0]);
    const contiguous = quickSelectKeys.every((value, index) => Number(value) === first + index);
    if (!contiguous) {
      return quickSelectKeys.join('/');
    }

    const last = quickSelectKeys[quickSelectKeys.length - 1];
    return `${quickSelectKeys[0]}-${last}`;
  }

  private _getInsertDialogPrompt(): string {
    const placement = INSERT_DIALOG_SHORTCUTS.placement;
    const navigation = INSERT_DIALOG_SHORTCUTS.navigation;
    const closeLabel = this._insertDialogKeyLabel(navigation.close);
    const confirmLabel = this._insertDialogKeyLabel(navigation.confirm);
    const previousLabel = this._insertDialogKeyLabel(navigation.previous);
    const nextLabel = this._insertDialogKeyLabel(navigation.next);
    const quickSelectLabel = this._insertDialogQuickSelectLabel();

    const documentPlacementPrompt = `${placement.document.start.toUpperCase()}=Start  ${placement.document.end.toUpperCase()}=End  ${closeLabel}=Close`;
    const selectedPlacementPrompt = `${placement.selected.after.toUpperCase()}=After  ${placement.selected.before.toUpperCase()}=Before  ${placement.selected.inside.toUpperCase()}=Inside  ${closeLabel}=Close`;

    if (!this._insertDialogMode) {
      return this._isDocumentInsertContext() ? documentPlacementPrompt : selectedPlacementPrompt;
    }
    if (
      !this._insertTarget &&
      this._insertDialogSlotOptions.length === 0 &&
      this._insertDialogBlockOptions.length === 0
    ) {
      return this._isDocumentInsertContext()
        ? `No valid target. Press ${placement.document.start.toUpperCase()}/${placement.document.end.toUpperCase()} or ${closeLabel}`
        : `No valid target. Press ${placement.selected.after.toUpperCase()}/${placement.selected.before.toUpperCase()}/${placement.selected.inside.toUpperCase()} or ${closeLabel}`;
    }

    const quickSelectPrompt = quickSelectLabel ? quickSelectLabel : 'Quick-select';
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return `${quickSelectPrompt}=Choose slot  ${previousLabel}/${nextLabel}=Navigate  ${confirmLabel}=Confirm  ${closeLabel}=Close`;
    }
    return `Type=Search  ${quickSelectPrompt}=Quick insert  ${previousLabel}/${nextLabel}=Navigate  ${confirmLabel}=Insert  ${closeLabel}=Close`;
  }

  private _getInsertDialogContext(): string {
    if (!this._insertDialogMode) {
      return this._isDocumentInsertContext()
        ? 'Target: document'
        : `Target: choose placement around ${this._selectedNodeLabel()}`;
    }

    if (this._insertDialogMode === 'start') return 'Target: document start';
    if (this._insertDialogMode === 'end') return 'Target: document end';

    if (this._insertDialogMode === 'after') {
      return `Target: after ${this._selectedNodeLabel()}`;
    }
    if (this._insertDialogMode === 'before') {
      return `Target: before ${this._selectedNodeLabel()}`;
    }
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return `Target: inside ${this._selectedNodeLabel()}`;
    }
    if (this._insertTarget) {
      const slot = this._doc?.slots[this._insertTarget.slotId];
      const slotName = slot?.name?.trim() ? slot.name : 'selected slot';
      return `Target: inside ${slotName}`;
    }
    return 'Target: inside selected block';
  }

  private _selectedNodeLabel(): string {
    if (!this._doc || !this._engine || !this._selectedNodeId) return 'document';
    const node = this._doc.nodes[this._selectedNodeId];
    if (!node) return 'document';
    return this._engine.registry.get(node.type)?.label ?? node.type;
  }

  private _isDocumentInsertContext(): boolean {
    if (!this._doc) return true;
    if (this._selectedNodeId === null || this._selectedNodeId === this._doc.root) return true;
    return !this._doc.nodes[this._selectedNodeId];
  }

  private _getInsertDialogTotalCount(): number {
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.length;
    }
    return this._insertDialogBlockOptions.length;
  }

  private _getInsertDialogRangeText(visibleCount: number, totalCount: number): string {
    if (totalCount === 0 || visibleCount === 0) return '';
    return `Showing 1-${visibleCount} of ${totalCount}`;
  }

  private _getInsertDialogRows(): Array<{ index: number; label: string; detail: string }> {
    const quickSelectCount = INSERT_DIALOG_SHORTCUTS.navigation.quickSelect.length;
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.slice(0, quickSelectCount).map((slot, i) => ({
        index: i + 1,
        label: slot.label,
        detail: 'slot',
      }));
    }

    return this._getInsertDialogVisibleBlockOptions().map((def, i) => ({
      index: i + 1,
      label: def.label,
      detail: def.category,
    }));
  }

  // ---------------------------------------------------------------------------
  // Block Operations
  // ---------------------------------------------------------------------------

  private _duplicateSelectedNode(): boolean {
    if (!this._engine || !this._doc) return false;
    const nodeId = this._selectedNodeId;
    if (!nodeId || nodeId === this._doc.root) return false;

    const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(nodeId);
    if (!parentSlotId) return false;
    const parentSlot = this._doc.slots[parentSlotId];
    if (!parentSlot) return false;

    const index = parentSlot.children.indexOf(nodeId);
    if (index < 0) return false;

    const source = extractBlockSubtree(this._doc, nodeId);
    if (!source) return false;

    const clone = rekeyBlockSubtree(source);
    if (!clone) return false;

    const result = this._engine.dispatch({
      type: 'InsertNode',
      node: clone.node,
      slots: clone.slots,
      targetSlotId: parentSlotId,
      index: index + 1,
      _restoreNodes: clone.extraNodes,
    });

    if (result.ok) {
      this._engine.selectNode(clone.node.id);
      this._focusCanvasBlock(clone.node.id);
      return true;
    }
    return false;
  }

  private _handleTogglePreview = () => {
    this._previewOpen = !this._previewOpen;
    try {
      localStorage.setItem(EpistolaEditor.PREVIEW_OPEN_KEY, String(this._previewOpen));
    } catch {
      // localStorage may be unavailable
    }
  };

  private _handleToggleCleanMode = () => {
    this._cleanMode = !this._cleanMode;
    try {
      localStorage.setItem(EpistolaEditor.CLEAN_MODE_KEY, String(this._cleanMode));
    } catch {
      // localStorage may be unavailable
    }
  };

  private _handleForceSave = () => {
    if (this._saveService && this._doc) {
      this._saveService.forceSave(this._doc);
    }
  };

  private _handleOpenDataContract = (): void => {
    if (!this.dataContractOptions) return;
    this._dataContractOpen = true;
    this.updateComplete
      .then(() => this._mountDataContractEditor())
      .catch(() => {
        // no-op
      });
  };

  private _closeDataContract = (): void => {
    this._dataContractOpen = false;
  };

  private _mountDataContractEditor(): void {
    if (this._dataContractMounted || !this.dataContractOptions) return;
    const host = this.querySelector<HTMLElement>('.editor-data-contract-host');
    if (!host) return;

    const editor: EpistolaDataContractEditor = document.createElement(
      'epistola-data-contract-editor',
    );
    editor.style.display = 'block';
    editor.init(
      this.dataContractOptions.initialSchema,
      this.dataContractOptions.initialExamples,
      this._createDataContractCallbacks(),
      this.dataContractOptions.readonly ?? false,
    );

    host.innerHTML = '';
    host.appendChild(editor);
    this._dataContractMounted = true;
  }

  private _createDataContractCallbacks(): SaveCallbacks {
    const options = this.dataContractOptions;
    if (!options) return {};
    const callbacks = options.callbacks;
    const onSaveSchemaCb = callbacks.onSaveSchema;
    const onSaveDataExamplesCb = callbacks.onSaveDataExamples;
    const onUpdateDataExampleCb = callbacks.onUpdateDataExample;
    const onDeleteDataExampleCb = callbacks.onDeleteDataExample;

    return {
      onSaveSchema: onSaveSchemaCb
        ? async (schema, forceUpdate, dataExamples) =>
            onSaveSchemaCb(schema, forceUpdate, dataExamples).then((result) => {
              if (result.success) {
                const dataContext: { dataModel?: object | null; dataExamples?: object[] } = {
                  dataModel: schema,
                };
                if (dataExamples) {
                  dataContext.dataExamples = dataExamples;
                }
                if (this._engine) {
                  this._engine.setDataContext(dataContext);
                }
              }
              return result;
            })
        : undefined,
      onSaveDataExamples: onSaveDataExamplesCb
        ? async (examples) =>
            onSaveDataExamplesCb(examples).then((result) => {
              if (result.success) {
                if (this._engine) {
                  this._engine.setDataExamples(examples);
                }
              }
              return result;
            })
        : undefined,
      onUpdateDataExample: onUpdateDataExampleCb
        ? async (exampleId, updates, forceUpdate) =>
            onUpdateDataExampleCb(exampleId, updates, forceUpdate).then((result) => {
              if (result.success && result.example) {
                const updatedExample = result.example;
                const engine = this._engine;
                if (engine) {
                  const sourceExamples = engine.dataExamples;
                  const examples = isDataExampleArray(sourceExamples) ? sourceExamples : [];
                  const nextExamples = examples.map((example) =>
                    example.id === exampleId ? updatedExample : example,
                  );
                  engine.setDataExamples(nextExamples);
                }
              }
              return result;
            })
        : undefined,
      onDeleteDataExample: onDeleteDataExampleCb
        ? async (exampleId) =>
            onDeleteDataExampleCb(exampleId).then((result) => {
              if (result.success) {
                const engine = this._engine;
                if (engine) {
                  const sourceExamples = engine.dataExamples;
                  const examples = isDataExampleArray(sourceExamples) ? sourceExamples : [];
                  engine.setDataExamples(examples.filter((example) => example.id !== exampleId));
                }
              }
              return result;
            })
        : undefined,
    };
  }

  /**
   * Warn users about unsaved changes when closing/navigating away.
   */
  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this._saveService?.isDirtyOrSaving) {
      e.preventDefault();
    }
  }

  private _handlePasteDialogBackdropClick = (): void => {
    this._closePasteDialog();
  };

  private _buildPasteSlotClickHandler(slotId: SlotId): () => void {
    return (): void => {
      this._handlePasteSlotSelection(slotId);
    };
  }

  private _renderPasteDialog(): unknown {
    if (!this._pasteDialogOpen) return nothing;

    return html`
      <div class="paste-dialog-backdrop" @click=${this._handlePasteDialogBackdropClick}></div>
      <div class="paste-dialog" data-testid="paste-dialog" role="dialog" aria-label="Paste block">
        <div class="paste-dialog-title">Paste Block</div>
        <div class="paste-dialog-hint">${this._getPasteDialogHint()}</div>
        <div class="paste-dialog-context">${this._getPasteDialogContext()}</div>

        ${this._pasteDialogError
          ? html`<div class="paste-dialog-error">${this._pasteDialogError}</div>`
          : nothing}
        ${this._pasteDialogMode === 'slot'
          ? html`
              <div class="paste-dialog-slot-list">
                ${this._pasteDialogSlotOptions.map(
                  (slot, index) => html`
                    <button
                      class="paste-dialog-slot-option"
                      data-testid=${`paste-slot-${index + 1}`}
                      type="button"
                      ?autofocus=${index === 0}
                      @click=${this._buildPasteSlotClickHandler(slot.slotId)}
                    >
                      <span class="paste-dialog-slot-index">${index + 1}</span>
                      <span class="paste-dialog-slot-label">${slot.label}</span>
                    </button>
                  `,
                )}
              </div>
            `
          : html`
              <div class="paste-dialog-actions">
                <button
                  class="paste-dialog-action"
                  data-testid="paste-after"
                  type="button"
                  ?disabled=${!this._canPastePlacement('after')}
                  autofocus
                  @click=${() => this._handlePastePlacement('after')}
                >
                  <span class="paste-dialog-action-index">1</span>
                  <span class="paste-dialog-action-label">After</span>
                  <span class="paste-dialog-action-detail"
                    >${this._getPastePlacementDetail('after')}</span
                  >
                </button>

                <button
                  class="paste-dialog-action"
                  data-testid="paste-before"
                  type="button"
                  ?disabled=${!this._canPastePlacement('before')}
                  @click=${() => this._handlePastePlacement('before')}
                >
                  <span class="paste-dialog-action-index">2</span>
                  <span class="paste-dialog-action-label">Before</span>
                  <span class="paste-dialog-action-detail"
                    >${this._getPastePlacementDetail('before')}</span
                  >
                </button>

                <button
                  class="paste-dialog-action"
                  data-testid="paste-inside"
                  type="button"
                  ?disabled=${!this._canPasteInside()}
                  @click=${() => this._handlePastePlacement('inside')}
                >
                  <span class="paste-dialog-action-index">3</span>
                  <span class="paste-dialog-action-label">Inside</span>
                  <span class="paste-dialog-action-detail"
                    >${this._getPastePlacementDetail('inside')}</span
                  >
                </button>
              </div>
            `}
      </div>
    `;
  }

  private _renderDataContractModal(): unknown {
    if (!this.dataContractOptions) return nothing;

    return html`
      <div class="editor-data-contract-modal ${this._dataContractOpen ? 'is-open' : ''}">
        <div class="editor-data-contract-backdrop" @click=${this._closeDataContract}></div>
        <div
          class="editor-data-contract-dialog"
          data-testid="data-contract-modal"
          role="dialog"
          aria-modal=${this._dataContractOpen ? 'true' : 'false'}
          aria-labelledby="editor-data-contract-title"
          @click=${(event: Event) => event.stopPropagation()}
        >
          <div class="editor-data-contract-header">
            <div>
              <div id="editor-data-contract-title" class="editor-data-contract-title">
                Data Contract
              </div>
              <div class="editor-data-contract-subtitle">Edit schema fields and example data.</div>
            </div>
            <button
              type="button"
              class="editor-data-contract-close"
              @click=${this._closeDataContract}
              aria-label="Close data contract"
            >
              ${icon('x', 16)}
            </button>
          </div>
          <div class="editor-data-contract-body">
            <div class="editor-data-contract-host"></div>
          </div>
        </div>
      </div>
    `;
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="editor-empty">No template loaded</div>`;
    }

    const hasPreview = !!this.fetchPreview;
    const hasSave = !!this.onSave;
    const showPreview = hasPreview && this._previewOpen;
    const previewWidth = showPreview ? EpistolaResizeHandle.getPersistedWidth() : undefined;
    const leaderClasses = [
      'leader-hint',
      this._leaderVisible ? 'is-visible' : '',
      this._leaderStatus !== 'idle' ? `is-${this._leaderStatus}` : '',
    ]
      .filter(Boolean)
      .join(' ');
    const insertRows = this._getInsertDialogRows();
    const insertPrompt = this._getInsertDialogPrompt();
    const insertContext = this._getInsertDialogContext();
    const insertTotalCount = this._getInsertDialogTotalCount();
    const insertRangeText = this._getInsertDialogRangeText(insertRows.length, insertTotalCount);
    const insertStageKey = [
      this._insertDialogMode ?? 'placement',
      this._insertTarget ? 'target' : 'slot',
      String(this._insertDialogSlotOptions.length),
      String(this._insertDialogBlockOptions.length),
    ].join(':');

    return html`
      <div class="epistola-editor">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
          .previewOpen=${this._previewOpen}
          .cleanMode=${this._cleanMode}
          .hasPreview=${hasPreview}
          .hasSave=${hasSave}
          .hasDataContract=${!!this.dataContractOptions}
          .saveState=${this._saveState}
          .pluginActions=${this._pluginToolbarActions}
        ></epistola-toolbar>

        <!-- Main layout: sidebar | canvas | [resize-handle | preview] -->
        <div
          class="editor-main"
          style=${showPreview ? `--ep-preview-width: ${previewWidth}px` : ''}
        >
          <epistola-sidebar
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
            .pluginTabs=${this._pluginSidebarTabs}
            .pluginContext=${this._pluginContext}
          ></epistola-sidebar>

          <epistola-canvas
            .engine=${this._engine}
            .doc=${this._doc}
            .selectedNodeId=${this._selectedNodeId}
            .cleanMode=${this._cleanMode}
          ></epistola-canvas>

          ${showPreview
            ? html`
                <epistola-resize-handle></epistola-resize-handle>
                <epistola-preview
                  .engine=${this._engine}
                  .fetchPreview=${this.fetchPreview}
                ></epistola-preview>
              `
            : nothing}
        </div>
        <div class=${leaderClasses} data-testid="leader-hint" role="status" aria-live="polite">
          <span class="leader-dot" aria-hidden="true"></span>
          <span class="leader-text" data-testid="leader-message">${this._leaderMessage}</span>
        </div>

        ${this._insertDialogOpen
          ? html`
              <div class="insert-dialog-backdrop" @click=${this._closeInsertDialog}></div>
              <div
                class="insert-dialog"
                data-testid="insert-dialog"
                role="dialog"
                aria-label="Insert block"
              >
                <div class="insert-dialog-title">Insert Block</div>
                ${keyed(
                  insertStageKey,
                  html`
                    <div class="insert-dialog-stage">
                      <div class="insert-dialog-hint">${insertPrompt}</div>
                      <div class="insert-dialog-context">${insertContext}</div>

                      ${this._insertDialogError
                        ? html`<div class="insert-dialog-error">${this._insertDialogError}</div>`
                        : nothing}
                      ${this._isInsertBlockSelectionStage()
                        ? html`
                            <input
                              class="insert-dialog-search"
                              type="text"
                              autofocus
                              .value=${this._insertDialogQuery}
                              @input=${(event: Event) => {
                                const target = event.target as HTMLInputElement;
                                this._setInsertDialogQuery(target.value);
                              }}
                              placeholder="Search blocks"
                              spellcheck="false"
                              autocomplete="off"
                              aria-label="Search blocks"
                            />
                          `
                        : nothing}

                      <div class="insert-dialog-list">
                        ${insertRows.length === 0
                          ? html`<div class="insert-dialog-empty">No matching blocks</div>`
                          : insertRows.map(
                              (row) => html`
                                <div
                                  class="insert-dialog-row ${row.index ===
                                  this._insertDialogHighlight
                                    ? 'is-active'
                                    : ''}"
                                >
                                  <span class="insert-dialog-index">${row.index}</span>
                                  <span class="insert-dialog-label">${row.label}</span>
                                  <span class="insert-dialog-detail">${row.detail}</span>
                                </div>
                              `,
                            )}
                      </div>

                      ${insertRangeText
                        ? html`<div class="insert-dialog-range">${insertRangeText}</div>`
                        : nothing}
                    </div>
                  `,
                )}
              </div>
            `
          : nothing}
        ${this._renderPasteDialog()} ${this._renderDataContractModal()}
      </div>
    `;
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-editor': EpistolaEditor;
  }
}
