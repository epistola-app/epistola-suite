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
import type { EpistolaCanvas } from './EpistolaCanvas.js'
import type { EpistolaToolbar } from './EpistolaToolbar.js'
import { nanoid } from 'nanoid'
import { LEADER_SHORTCUTS } from './shortcuts.js'

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

interface LeaderCommandResult {
  ok: boolean
  message: string
}

interface InsertBlockQuickKey {
  key: string
  type: string
}

const INSERT_BLOCK_QUICK_KEYS: InsertBlockQuickKey[] = [
  { key: '1', type: 'text' },
  { key: '2', type: 'container' },
  { key: '3', type: 'columns' },
  { key: '4', type: 'table' },
  { key: '5', type: 'datatable' },
  { key: '6', type: 'conditional' },
  { key: '7', type: 'loop' },
  { key: '8', type: 'image' },
  { key: '9', type: 'page-header' },
  { key: '0', type: 'page-footer' },
]

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

  @property({ attribute: false }) fetchPreview?: FetchPreviewFn
  @property({ attribute: false }) onSave?: SaveFn
  @property({ attribute: false }) plugins?: EditorPlugin[]
  @state() private _doc?: TemplateDocument
  @state() private _selectedNodeId: NodeId | null = null
  @state() private _previewOpen = false
  @state() private _saveState: SaveState = { status: 'idle' }
  @state() private _leaderActive = false
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

  private _leaderTimeout: ReturnType<typeof setTimeout> | null = null
  private _leaderResultTimeout: ReturnType<typeof setTimeout> | null = null
  private _leaderClearTimeout: ReturnType<typeof setTimeout> | null = null

  private static readonly PREVIEW_OPEN_KEY = 'ep:preview-open'
  private static readonly LEADER_TIMEOUT_MS = 1600
  private static readonly LEADER_RESULT_MS = 700
  private static readonly LEADER_CLEAR_MS = 180

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

  override connectedCallback(): void {
    super.connectedCallback()
    window.addEventListener('keydown', this._onKeydown)
    this.addEventListener('toggle-preview', this._handleTogglePreview)
    this.addEventListener('request-close-preview', this._handleClosePreviewRequest)
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
    this.removeEventListener('request-close-preview', this._handleClosePreviewRequest)
    this.removeEventListener('force-save', this._handleForceSave)
    window.removeEventListener('beforeunload', this._onBeforeUnload)
    super.disconnectedCallback()
    this._disposePlugins()
    this._unsubEngine?.()
    this._unsubSelection?.()
    this._saveService?.dispose()
    this._clearLeaderTimers()
  }

  /**
   * Global keyboard handler for undo/redo, save, delete, and escape.
   */
  private _handleKeydown(e: KeyboardEvent): void {
    if (!this._engine) return
    if (this._insertDialogOpen) {
      this._handleInsertDialogKeydown(e)
      return
    }
    if (e.defaultPrevented) return
    const mod = e.metaKey || e.ctrlKey
    if (mod && e.code === 'Space') {
      e.preventDefault()
      this._startLeaderMode()
      return
    }
    const target = e.target as HTMLElement | null
    if (target?.closest('input, textarea, select, [contenteditable="true"], .ProseMirror')) {
      return
    }
    const key = e.key.toLowerCase()

    if (this._leaderActive) {
      if (e.key === 'Escape') {
        e.preventDefault()
        this._hideLeaderMode()
        return
      }
      if (e.key === 'Shift' || e.key === 'Alt' || e.key === 'Control' || e.key === 'Meta') {
        return
      }

      e.preventDefault()
      const result = this._runLeaderCommand(key)
      this._showLeaderResult(result.ok, result.message)
      return
    }

    if (mod) {
      if (key === 's') {
        e.preventDefault()
        if (this._saveService && this._doc) {
          this._saveService.forceSave(this._doc)
        }
      } else if (key === 'z' && !e.shiftKey) {
        e.preventDefault()
        this._engine.undo()
      } else if ((key === 'z' && e.shiftKey) || key === 'y') {
        e.preventDefault()
        this._engine.redo()
      }
      return
    }

    if (e.key === 'Escape') {
      const selectedNodeId = this._selectedNodeId
      if (!selectedNodeId || !this._doc) return
      if (selectedNodeId === this._doc.root) return
      e.preventDefault()
      this._engine.selectNode(null)
      this._focusCanvas()
      return
    }

    if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      const selectedNodeId = this._selectedNodeId
      if (!selectedNodeId || !this._doc) return
      if (selectedNodeId === this._doc.root) return
      e.preventDefault()
      this._moveSelectedNode(e.key === 'ArrowUp' ? -1 : 1)
      return
    }

    if (e.key === 'Backspace' || e.key === 'Delete') {
      const selectedNodeId = this._selectedNodeId
      if (!selectedNodeId || !this._doc) return
      if (selectedNodeId === this._doc.root) return
      const nextSelection = this._engine.getNextSelectionAfterRemove(selectedNodeId)
      e.preventDefault()
      const result = this._engine.dispatch({ type: 'RemoveNode', nodeId: selectedNodeId })
      if (result.ok) {
        this._engine.selectNode(nextSelection)
        this._focusCanvasBlock(nextSelection)
      }
    }
  }

  private _focusCanvasBlock(nodeId: NodeId | null): void {
    if (!nodeId) return
    requestAnimationFrame(() => {
      const block = this.querySelector<HTMLElement>(`.canvas-block[data-node-id="${nodeId}"]`)
      block?.focus({ preventScroll: true })
    })
  }

  private _startLeaderMode(): void {
    this._clearLeaderTimers()

    const active = document.activeElement as HTMLElement | null
    if (active && this.contains(active) && active.matches('input, textarea, select, [contenteditable="true"], .ProseMirror')) {
      active.blur()
    }

    this._leaderActive = true
    this._leaderVisible = true
    this._leaderStatus = 'idle'
    const idleTokens = LEADER_SHORTCUTS.map((command) => command.idleToken).join(' ')
    this._leaderMessage = `Waiting: ${idleTokens}`
    this._leaderTimeout = setTimeout(() => this._hideLeaderMode(), EpistolaEditor.LEADER_TIMEOUT_MS)
  }

  private _showLeaderResult(ok: boolean, message: string): void {
    this._clearLeaderTimers()
    this._leaderActive = false
    this._leaderVisible = true
    this._leaderStatus = ok ? 'success' : 'error'
    this._leaderMessage = message
    this._leaderResultTimeout = setTimeout(() => this._hideLeaderMode(), EpistolaEditor.LEADER_RESULT_MS)
  }

  private _hideLeaderMode(): void {
    this._clearLeaderTimers()
    this._leaderActive = false
    this._leaderVisible = false
    this._leaderClearTimeout = setTimeout(() => {
      this._leaderStatus = 'idle'
      this._leaderMessage = ''
    }, EpistolaEditor.LEADER_CLEAR_MS)
  }

  private _clearLeaderTimers(): void {
    if (this._leaderTimeout) {
      clearTimeout(this._leaderTimeout)
      this._leaderTimeout = null
    }
    if (this._leaderResultTimeout) {
      clearTimeout(this._leaderResultTimeout)
      this._leaderResultTimeout = null
    }
    if (this._leaderClearTimeout) {
      clearTimeout(this._leaderClearTimeout)
      this._leaderClearTimeout = null
    }
  }

  private _runLeaderCommand(key: string): LeaderCommandResult {
    switch (key) {
      case 'p':
        this._handleTogglePreview()
        return { ok: true, message: this._leaderSuccessMessage('p') }
      case 'd':
        return this._runDuplicateLeaderCommand()
      case 'a':
        return this._withLeaderResult(this._openInsertDialog(), 'a', 'Cannot open insert dialog')
      case 'c':
        return this._withLeaderResult(this._focusCanvas(), 'c', 'Canvas unavailable')
      case 'r':
        return this._withLeaderResult(this._focusResizeHandle(), 'r', 'Preview divider unavailable')
      case '?':
      case '/':
        return this._withLeaderResult(this._openShortcutsHelp(), '?', 'Cannot open shortcuts help')
      case '1':
        return this._withLeaderResult(this._focusPalette(), '1', 'Blocks panel unavailable')
      case '2':
        return this._withLeaderResult(this._focusTree(), '2', 'Structure panel unavailable')
      case '3':
        return this._withLeaderResult(this._focusInspector(), '3', 'Inspector panel unavailable')
      case 'arrowup':
        return this._runMoveLeaderCommand(-1)
      case 'arrowdown':
        return this._runMoveLeaderCommand(1)
      default:
        return { ok: false, message: 'Unknown leader command' }
    }
  }

  private _withLeaderResult(ok: boolean, key: string, failureMessage: string): LeaderCommandResult {
    if (!ok) return { ok: false, message: failureMessage }
    return { ok: true, message: this._leaderSuccessMessage(key) }
  }

  private _runDuplicateLeaderCommand(): LeaderCommandResult {
    const label = this._selectedNodeLabel()
    const ok = this._duplicateSelectedNode()
    if (!ok) return { ok: false, message: 'Select a block to duplicate' }
    return { ok: true, message: `Duplicated ${label}` }
  }

  private _runMoveLeaderCommand(delta: number): LeaderCommandResult {
    const label = this._selectedNodeLabel()
    const ok = this._moveSelectedNode(delta)
    if (!ok) {
      return { ok: false, message: delta < 0 ? 'Cannot move block up' : 'Cannot move block down' }
    }
    return { ok: true, message: `Moved ${label} ${delta < 0 ? 'up' : 'down'}` }
  }

  private _leaderSuccessMessage(key: string): string {
    return LEADER_SHORTCUTS.find((command) => command.key === key)?.successMessage ?? 'Command applied'
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

  private _focusCanvas(): boolean {
    // Leader + C targets the canvas root focus target (the epistola-canvas host),
    // not an individual block. This is the keyboard "return to root area" action.
    const canvas = this.querySelector<EpistolaCanvas>('epistola-canvas')
    if (!canvas) return false
    canvas.focus()
    return true
  }

  private _focusResizeHandle(): boolean {
    if (!this._previewOpen) return false
    const handle = this.querySelector<EpistolaResizeHandle>('epistola-resize-handle')
    if (!handle) return false
    handle.focus()
    return true
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

  private _handleInsertDialogKeydown(e: KeyboardEvent): void {
    if (e.key === 'Escape') {
      e.preventDefault()
      if (this._insertDialogMode) {
        this._resetInsertDialogToPlacement()
        return
      }
      this._closeInsertDialog()
      return
    }

    const modeKey = e.key.toLowerCase()
    const isDocumentContext = this._isDocumentInsertContext()
    const mode = this._parseInsertModeKey(modeKey, isDocumentContext)
    if (mode) {
      e.preventDefault()
      this._selectInsertMode(mode)
      return
    }

    if (!this._insertDialogMode) {
      return
    }

    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const optionCount = this._getInsertDialogOptionCount()
      if (optionCount === 0) return
      if (this._insertDialogHighlight <= 0) {
        this._insertDialogHighlight = 1
        return
      }
      const delta = e.key === 'ArrowDown' ? 1 : -1
      const next = this._insertDialogHighlight + delta
      this._insertDialogHighlight = next < 1 ? optionCount : next > optionCount ? 1 : next
      return
    }

    if (e.key === 'Enter') {
      e.preventDefault()
      if (this._insertDialogHighlight <= 0) return
      this._selectInsertDialogOption(this._insertDialogHighlight)
      return
    }

    if (!this._insertTarget) {
      return
    }

    const quickKey = INSERT_BLOCK_QUICK_KEYS.find((entry) => entry.key === e.key)
    if (quickKey) {
      e.preventDefault()
      this._insertBlockByType(quickKey.type)
      return
    }

    if (e.key === 'Backspace') {
      if (this._insertDialogQuery.length === 0) return
      e.preventDefault()
      this._setInsertDialogQuery(this._insertDialogQuery.slice(0, -1))
      return
    }

    if (e.key === 'Delete') {
      if (this._insertDialogQuery.length === 0) return
      e.preventDefault()
      this._setInsertDialogQuery('')
      return
    }

    if (e.key.length === 1 && !e.metaKey && !e.ctrlKey && !e.altKey) {
      e.preventDefault()
      this._setInsertDialogQuery(this._insertDialogQuery + e.key)
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

      this._insertTarget = insideTarget
      this._insertDialogBlockOptions = this._buildInsertableOptions(insideTarget.parentType)
      this._insertDialogSlotOptions = []
      this._insertDialogHighlight = this._insertDialogBlockOptions.length > 0 ? 1 : 0
      this._insertDialogError = ''
      if (this._insertDialogBlockOptions.length === 0) {
        this._insertDialogError = 'No blocks can be inserted at this location'
      }
      return
    }

    const definition = this._getVisibleInsertDialogBlockOptions()[selectedIndex - 1]
    if (!definition || !this._insertTarget) {
      this._insertDialogError = 'Invalid block number'
      return
    }

    this._insertBlockByType(definition.type)
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
      this._insertTarget = target
      this._setInsertDialogBlockOptions(this._buildInsertableOptions(target.parentType))
      return
    }

    if (mode === 'end') {
      const target = this._getInsertTargetDocumentEnd()
      if (!target) {
        this._insertDialogError = 'No valid document end target'
        return
      }
      this._insertTarget = target
      this._setInsertDialogBlockOptions(this._buildInsertableOptions(target.parentType))
      return
    }

    if (mode === 'after') {
      const target = this._getInsertTargetAfterSelected()
      if (!target) {
        this._insertDialogError = 'No valid "after" target'
        return
      }
      this._insertTarget = target
      this._setInsertDialogBlockOptions(this._buildInsertableOptions(target.parentType))
      return
    }

    if (mode === 'before') {
      const target = this._getInsertTargetBeforeSelected()
      if (!target) {
        this._insertDialogError = 'No valid "before" target'
        return
      }
      this._insertTarget = target
      this._setInsertDialogBlockOptions(this._buildInsertableOptions(target.parentType))
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
      this._insertTarget = target
      this._setInsertDialogBlockOptions(this._buildInsertableOptions(target.parentType))
      this._insertDialogSlotOptions = []
    }
  }

  private _getInsertDialogOptionCount(): number {
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.length
    }
    return this._getVisibleInsertDialogBlockOptions().length
  }

  private _setInsertDialogBlockOptions(options: ComponentDefinition[]): void {
    this._insertDialogBlockOptions = options
    this._insertDialogQuery = ''
    this._insertDialogError = ''
    this._insertDialogHighlight = this._getInsertDialogOptionCount() > 0 ? 1 : 0
    if (options.length === 0) {
      this._insertDialogError = 'No blocks can be inserted at this location'
    }
  }

  private _setInsertDialogQuery(query: string): void {
    this._insertDialogQuery = query
    this._insertDialogError = ''
    this._insertDialogHighlight = this._getInsertDialogOptionCount() > 0 ? 1 : 0
  }

  private _getVisibleInsertDialogBlockOptions(): ComponentDefinition[] {
    const query = this._insertDialogQuery.trim().toLowerCase()
    if (query.length === 0) return this._insertDialogBlockOptions
    return this._insertDialogBlockOptions.filter((def) => {
      const haystack = `${def.label} ${def.category}`.toLowerCase()
      return haystack.includes(query)
    })
  }

  private _insertBlockByType(type: string): void {
    if (!this._insertTarget) return
    const isAllowedAtTarget = this._insertDialogBlockOptions.some((def) => def.type === type)
    if (!isAllowedAtTarget) {
      this._insertDialogError = 'Core block unavailable at this location'
      return
    }
    const ok = this._insertNodeAtTarget(type, this._insertTarget.slotId, this._insertTarget.index)
    if (ok) {
      this._closeInsertDialog()
    } else {
      this._insertDialogError = 'Failed to insert block'
    }
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

  private _getInsertDialogPrompt(): string {
    if (!this._insertDialogMode) {
      return this._isDocumentInsertContext()
        ? 'S=Start  E=End  Esc=Close'
        : 'A=After  B=Before  I=Inside  Esc=Close'
    }
    if (!this._insertTarget && this._insertDialogSlotOptions.length === 0 && this._insertDialogBlockOptions.length === 0) {
      return this._isDocumentInsertContext()
        ? 'No valid target. Press S/E or Esc'
        : 'No valid target. Press A/B/I or Esc'
    }
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return '↑/↓=Navigate slots  Enter=Confirm  Esc=Back'
    }
    return `Type=Filter  ${this._insertQuickKeyHint()}  ↑/↓=Navigate  Enter=Insert  Esc=Back`
  }

  private _insertQuickKeyHint(): string {
    return '1-9/0=Core blocks'
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

  private _parseInsertModeKey(key: string, isDocumentContext: boolean): InsertMode | null {
    if (isDocumentContext) {
      if (key === 's') return 'start'
      if (key === 'e') return 'end'
      return null
    }
    if (key === 'a') return 'after'
    if (key === 'b') return 'before'
    if (key === 'i') return 'inside'
    return null
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
    if (!this._insertTarget && this._insertDialogSlotOptions.length > 0) {
      return this._insertDialogSlotOptions.map((slot, i) => ({
        index: i + 1,
        label: slot.label,
        detail: 'slot',
      }))
    }

    return this._getVisibleInsertDialogBlockOptions().map((def, i) => ({
      index: i + 1,
      label: def.label,
      detail: this._quickKeyForBlockType(def.type) ?? def.category,
    }))
  }

  private _quickKeyForBlockType(type: string): string | null {
    const entry = INSERT_BLOCK_QUICK_KEYS.find((quickKey) => quickKey.type === type)
    return entry ? `Key ${entry.key}` : null
  }

  private _isInsertBlockSelectionStage(): boolean {
    return !!this._insertTarget
  }

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

  private _setPreviewOpen(open: boolean): void {
    this._previewOpen = open
    try {
      localStorage.setItem(EpistolaEditor.PREVIEW_OPEN_KEY, String(open))
    } catch {
      // localStorage may be unavailable
    }
  }

  private _handleTogglePreview = () => {
    this._setPreviewOpen(!this._previewOpen)
  }

  private _handleClosePreviewRequest = (e: Event) => {
    e.stopPropagation()
    if (!this._previewOpen) return
    this._setPreviewOpen(false)
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
    const isInsertBlockStage = this._isInsertBlockSelectionStage()
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
        <div class=${leaderClasses} role="status" aria-live="polite">
          <span class="leader-dot" aria-hidden="true"></span>
          <span class="leader-text">${this._leaderMessage}</span>
        </div>

        ${this._insertDialogOpen
          ? html`
              <div class="insert-dialog-backdrop" @click=${this._closeInsertDialog}></div>
              <div class="insert-dialog" role="dialog" aria-label="Insert block">
                <div class="insert-dialog-title">Insert Block</div>
                ${keyed(insertStageKey, html`
                  <div class="insert-dialog-stage">
                    <div class="insert-dialog-hint">${insertPrompt}</div>
                    <div class="insert-dialog-context">${insertContext}</div>

                    ${isInsertBlockStage
                      ? html`
                          <div class="insert-dialog-filter">
                            <span class="insert-dialog-filter-label">Filter</span>
                            <span class="insert-dialog-filter-value">${this._insertDialogQuery || 'Type to search blocks...'}</span>
                          </div>
                        `
                      : nothing}

                    ${this._insertDialogError
                      ? html`<div class="insert-dialog-error">${this._insertDialogError}</div>`
                      : nothing}

                    <div class="insert-dialog-list">
                      ${insertRows.length > 0
                        ? insertRows.map((row) => html`
                            <div class="insert-dialog-row ${row.index === this._insertDialogHighlight ? 'is-active' : ''}">
                              <span class="insert-dialog-index">${row.index}</span>
                              <span class="insert-dialog-label">${row.label}</span>
                              <span class="insert-dialog-detail">${row.detail}</span>
                            </div>
                          `)
                        : html`<div class="insert-dialog-empty">No matching blocks</div>`}
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
