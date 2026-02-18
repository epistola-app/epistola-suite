import { describe, it, expect, vi, beforeEach } from 'vitest'
import { html } from 'lit'
import type { EditorPlugin, PluginContext, SidebarTabContribution, ToolbarAction } from './types.js'
import { EditorEngine } from '../engine/EditorEngine.js'
import { createTestDocument, testRegistry, resetCounter } from '../engine/test-helpers.js'

beforeEach(() => {
  resetCounter()
})

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createContext(overrides?: Partial<PluginContext>): PluginContext {
  const doc = createTestDocument()
  const engine = new EditorEngine(doc, testRegistry())
  return {
    engine,
    doc: engine.doc,
    selectedNodeId: null,
    ...overrides,
  }
}

function createTestPlugin(overrides?: Partial<EditorPlugin>): EditorPlugin {
  return {
    id: 'test-plugin',
    init: vi.fn(() => vi.fn()),
    ...overrides,
  }
}

// ---------------------------------------------------------------------------
// Plugin init / dispose lifecycle
// ---------------------------------------------------------------------------

describe('EditorPlugin lifecycle', () => {
  it('init() is called with the correct context', () => {
    const plugin = createTestPlugin()
    const ctx = createContext()

    plugin.init(ctx)

    expect(plugin.init).toHaveBeenCalledOnce()
    expect(plugin.init).toHaveBeenCalledWith(ctx)
  })

  it('init() receives engine, doc, and selectedNodeId in context', () => {
    let receivedCtx: PluginContext | undefined
    const plugin = createTestPlugin({
      init: vi.fn((ctx) => {
        receivedCtx = ctx
        return () => {}
      }),
    })
    const ctx = createContext()

    plugin.init(ctx)

    expect(receivedCtx).toBeDefined()
    expect(receivedCtx!.engine).toBeInstanceOf(EditorEngine)
    expect(receivedCtx!.doc).toBeDefined()
    expect(receivedCtx!.doc.root).toBeDefined()
    expect(receivedCtx!.selectedNodeId).toBeNull()
  })

  it('init() returns a dispose function', () => {
    const disposeFn = vi.fn()
    const plugin = createTestPlugin({
      init: vi.fn(() => disposeFn),
    })
    const ctx = createContext()

    const dispose = plugin.init(ctx)

    expect(typeof dispose).toBe('function')
    expect(disposeFn).not.toHaveBeenCalled()

    dispose()

    expect(disposeFn).toHaveBeenCalledOnce()
  })

  it('multiple plugins can be initialized and disposed independently', () => {
    const dispose1 = vi.fn()
    const dispose2 = vi.fn()

    const plugin1 = createTestPlugin({ id: 'plugin-1', init: vi.fn(() => dispose1) })
    const plugin2 = createTestPlugin({ id: 'plugin-2', init: vi.fn(() => dispose2) })

    const ctx = createContext()

    const d1 = plugin1.init(ctx)
    const d2 = plugin2.init(ctx)

    // Dispose only the first
    d1()
    expect(dispose1).toHaveBeenCalledOnce()
    expect(dispose2).not.toHaveBeenCalled()

    // Dispose the second
    d2()
    expect(dispose2).toHaveBeenCalledOnce()
  })
})

// ---------------------------------------------------------------------------
// Sidebar tab contributions
// ---------------------------------------------------------------------------

describe('SidebarTabContribution', () => {
  it('plugin can contribute a sidebar tab', () => {
    const tab: SidebarTabContribution = {
      id: 'ai-chat',
      label: 'AI Chat',
      icon: 'bot',
      render: (ctx) => html`<div>AI panel for ${ctx.doc.root}</div>`,
    }

    const plugin = createTestPlugin({ sidebarTab: tab })

    expect(plugin.sidebarTab).toBeDefined()
    expect(plugin.sidebarTab!.id).toBe('ai-chat')
    expect(plugin.sidebarTab!.label).toBe('AI Chat')
    expect(plugin.sidebarTab!.icon).toBe('bot')
  })

  it('sidebar tab render receives plugin context', () => {
    const renderSpy = vi.fn((ctx: PluginContext) => html`<div>content</div>`)
    const tab: SidebarTabContribution = {
      id: 'test-tab',
      label: 'Test',
      render: renderSpy,
    }

    const ctx = createContext()
    tab.render(ctx)

    expect(renderSpy).toHaveBeenCalledOnce()
    expect(renderSpy).toHaveBeenCalledWith(ctx)
  })

  it('plugin without sidebar tab has undefined sidebarTab', () => {
    const plugin = createTestPlugin()
    expect(plugin.sidebarTab).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// Toolbar action contributions
// ---------------------------------------------------------------------------

describe('ToolbarAction', () => {
  it('plugin can contribute toolbar actions', () => {
    const clickSpy = vi.fn()
    const actions: ToolbarAction[] = [
      { id: 'ai-generate', label: 'Generate', icon: 'sparkles', onClick: clickSpy },
    ]

    const plugin = createTestPlugin({ toolbarActions: actions })

    expect(plugin.toolbarActions).toHaveLength(1)
    expect(plugin.toolbarActions![0].id).toBe('ai-generate')
    expect(plugin.toolbarActions![0].label).toBe('Generate')
  })

  it('toolbar action onClick is callable', () => {
    const clickSpy = vi.fn()
    const action: ToolbarAction = {
      id: 'test-action',
      label: 'Test',
      icon: 'test',
      onClick: clickSpy,
    }

    action.onClick()

    expect(clickSpy).toHaveBeenCalledOnce()
  })

  it('plugin can contribute multiple toolbar actions', () => {
    const actions: ToolbarAction[] = [
      { id: 'action-1', label: 'One', icon: 'one', onClick: vi.fn() },
      { id: 'action-2', label: 'Two', icon: 'two', onClick: vi.fn() },
    ]

    const plugin = createTestPlugin({ toolbarActions: actions })

    expect(plugin.toolbarActions).toHaveLength(2)
  })

  it('plugin without toolbar actions has undefined toolbarActions', () => {
    const plugin = createTestPlugin()
    expect(plugin.toolbarActions).toBeUndefined()
  })
})

// ---------------------------------------------------------------------------
// Collecting contributions from multiple plugins
// ---------------------------------------------------------------------------

describe('Plugin contribution aggregation', () => {
  it('sidebar tabs from multiple plugins can be collected', () => {
    const plugins: EditorPlugin[] = [
      createTestPlugin({
        id: 'plugin-a',
        sidebarTab: { id: 'tab-a', label: 'Tab A', render: () => html`<div>A</div>` },
      }),
      createTestPlugin({ id: 'plugin-b' }), // no tab
      createTestPlugin({
        id: 'plugin-c',
        sidebarTab: { id: 'tab-c', label: 'Tab C', render: () => html`<div>C</div>` },
      }),
    ]

    const tabs = plugins.filter((p) => p.sidebarTab).map((p) => p.sidebarTab!)

    expect(tabs).toHaveLength(2)
    expect(tabs[0].id).toBe('tab-a')
    expect(tabs[1].id).toBe('tab-c')
  })

  it('toolbar actions from multiple plugins can be flattened', () => {
    const plugins: EditorPlugin[] = [
      createTestPlugin({
        id: 'plugin-a',
        toolbarActions: [
          { id: 'a1', label: 'A1', icon: 'a', onClick: vi.fn() },
        ],
      }),
      createTestPlugin({ id: 'plugin-b' }), // no actions
      createTestPlugin({
        id: 'plugin-c',
        toolbarActions: [
          { id: 'c1', label: 'C1', icon: 'c', onClick: vi.fn() },
          { id: 'c2', label: 'C2', icon: 'c', onClick: vi.fn() },
        ],
      }),
    ]

    const actions = plugins.flatMap((p) => p.toolbarActions ?? [])

    expect(actions).toHaveLength(3)
    expect(actions.map((a) => a.id)).toEqual(['a1', 'c1', 'c2'])
  })
})
