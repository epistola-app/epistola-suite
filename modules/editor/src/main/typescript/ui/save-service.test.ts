import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { SaveService, type SaveState, type SaveFn } from './save-service.js'
import type { TemplateDocument } from '../types/index.js'

// Minimal doc fixture — SaveService doesn't inspect its contents
const DOC = { modelVersion: 1, root: 'r', nodes: {}, slots: {} } as unknown as TemplateDocument
const DOC2 = { modelVersion: 1, root: 'r2', nodes: {}, slots: {} } as unknown as TemplateDocument

/**
 * Helper that creates a save function resolving after a microtask.
 * Captures calls for assertion.
 */
function createMockSave() {
  const calls: TemplateDocument[] = []
  const fn: SaveFn = async (doc) => {
    calls.push(doc)
    await Promise.resolve()
  }
  return { fn, calls }
}

describe('SaveService', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts in idle state', () => {
    const onChange = vi.fn()
    const { fn } = createMockSave()
    const service = new SaveService(fn, onChange)

    expect(service.state).toEqual({ status: 'idle' })
    expect(onChange).not.toHaveBeenCalled()
    service.dispose()
  })

  it('markDirty transitions from idle to dirty', () => {
    const states: SaveState[] = []
    const { fn } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }))

    service.markDirty()

    expect(states).toEqual([{ status: 'dirty' }])
    expect(service.state).toEqual({ status: 'dirty' })
    service.dispose()
  })

  it('markDirty is idempotent when already dirty', () => {
    const states: SaveState[] = []
    const { fn } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }))

    service.markDirty()
    service.markDirty()
    service.markDirty()

    expect(states).toEqual([{ status: 'dirty' }])
    service.dispose()
  })

  it('scheduleAutoSave fires after debounce period', async () => {
    const states: SaveState[] = []
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }), 3000)

    service.markDirty()
    service.scheduleAutoSave(DOC)

    // Before debounce fires
    expect(calls.length).toBe(0)

    // Advance past debounce
    vi.advanceTimersByTime(3000)

    // saving state emitted synchronously
    expect(states).toContainEqual({ status: 'saving' })

    // Let the async save resolve
    await vi.runAllTimersAsync()

    expect(calls.length).toBe(1)
    expect(calls[0]).toBe(DOC)
    expect(states).toContainEqual({ status: 'saved' })

    service.dispose()
  })

  it('coalesces multiple scheduleAutoSave calls within debounce window', async () => {
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 3000)

    service.markDirty()
    service.scheduleAutoSave(DOC)
    vi.advanceTimersByTime(1500) // halfway
    service.scheduleAutoSave(DOC2) // reset timer
    vi.advanceTimersByTime(1500) // still not enough from second call
    expect(calls.length).toBe(0)

    vi.advanceTimersByTime(1500) // now 3000ms from second call
    await vi.runAllTimersAsync()

    expect(calls.length).toBe(1)
    expect(calls[0]).toBe(DOC2) // only the last doc is saved

    service.dispose()
  })

  it('forceSave bypasses debounce', async () => {
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 5000)

    service.markDirty()
    service.forceSave(DOC)

    expect(calls.length).toBe(1)

    await vi.runAllTimersAsync()
    service.dispose()
  })

  it('forceSave is a no-op when idle (nothing to save)', async () => {
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 3000)

    service.forceSave(DOC)

    expect(calls.length).toBe(0)
    service.dispose()
  })

  it('forceSave is a no-op when saved with no pending changes', async () => {
    const states: SaveState[] = []
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }), 3000)

    // Get into saved state
    service.markDirty()
    service.forceSave(DOC)
    await vi.runAllTimersAsync()
    expect(states).toContainEqual({ status: 'saved' })

    // Now forceSave should be no-op (nothing dirty)
    const callsBefore = calls.length
    service.forceSave(DOC)
    expect(calls.length).toBe(callsBefore)

    service.dispose()
  })

  it('transitions saving → saved → idle on success', async () => {
    const states: SaveState[] = []
    const { fn } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }), 0, 2000)

    service.markDirty()
    service.forceSave(DOC)
    await vi.runAllTimersAsync()

    expect(states).toContainEqual({ status: 'dirty' })
    expect(states).toContainEqual({ status: 'saving' })
    expect(states).toContainEqual({ status: 'saved' })

    // saved → idle after 2s
    vi.advanceTimersByTime(2000)
    expect(states[states.length - 1]).toEqual({ status: 'idle' })

    service.dispose()
  })

  it('transitions to error state when save throws', async () => {
    const states: SaveState[] = []
    const failingFn: SaveFn = async () => {
      await Promise.resolve()
      throw new Error('Network error')
    }
    const service = new SaveService(failingFn, (s) => states.push({ ...s }), 0)

    service.markDirty()
    service.forceSave(DOC)
    await vi.runAllTimersAsync()

    expect(states).toContainEqual({ status: 'saving' })
    expect(states).toContainEqual({ status: 'error', message: 'Network error' })

    service.dispose()
  })

  it('handles non-Error thrown values gracefully', async () => {
    const states: SaveState[] = []
    const throwStringFn: SaveFn = async () => {
      await Promise.resolve()
      throw 'unexpected string error'
    }
    const service = new SaveService(throwStringFn, (s) => states.push({ ...s }), 0)

    service.markDirty()
    service.forceSave(DOC)
    await vi.runAllTimersAsync()

    expect(states).toContainEqual({ status: 'error', message: 'Save failed' })

    service.dispose()
  })

  it('prevents concurrent saves — queues pending doc', async () => {
    const calls: TemplateDocument[] = []
    let resolveFirst!: () => void
    const slowFn: SaveFn = async (doc) => {
      calls.push(doc)
      if (calls.length === 1) {
        // First call: wait for manual resolution
        await new Promise<void>((resolve) => {
          resolveFirst = resolve
        })
      } else {
        await Promise.resolve()
      }
    }

    const states: SaveState[] = []
    const service = new SaveService(slowFn, (s) => states.push({ ...s }), 0)

    // Start first save
    service.markDirty()
    service.forceSave(DOC)
    expect(calls.length).toBe(1)

    // While first save is in-flight, try another save
    service.markDirty()
    service.forceSave(DOC2)

    // Second save should not have started yet
    expect(calls.length).toBe(1)

    // Resolve first save
    resolveFirst()
    await vi.runAllTimersAsync()

    // Second save should have been triggered with DOC2
    expect(calls.length).toBe(2)
    expect(calls[1]).toBe(DOC2)

    service.dispose()
  })

  it('markDirty during saving causes re-save after completion', async () => {
    const calls: TemplateDocument[] = []
    let resolveSave!: () => void
    const controlledFn: SaveFn = async (doc) => {
      calls.push(doc)
      await new Promise<void>((resolve) => {
        resolveSave = resolve
      })
    }

    const states: SaveState[] = []
    const service = new SaveService(controlledFn, (s) => states.push({ ...s }), 3000)

    // Start save
    service.markDirty()
    service.forceSave(DOC)
    expect(calls.length).toBe(1)

    // Mark dirty during save and schedule autosave
    service.markDirty()
    service.scheduleAutoSave(DOC2)

    // Complete first save
    resolveSave()
    await vi.runAllTimersAsync()

    // Should transition to dirty (not saved) because pendingDoc was set
    expect(states).toContainEqual({ status: 'dirty' })

    // After debounce, the pending doc should be saved
    vi.advanceTimersByTime(3000)
    resolveSave()
    await vi.runAllTimersAsync()

    expect(calls.length).toBe(2)
    expect(calls[1]).toBe(DOC2)

    service.dispose()
  })

  it('isDirtyOrSaving reflects current state', async () => {
    const { fn } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 3000)

    // Initially clean
    expect(service.isDirtyOrSaving).toBe(false)

    // After markDirty
    service.markDirty()
    expect(service.isDirtyOrSaving).toBe(true)

    // During save
    service.forceSave(DOC)
    expect(service.isDirtyOrSaving).toBe(true)

    // After save completes
    await vi.runAllTimersAsync()
    expect(service.isDirtyOrSaving).toBe(false)

    service.dispose()
  })

  it('dispose prevents further saves', async () => {
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 3000)

    service.markDirty()
    service.scheduleAutoSave(DOC)

    service.dispose()

    // Debounce timer should have been cleared
    vi.advanceTimersByTime(5000)
    expect(calls.length).toBe(0)

    // Direct calls should also be no-ops
    service.markDirty()
    service.forceSave(DOC)
    expect(calls.length).toBe(0)
  })

  it('dispose clears saved→idle timer', async () => {
    const states: SaveState[] = []
    const { fn } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }), 0, 2000)

    service.markDirty()
    service.forceSave(DOC)
    // Flush only microtasks (save promise), not the 2s saved→idle timer
    await vi.advanceTimersByTimeAsync(0)

    // Should be in 'saved' state
    expect(service.state).toEqual({ status: 'saved' })

    service.dispose()

    // After dispose, the saved→idle timer should not fire
    const statesBefore = states.length
    vi.advanceTimersByTime(3000)
    expect(states.length).toBe(statesBefore)
  })

  it('markDirty from saved state cancels saved→idle timer', async () => {
    const states: SaveState[] = []
    const { fn } = createMockSave()
    const service = new SaveService(fn, (s) => states.push({ ...s }), 0, 2000)

    service.markDirty()
    service.forceSave(DOC)
    // Flush only microtasks, not the 2s saved→idle timer
    await vi.advanceTimersByTimeAsync(0)
    expect(service.state).toEqual({ status: 'saved' })

    // Mark dirty again before the saved→idle timer fires
    service.markDirty()
    expect(service.state).toEqual({ status: 'dirty' })

    // The saved→idle timer should NOT transition to idle
    vi.advanceTimersByTime(3000)
    expect(service.state).toEqual({ status: 'dirty' })

    service.dispose()
  })

  it('can recover from error state via markDirty + save', async () => {
    let shouldFail = true
    const calls: TemplateDocument[] = []
    const fn: SaveFn = async (doc) => {
      calls.push(doc)
      await Promise.resolve()
      if (shouldFail) throw new Error('fail')
    }

    const states: SaveState[] = []
    const service = new SaveService(fn, (s) => states.push({ ...s }), 0)

    // First save fails
    service.markDirty()
    service.forceSave(DOC)
    await vi.advanceTimersByTimeAsync(0)
    expect(service.state.status).toBe('error')

    // Recover: markDirty then retry
    shouldFail = false
    service.markDirty()
    service.forceSave(DOC)
    await vi.advanceTimersByTimeAsync(0)
    expect(service.state.status).toBe('saved')

    service.dispose()
  })

  it('passes correct doc to save function', async () => {
    const { fn, calls } = createMockSave()
    const service = new SaveService(fn, vi.fn(), 0)

    service.markDirty()
    service.forceSave(DOC)
    await vi.runAllTimersAsync()

    expect(calls[0]).toBe(DOC)

    service.dispose()
  })
})
