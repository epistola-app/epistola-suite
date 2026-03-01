import { LitElement, html, nothing } from 'lit'
import { customElement, property, state } from 'lit/decorators.js'
import { keyed } from 'lit/directives/keyed.js'
import type { TemplateDocument, NodeId, SlotId, Node, Slot } from '../types/index.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createDefaultRegistry } from '../engine/registry.js'
import type { ComponentRegistry, ComponentDefinition } from '../engine/registry.js'
import type { FetchPreviewFn } from './preview-service.js'
import { SaveService, type SaveState, type SaveFn } from './save-service.js'
import { EpistolaResizeHandle } from './EpistolaResizeHandle.js'
import type { EditorPlugin, PluginContext, PluginDisposeFn, SidebarTabContribution, ToolbarAction } from '../plugins/types.js'
import type { EpistolaSidebar } from './EpistolaSidebar.js'
import type { EpistolaToolbar } from './EpistolaToolbar.js'
import { nanoid } from 'nanoid'
import { EDITOR_SHORTCUTS_CONFIG } from '../shortcuts-config.js'
import {
  getAllLeaderIdleTokens,
  getEditorShortcutRegistry,
  getLeaderIdleTokensForCommandIds,
  type EditorShortcutRuntimeContext,
} from '../shortcuts/editor-runtime.js'
import {
  getInsertDialogShortcutRegistry,
  INSERT_DIALOG_KEYS,
  type InsertDialogShortcutRuntimeContext,
} from '../shortcuts/insert-dialog-runtime.js'
import {
  ShortcutResolver,
  applyResolutionEventPolicy,
  startShortcutCommandExecution,
} from '../shortcuts/resolver.js'
import { LeaderModeController, type LeaderModeState } from '../shortcuts/leader-controller.js'
import { validateCoreShortcutRegistriesOnStartup } from '../shortcuts/startup-validation.js'

import './EpistolaSidebar.js'
import './EpistolaCanvas.js'
import './EpistolaToolbar.js'
import './EpistolaPreview.js'
import './EpistolaResizeHandle.js'

type InsertMode = 'after' | 'before' | 'inside' | 'start' | 'end'

interface InsertSlotOption {
  slotId: SlotId
  label: string
}

interface InsertTarget {
  slotId: SlotId
  index: number
  parentType: string
}

const INSERT_DIALOG_SHORTCUTS = INSERT_DIALOG_KEYS

const EDITABLE_TARGET_SELECTOR = 'input, textarea, select, [contenteditable="true"], .ProseMirror'

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
    return this
  }

  private _engine?: EditorEngine
  private _unsubEngine?: () => void
  private _unsubSelection?: () => void
  private _saveService?: SaveService
  private _pluginDisposers: PluginDisposeFn[] = []
  private _onKeydown = this._handleKeydown.bind(this)
  private _onBeforeUnload = this._handleBeforeUnload.bind(this)
  private readonly _shortcutResolver = new ShortcutResolver<EditorShortcutRuntimeContext>(
    getEditorShortcutRegistry(),
    {
      chord: {
        timeoutMs: EDITOR_SHORTCUTS_CONFIG.leader.timeout.idleHideMs,
        cancelKeys: ['escape'],
      },
    },
  )
  private readonly _insertDialogShortcutResolver = new ShortcutResolver<InsertDialogShortcutRuntimeContext>(
    getInsertDialogShortcutRegistry(),
    {
      fallbackContexts: ['insertDialog'],
    },
  )
  private readonly _leaderController = new LeaderModeController({
    timing: EDITOR_SHORTCUTS_CONFIG.leader.timeout,
    getIdleTokens: getLeaderIdleTokensForCommandIds,
    fallbackTokens: getAllLeaderIdleTokens(),
    onStateChange: (state) => this._applyLeaderState(state),
    cancelActiveChord: () => this._shortcutResolver.cancelActiveChord(),
    blurEditingTarget: () => {
      const active = document.activeElement as HTMLElement | null
      if (active && this.contains(active) && active.matches(EDITABLE_TARGET_SELECTOR)) {
        active.blur()
      }
    },
  })

  @property({ attribute: false }) fetchPreview?: FetchPreviewFn
  @property({ attribute: false }) onSave?: SaveFn
  @property({ attribute: false }) plugins?: EditorPlugin[]
  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null
  @state() private _previewOpen = false
  @state() private _saveState: SaveState = { status: 'idle' }
  @state() private _leaderVisible = false
  @state() private _leaderStatus: 'idle' | 'success' | 'error' = 'idle'
  @state() private _leaderMessage = ''
  @state() private _insertDialogOpen = false
  @state() private _insertDialogMode: InsertMode | null = null
  @state() private _insertDialogSlotOptions: InsertSlotOption[] = []
  @state() private _insertDialogBlockOptions: ComponentDefinition[] = []
  @state() private _insertDialogQuery = ''
  @state() private _insertDialogHighlight = 0
  @state() private _insertDialogError = ''

  private _insertTarget: InsertTarget | null = null

  private static readonly PREVIEW_OPEN_KEY = 'ep:preview-open'

  get engine(): EditorEngine | undefined {
    return this._engine
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
    this._unsubEngine?.()
    this._unsubSelection?.()
    this._saveService?.dispose()
    this._saveService = undefined

    const reg = registry ?? createDefaultRegistry()
    this._engine = new EditorEngine(doc, reg, {
      dataModel: options?.dataModel,
      dataExamples: options?.dataExamples,
    })
    this._doc = this._engine.doc

    this._unsubEngine = this._engine.events.on('doc:change', ({ doc }) => {
      this._doc = doc
      // Notify save service of changes
      if (this._saveService) {
        this._saveService.markDirty()
        this._saveService.scheduleAutoSave(doc)
      }
    })

    this._unsubSelection = this._engine.events.on('selection:change', ({ nodeId }) => {
      this._selectedNodeId = nodeId
      // Clear component state when selecting a different node
      this._engine?.setComponentState('table:cellSelection', null)
    })

    // Create save service if onSave callback is provided
    if (this.onSave) {
      this._saveService = new SaveService(this.onSave, (state) => {
        this._saveState = state
      })
    }

    // Initialize plugins
    this._disposePlugins()
    if (this.plugins) {
      const context: PluginContext = {
        engine: this._engine,
        doc: this._doc!,
        selectedNodeId: this._selectedNodeId,
      }
      this._pluginDisposers = this.plugins.map((p) => p.init(context))
    }
  }

  private _disposePlugins(): void {
    this._pluginDisposers.forEach((dispose) => dispose())
    this._pluginDisposers = []
  }

  /** Sidebar tab contributions from plugins. */
  private get _pluginSidebarTabs(): SidebarTabContribution[] {
    if (!this.plugins) return []
    return this.plugins.filter((p) => p.sidebarTab).map((p) => p.sidebarTab!)
  }

  /** Toolbar action contributions from plugins. */
  private get _pluginToolbarActions(): ToolbarAction[] {
    if (!this.plugins) return []
    return this.plugins.flatMap((p) => p.toolbarActions ?? [])
  }

  /** Current plugin context for reactive sidebar tab rendering. */
  private get _pluginContext(): PluginContext | undefined {
    if (!this._engine || !this._doc) return undefined
    return {
      engine: this._engine,
      doc: this._doc,
      selectedNodeId: this._selectedNodeId,
    }
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  override connectedCallback(): void {
    super.connectedCallback()
    validateCoreShortcutRegistriesOnStartup()
    window.addEventListener('keydown', this._onKeydown)
    this.addEventListener('toggle-preview', this._handleTogglePreview)
    this.addEventListener('force-save', this._handleForceSave)
    window.addEventListener('beforeunload', this._onBeforeUnload)

    // Restore preview open state from localStorage
    try {
      this._previewOpen = localStorage.getItem(EpistolaEditor.PREVIEW_OPEN_KEY) === 'true'
    } catch {
      // localStorage may be unavailable
    }
  }

  override disconnectedCallback(): void {
    window.removeEventListener('keydown', this._onKeydown)
    this.removeEventListener('toggle-preview', this._handleTogglePreview)
    this.removeEventListener('force-save', this._handleForceSave)
    window.removeEventListener('beforeunload', this._onBeforeUnload)
    super.disconnectedCallback()
    this._disposePlugins()
    this._unsubEngine?.()
    this._unsubSelection?.()
    this._saveService?.dispose()
    this._shortcutResolver.cancelActiveChord()
    this._insertDialogShortcutResolver.cancelActiveChord()
    this._leaderController.dispose()
  }

  // ---------------------------------------------------------------------------
  // Keyboard Handling
  // ---------------------------------------------------------------------------

  private _isShortcutEditingTarget(target: HTMLElement | null): boolean {
    return !!target?.closest(EDITABLE_TARGET_SELECTOR)
  }

  private _canDeleteSelectedBlock(): boolean {
    if (!this._engine || !this._doc) return false
    if (!this._selectedNodeId) return false
    return this._selectedNodeId !== this._doc.root
  }

  private _deleteSelectedBlockByShortcut(): boolean {
    if (!this._engine || !this._doc) return false
    const selectedNodeId = this._selectedNodeId
    if (!selectedNodeId || selectedNodeId === this._doc.root) return false

    const nextSelection = this._engine.getNextSelectionAfterRemove(selectedNodeId)
    const result = this._engine.dispatch({ type: 'RemoveNode', nodeId: selectedNodeId })
    if (!result.ok) return false

    this._engine.selectNode(nextSelection)
    this._focusCanvasBlock(nextSelection)
    return true
  }

  private _deselectSelectedBlockByShortcut(): boolean {
    if (!this._engine || !this._selectedNodeId) return false
    this._engine.selectNode(null)
    return true
  }

  private _buildShortcutRuntimeContext(): EditorShortcutRuntimeContext {
    return {
      save: () => {
        if (this._saveService && this._doc) {
          this._saveService.forceSave(this._doc)
        }
      },
      undo: () => {
        this._engine?.undo()
      },
      redo: () => {
        this._engine?.redo()
      },
      canDeleteSelectedBlock: this._canDeleteSelectedBlock(),
      deleteSelectedBlock: () => this._deleteSelectedBlockByShortcut(),
      canDeselectSelectedBlock: !!this._selectedNodeId,
      deselectSelectedBlock: () => this._deselectSelectedBlockByShortcut(),
      togglePreview: () => {
        const openingPreview = !this._previewOpen
        this._handleTogglePreview()
        if (openingPreview) {
          this._focusResizeHandleAfterRender()
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
    }
  }

  private _handleKeydown(e: KeyboardEvent): void {
    if (!this._engine) return
    if (this._insertDialogOpen) {
      this._handleInsertDialogKeydown(e)
      return
    }

    const target = e.target as HTMLElement | null
    const activeContexts = this._isShortcutEditingTarget(target)
      ? ['global'] as const
      : ['global', 'editor'] as const
    const runtimeContext = this._buildShortcutRuntimeContext()

    const resolution = this._shortcutResolver.resolve({
      event: e,
      activeContexts,
      runtimeContext,
    })

    if (resolution.kind === 'none') {
      return
    }

    if (resolution.kind === 'chord-cancelled' && (resolution.reason === 'cancel-key' || resolution.reason === 'mismatch')) {
      e.preventDefault()
    } else {
      applyResolutionEventPolicy(e, resolution)
    }

    if (resolution.kind === 'chord-awaiting') {
      this._leaderController.showAwaiting(resolution.state.commandIds)
      return
    }

    if (resolution.kind === 'chord-cancelled') {
      this._leaderController.handleChordCancelled(resolution.reason)
      return
    }

    const execution = startShortcutCommandExecution(resolution.match.command, runtimeContext)
    if (!resolution.fromChord) {
      return
    }

    this._leaderController.handleCommandExecution(execution.initial, execution.completion)
  }

  private _applyLeaderState(state: LeaderModeState): void {
    this._leaderVisible = state.visible
    this._leaderStatus = state.status
    this._leaderMessage = state.message
  }

  private _focusCanvasBlock(nodeId: NodeId | null): void {
    if (!nodeId) return
    requestAnimationFrame(() => {
      const block = this.querySelector<HTMLElement>(`.canvas-block[data-node-id="${nodeId}"]`)
      block?.focus({ preventScroll: true })
    })
  }

  private _focusPalette(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar')
    if (!sidebar) return false
    sidebar.focusPalette()
    return true
  }

  private _openShortcutsHelp(): boolean {
    const toolbar = this.querySelector<EpistolaToolbar>('epistola-toolbar')
    if (!toolbar) return false
    toolbar.openShortcuts()
    return true
  }

  private _openDataPreview(): boolean {
    const toolbar = this.querySelector<EpistolaToolbar>('epistola-toolbar')
    if (!toolbar) return false
    toolbar.openDataPreview()
    return true
  }

  private _focusTree(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar')
    if (!sidebar) return false
    sidebar.focusTree()
    return true
  }

  private _focusInspector(): boolean {
    const sidebar = this.querySelector<EpistolaSidebar>('epistola-sidebar')
    if (!sidebar) return false
    sidebar.focusInspector()
    return true
  }

  // _focusResizeHandle() for cases where the handle is already mounted (Leader + R)
  // _focusResizeHandleAfterRender() for preview-open flows where the handle is added next render
  private _focusResizeHandle(): boolean {
    const resizeHandle = this.querySelector<HTMLElement>('epistola-resize-handle')
    if (!resizeHandle) return false
    resizeHandle.focus({ preventScroll: true })
    return true
  }

  private _focusResizeHandleAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        this._focusResizeHandle()
      })
    })
  }

  private _moveSelectedNode(delta: number): boolean {
    if (!this._engine || !this._doc) return false
    const nodeId = this._selectedNodeId
    if (!nodeId || nodeId === this._doc.root) return false

    const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(nodeId)
    if (!parentSlotId) return false
    const parentSlot = this._doc.slots[parentSlotId]
    if (!parentSlot) return false

    const index = parentSlot.children.indexOf(nodeId)
    if (index < 0) return false
    const nextIndex = index + delta
    if (nextIndex < 0 || nextIndex >= parentSlot.children.length) return false

    const result = this._engine.dispatch({
      type: 'MoveNode',
      nodeId,
      targetSlotId: parentSlotId,
      index: nextIndex,
    })
    if (result.ok) {
      this._engine.selectNode(nodeId)
      this._focusCanvasBlock(nodeId)
      return true
    }
    return false
  }

  // ---------------------------------------------------------------------------
  // Insert Dialog
  // ---------------------------------------------------------------------------

  private _openInsertDialog(): boolean {
    if (!this._engine || !this._doc) return false
    this._insertDialogOpen = true
    this._resetInsertDialogToPlacement()
    return true
  }

  private _closeInsertDialog(): void {
    this._insertDialogOpen = false
    this._resetInsertDialogToPlacement()
  }

  private _resetInsertDialogToPlacement(): void {
    this._insertDialogMode = null
    this._insertDialogSlotOptions = []
    this._insertDialogBlockOptions = []
    this._insertDialogQuery = ''
    this._insertDialogHighlight = 0
    this._insertDialogError = ''
    this._insertTarget = null
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
          this._resetInsertDialogToPlacement()
          return
        }
        this._closeInsertDialog()
      },
      selectMode: (mode) => {
        this._selectInsertMode(mode)
      },
      setHighlight: (index) => {
        this._insertDialogHighlight = index
      },
      selectOption: (index) => {
        this._selectInsertDialogOption(index)
      },
      setOptionOutOfRange: () => {
        this._insertDialogError = 'Option out of range'
      },
    }
  }

  private _handleInsertDialogKeydown(e: KeyboardEvent): void {
    const runtimeContext = this._buildInsertDialogShortcutRuntimeContext()
    const resolution = this._insertDialogShortcutResolver.resolve({
      event: e,
      activeContexts: ['insertDialog'],
      runtimeContext,
    })

    if (resolution.kind === 'none') {
      return
    }

    applyResolutionEventPolicy(e, resolution)

    if (resolution.kind !== 'command') {
      return
    }

    const execution = startShortcutCommandExecution(resolution.match.command, runtimeContext)
    if (execution.initial.status === 'pending') {
      void execution.completion
    }
  }

  private _selectInsertDialogOption(selectedIndex: number): void {
    this._insertDialogHighlight = selectedIndex

    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      const selectedSlot = this._insertDialogSlotOptions[selectedIndex - 1]
      if (!selectedSlot) {
        this._insertDialogError = 'Invalid slot number'
        return
      }

      const insideTarget = this._buildInsideTargetFromSlot(selectedSlot.slotId)
      if (!insideTarget) {
        this._insertDialogError = 'Cannot insert into selected slot'
        return
      }

      this._insertDialogSlotOptions = []
      this._insertDialogQuery = ''
      this._setInsertDialogTarget(insideTarget)
      return
    }

    const definition = this._getInsertDialogVisibleBlockOptions()[selectedIndex - 1]
    if (!definition || !this._insertTarget) {
      this._insertDialogError = 'Invalid block number'
      return
    }

    const ok = this._insertNodeAtTarget(definition.type, this._insertTarget.slotId, this._insertTarget.index)
    if (ok) {
      this._closeInsertDialog()
    } else {
      this._insertDialogError = 'Failed to insert block'
    }
  }

  private _focusInsertDialogSearchAfterRender(): void {
    void this.updateComplete.then(() => {
      requestAnimationFrame(() => {
        const searchInput = this.querySelector<HTMLInputElement>('.insert-dialog-search')
        if (!searchInput) return
        if (document.activeElement !== searchInput) {
          searchInput.focus({ preventScroll: true })
        }
      })
    })
  }

  private _setInsertDialogTarget(target: InsertTarget): void {
    this._insertTarget = target
    this._insertDialogBlockOptions = this._buildInsertableOptions(target.parentType)
    this._insertDialogHighlight = this._insertDialogBlockOptions.length > 0 ? 1 : 0
    this._insertDialogError = this._insertDialogBlockOptions.length > 0
      ? ''
      : 'No blocks can be inserted at this location'
    this._focusInsertDialogSearchAfterRender()
  }

  private _selectInsertMode(mode: InsertMode): void {
    this._insertDialogMode = mode
    this._insertTarget = null
    this._insertDialogSlotOptions = []
    this._insertDialogBlockOptions = []
    this._insertDialogQuery = ''
    this._insertDialogHighlight = 0
    this._insertDialogError = ''

    if (mode === 'start') {
      const target = this._getInsertTargetDocumentStart()
      if (!target) {
        this._insertDialogError = 'No valid document start target'
        return
      }
      this._setInsertDialogTarget(target)
      return
    }

    if (mode === 'end') {
      const target = this._getInsertTargetDocumentEnd()
      if (!target) {
        this._insertDialogError = 'No valid document end target'
        return
      }
      this._setInsertDialogTarget(target)
      return
    }

    if (mode === 'after') {
      const target = this._getInsertTargetAfterSelected()
      if (!target) {
        this._insertDialogError = 'No valid "after" target'
        return
      }
      this._setInsertDialogTarget(target)
      return
    }

    if (mode === 'before') {
      const target = this._getInsertTargetBeforeSelected()
      if (!target) {
        this._insertDialogError = 'No valid "before" target'
        return
      }
      this._setInsertDialogTarget(target)
      return
    }

    this._insertDialogSlotOptions = this._getInsertSlotOptionsForInside()
    this._insertDialogHighlight = this._insertDialogSlotOptions.length > 0 ? 1 : 0
    if (this._insertDialogSlotOptions.length === 0) {
      this._insertDialogError = 'No valid inside slot for selected block'
      return
    }
    if (this._insertDialogSlotOptions.length === 1) {
      const target = this._buildInsideTargetFromSlot(this._insertDialogSlotOptions[0].slotId)
      if (!target) {
        this._insertDialogError = 'No valid inside slot for selected block'
        return
      }
      this._insertDialogSlotOptions = []
      this._setInsertDialogTarget(target)
    }
  }

  private _getInsertDialogOptionCount(): number {
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.length
    }
    return this._getInsertDialogVisibleBlockOptions().length
  }

  private _setInsertDialogQuery(query: string): void {
    this._insertDialogQuery = query
    const optionCount = this._getInsertDialogVisibleBlockOptions().length
    if (optionCount === 0) {
      this._insertDialogHighlight = 0
      return
    }
    if (this._insertDialogHighlight <= 0 || this._insertDialogHighlight > optionCount) {
      this._insertDialogHighlight = 1
    }
  }

  private _isInsertBlockSelectionStage(): boolean {
    return !!this._insertTarget
  }

  private _getInsertDialogVisibleBlockOptions(): ComponentDefinition[] {
    const query = this._insertDialogQuery.trim().toLowerCase()
    if (!query) return this._insertDialogBlockOptions
    return this._insertDialogBlockOptions.filter((def) => {
      return (
        def.label.toLowerCase().includes(query)
        || def.type.toLowerCase().includes(query)
        || def.category.toLowerCase().includes(query)
      )
    })
  }

  private _insertNodeAtTarget(type: string, slotId: SlotId, index: number): boolean {
    if (!this._engine || !this._doc) return false

    const { node, slots, extraNodes } = this._engine.registry.createNode(type)
    const result = this._engine.dispatch({
      type: 'InsertNode',
      node,
      slots,
      targetSlotId: slotId,
      index,
      _restoreNodes: extraNodes,
    })

    if (result.ok) {
      this._engine.selectNode(node.id)
      this._focusCanvasBlock(node.id)
      return true
    }
    return false
  }

  private _buildInsertableOptions(parentType: string): ComponentDefinition[] {
    if (!this._engine) return []
    return this._engine.registry
      .insertable()
      // Root is the single document container and must never be insertable as a block.
      .filter((def) => def.type !== 'root')
      .filter((def) => this._engine!.registry.canContain(parentType, def.type))
  }

  private _getInsertTargetAfterSelected(): InsertTarget | null {
    if (!this._engine || !this._doc) return null

    const selectedNodeId = this._selectedNodeId
    if (selectedNodeId) {
      const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(selectedNodeId)
      if (parentSlotId) {
        const parentSlot = this._doc.slots[parentSlotId]
        if (!parentSlot) return null
        const index = parentSlot.children.indexOf(selectedNodeId)
        if (index >= 0) {
          const parentNode = this._doc.nodes[parentSlot.nodeId]
          if (!parentNode) return null
          return { slotId: parentSlotId, index: index + 1, parentType: parentNode.type }
        }
      }
    }

    const rootNode = this._doc.nodes[this._doc.root]
    if (!rootNode || rootNode.slots.length === 0) return null
    const slotId = rootNode.slots[0]
    const slot = this._doc.slots[slotId]
    if (!slot) return null
    return { slotId, index: slot.children.length, parentType: rootNode.type }
  }

  private _getInsertTargetDocumentStart(): InsertTarget | null {
    if (!this._doc) return null
    const rootNode = this._doc.nodes[this._doc.root]
    if (!rootNode || rootNode.slots.length === 0) return null
    const slotId = rootNode.slots[0]
    return { slotId, index: 0, parentType: rootNode.type }
  }

  private _getInsertTargetDocumentEnd(): InsertTarget | null {
    if (!this._doc) return null
    const rootNode = this._doc.nodes[this._doc.root]
    if (!rootNode || rootNode.slots.length === 0) return null
    const slotId = rootNode.slots[0]
    const slot = this._doc.slots[slotId]
    if (!slot) return null
    return { slotId, index: slot.children.length, parentType: rootNode.type }
  }

  private _getInsertTargetBeforeSelected(): InsertTarget | null {
    if (!this._engine || !this._doc) return null

    const selectedNodeId = this._selectedNodeId
    if (selectedNodeId) {
      const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(selectedNodeId)
      if (parentSlotId) {
        const parentSlot = this._doc.slots[parentSlotId]
        if (!parentSlot) return null
        const index = parentSlot.children.indexOf(selectedNodeId)
        if (index >= 0) {
          const parentNode = this._doc.nodes[parentSlot.nodeId]
          if (!parentNode) return null
          return { slotId: parentSlotId, index, parentType: parentNode.type }
        }
      }
    }

    const rootNode = this._doc.nodes[this._doc.root]
    if (!rootNode || rootNode.slots.length === 0) return null
    const slotId = rootNode.slots[0]
    return { slotId, index: 0, parentType: rootNode.type }
  }

  private _getInsertSlotOptionsForInside(): InsertSlotOption[] {
    if (!this._engine || !this._doc) return []

    const selectedNodeId = this._selectedNodeId ?? this._doc.root
    const selectedNode = this._doc.nodes[selectedNodeId]
    if (!selectedNode) return []

    const insertable = this._buildInsertableOptions(selectedNode.type)
    if (insertable.length === 0) return []

    return selectedNode.slots
      .map((slotId, index) => {
        const slot = this._doc?.slots[slotId]
        if (!slot) return null
        const slotName = slot.name && slot.name.trim().length > 0 ? slot.name : `slot-${index + 1}`
        return { slotId, label: `${slotName} (${slot.children.length} items)` }
      })
      .filter((value): value is InsertSlotOption => value !== null)
  }

  private _buildInsideTargetFromSlot(slotId: SlotId): InsertTarget | null {
    if (!this._engine || !this._doc) return null
    const slot = this._doc.slots[slotId]
    if (!slot) return null
    const ownerNode = this._doc.nodes[slot.nodeId]
    if (!ownerNode) return null
    return { slotId, index: slot.children.length, parentType: ownerNode.type }
  }

  private _insertDialogKeyLabel(key: string): string {
    if (key === 'escape') return 'Esc'
    if (key === 'arrowup') return '\u2191'
    if (key === 'arrowdown') return '\u2193'
    if (key === 'enter') return 'Enter'
    if (key.length === 1) return key.toUpperCase()
    return key
  }

  private _insertDialogQuickSelectLabel(): string {
    const quickSelectKeys = INSERT_DIALOG_SHORTCUTS.navigation.quickSelect as readonly string[]
    if (quickSelectKeys.length === 0) return ''

    const singleDigitSequence = quickSelectKeys.every((value) => /^[0-9]$/.test(value))
    if (!singleDigitSequence) {
      return quickSelectKeys.join('/')
    }

    const first = Number(quickSelectKeys[0])
    const contiguous = quickSelectKeys.every((value, index) => Number(value) === first + index)
    if (!contiguous) {
      return quickSelectKeys.join('/')
    }

    const last = quickSelectKeys[quickSelectKeys.length - 1]
    return `${quickSelectKeys[0]}-${last}`
  }

  private _getInsertDialogPrompt(): string {
    const placement = INSERT_DIALOG_SHORTCUTS.placement
    const navigation = INSERT_DIALOG_SHORTCUTS.navigation
    const closeLabel = this._insertDialogKeyLabel(navigation.close)
    const confirmLabel = this._insertDialogKeyLabel(navigation.confirm)
    const previousLabel = this._insertDialogKeyLabel(navigation.previous)
    const nextLabel = this._insertDialogKeyLabel(navigation.next)
    const quickSelectLabel = this._insertDialogQuickSelectLabel()

    const documentPlacementPrompt = `${placement.document.start.toUpperCase()}=Start  ${placement.document.end.toUpperCase()}=End  ${closeLabel}=Close`
    const selectedPlacementPrompt = `${placement.selected.after.toUpperCase()}=After  ${placement.selected.before.toUpperCase()}=Before  ${placement.selected.inside.toUpperCase()}=Inside  ${closeLabel}=Close`

    if (!this._insertDialogMode) {
      return this._isDocumentInsertContext()
        ? documentPlacementPrompt
        : selectedPlacementPrompt
    }
    if (!this._insertTarget && this._insertDialogSlotOptions.length === 0 && this._insertDialogBlockOptions.length === 0) {
      return this._isDocumentInsertContext()
        ? `No valid target. Press ${placement.document.start.toUpperCase()}/${placement.document.end.toUpperCase()} or ${closeLabel}`
        : `No valid target. Press ${placement.selected.after.toUpperCase()}/${placement.selected.before.toUpperCase()}/${placement.selected.inside.toUpperCase()} or ${closeLabel}`
    }

    const quickSelectPrompt = quickSelectLabel ? quickSelectLabel : 'Quick-select'
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return `${quickSelectPrompt}=Choose slot  ${previousLabel}/${nextLabel}=Navigate  ${confirmLabel}=Confirm  ${closeLabel}=Close`
    }
    return `Type=Search  ${quickSelectPrompt}=Quick insert  ${previousLabel}/${nextLabel}=Navigate  ${confirmLabel}=Insert  ${closeLabel}=Close`
  }

  private _getInsertDialogContext(): string {
    if (!this._insertDialogMode) {
      return this._isDocumentInsertContext()
        ? 'Target: document'
        : `Target: choose placement around ${this._selectedNodeLabel()}`
    }

    if (this._insertDialogMode === 'start') return 'Target: document start'
    if (this._insertDialogMode === 'end') return 'Target: document end'

    if (this._insertDialogMode === 'after') {
      return `Target: after ${this._selectedNodeLabel()}`
    }
    if (this._insertDialogMode === 'before') {
      return `Target: before ${this._selectedNodeLabel()}`
    }
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return `Target: inside ${this._selectedNodeLabel()}`
    }
    if (this._insertTarget) {
      const slot = this._doc?.slots[this._insertTarget.slotId]
      const slotName = slot?.name?.trim() ? slot.name : 'selected slot'
      return `Target: inside ${slotName}`
    }
    return 'Target: inside selected block'
  }

  private _selectedNodeLabel(): string {
    if (!this._doc || !this._engine || !this._selectedNodeId) return 'document'
    const node = this._doc.nodes[this._selectedNodeId]
    if (!node) return 'document'
    return this._engine.registry.get(node.type)?.label ?? node.type
  }

  private _isDocumentInsertContext(): boolean {
    if (!this._doc) return true
    if (this._selectedNodeId === null || this._selectedNodeId === this._doc.root) return true
    return !this._doc.nodes[this._selectedNodeId]
  }

  private _getInsertDialogTotalCount(): number {
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.length
    }
    return this._insertDialogBlockOptions.length
  }

  private _getInsertDialogRangeText(visibleCount: number, totalCount: number): string {
    if (totalCount === 0 || visibleCount === 0) return ''
    return `Showing 1-${visibleCount} of ${totalCount}`
  }

  private _getInsertDialogRows(): Array<{ index: number; label: string; detail: string }> {
    const quickSelectCount = INSERT_DIALOG_SHORTCUTS.navigation.quickSelect.length
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.slice(0, quickSelectCount).map((slot, i) => ({
        index: i + 1,
        label: slot.label,
        detail: 'slot',
      }))
    }

    return this._getInsertDialogVisibleBlockOptions().map((def, i) => ({
      index: i + 1,
      label: def.label,
      detail: def.category,
    }))
  }

  // ---------------------------------------------------------------------------
  // Block Operations
  // ---------------------------------------------------------------------------

  private _duplicateSelectedNode(): boolean {
    if (!this._engine || !this._doc) return false
    const nodeId = this._selectedNodeId
    if (!nodeId || nodeId === this._doc.root) return false

    const parentSlotId = this._engine.indexes.parentSlotByNodeId.get(nodeId)
    if (!parentSlotId) return false
    const parentSlot = this._doc.slots[parentSlotId]
    if (!parentSlot) return false

    const index = parentSlot.children.indexOf(nodeId)
    if (index < 0) return false

    const clone = this._cloneSubtree(nodeId)
    if (!clone) return false

    const result = this._engine.dispatch({
      type: 'InsertNode',
      node: clone.node,
      slots: clone.slots,
      targetSlotId: parentSlotId,
      index: index + 1,
      _restoreNodes: clone.extraNodes,
    })

    if (result.ok) {
      this._engine.selectNode(clone.node.id)
      this._focusCanvasBlock(clone.node.id)
      return true
    }
    return false
  }

  private _cloneSubtree(nodeId: NodeId): { node: Node; slots: Slot[]; extraNodes?: Node[] } | null {
    const doc = this._doc
    if (!doc) return null

    const nodeIds: NodeId[] = []
    const slotIds: SlotId[] = []
    const visit = (currentId: NodeId) => {
      nodeIds.push(currentId)
      const node = doc.nodes[currentId]
      if (!node) return
      for (const slotId of node.slots) {
        slotIds.push(slotId)
        const slot = doc.slots[slotId]
        if (!slot) continue
        for (const childId of slot.children) {
          visit(childId)
        }
      }
    }
    visit(nodeId)

    const nodeIdMap = new Map<NodeId, NodeId>()
    const slotIdMap = new Map<SlotId, SlotId>()
    for (const id of nodeIds) {
      nodeIdMap.set(id, nanoid() as NodeId)
    }
    for (const id of slotIds) {
      slotIdMap.set(id, nanoid() as SlotId)
    }

    const clonedNodes: Node[] = nodeIds.map((id) => {
      const node = doc.nodes[id]
      const mappedSlots = node.slots.map((slotId) => slotIdMap.get(slotId)!)
      return {
        ...structuredClone(node),
        id: nodeIdMap.get(id)!,
        slots: mappedSlots,
      }
    })

    const clonedSlots: Slot[] = slotIds.map((id) => {
      const slot = doc.slots[id]
      const mappedChildren = slot.children.map((childId) => nodeIdMap.get(childId)!)
      return {
        ...structuredClone(slot),
        id: slotIdMap.get(id)!,
        nodeId: nodeIdMap.get(slot.nodeId)!,
        children: mappedChildren,
      }
    })

    const rootNode = clonedNodes.find((n) => n.id === nodeIdMap.get(nodeId))
    if (!rootNode) return null
    const extraNodes = clonedNodes.filter((n) => n.id !== rootNode.id)

    return {
      node: rootNode,
      slots: clonedSlots,
      extraNodes: extraNodes.length > 0 ? extraNodes : undefined,
    }
  }

  private _handleTogglePreview = () => {
    this._previewOpen = !this._previewOpen
    try {
      localStorage.setItem(EpistolaEditor.PREVIEW_OPEN_KEY, String(this._previewOpen))
    } catch {
      // localStorage may be unavailable
    }
  }

  private _handleForceSave = () => {
    if (this._saveService && this._doc) {
      this._saveService.forceSave(this._doc)
    }
  }

  /**
   * Warn users about unsaved changes when closing/navigating away.
   */
  private _handleBeforeUnload(e: BeforeUnloadEvent): void {
    if (this._saveService?.isDirtyOrSaving) {
      e.preventDefault()
    }
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  override render() {
    if (!this._engine || !this._doc) {
      return html`<div class="editor-empty">No template loaded</div>`
    }

    const hasPreview = !!this.fetchPreview
    const hasSave = !!this.onSave
    const showPreview = hasPreview && this._previewOpen
    const previewWidth = showPreview ? EpistolaResizeHandle.getPersistedWidth() : undefined
    const leaderClasses = [
      'leader-hint',
      this._leaderVisible ? 'is-visible' : '',
      this._leaderStatus !== 'idle' ? `is-${this._leaderStatus}` : '',
    ]
      .filter(Boolean)
      .join(' ')
    const insertRows = this._getInsertDialogRows()
    const insertPrompt = this._getInsertDialogPrompt()
    const insertContext = this._getInsertDialogContext()
    const insertTotalCount = this._getInsertDialogTotalCount()
    const insertRangeText = this._getInsertDialogRangeText(insertRows.length, insertTotalCount)
    const insertStageKey = [
      this._insertDialogMode ?? 'placement',
      this._insertTarget ? 'target' : 'slot',
      String(this._insertDialogSlotOptions.length),
      String(this._insertDialogBlockOptions.length),
    ].join(':')

    return html`
      <div class="epistola-editor">
        <!-- Toolbar -->
        <epistola-toolbar
          .engine=${this._engine}
          .previewOpen=${this._previewOpen}
          .hasPreview=${hasPreview}
          .hasSave=${hasSave}
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
              <div class="insert-dialog" data-testid="insert-dialog" role="dialog" aria-label="Insert block">
                <div class="insert-dialog-title">Insert Block</div>
                ${keyed(insertStageKey, html`
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
                              const target = event.target as HTMLInputElement
                              this._setInsertDialogQuery(target.value)
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
                        : insertRows.map((row) => html`
                            <div class="insert-dialog-row ${row.index === this._insertDialogHighlight ? 'is-active' : ''}">
                              <span class="insert-dialog-index">${row.index}</span>
                              <span class="insert-dialog-label">${row.label}</span>
                              <span class="insert-dialog-detail">${row.detail}</span>
                            </div>
                          `)}
                    </div>

                    ${insertRangeText
                      ? html`<div class="insert-dialog-range">${insertRangeText}</div>`
                      : nothing}
                  </div>
                `)}
              </div>
            `
          : nothing}
      </div>
    `
  }
}

declare global {
  interface HTMLElementTagNameMap {
    'epistola-editor': EpistolaEditor
  }
}
