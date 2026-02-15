import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { PreviewService, type PreviewState, type FetchPreviewFn } from './preview-service.js'
import type { TemplateDocument } from '../types/index.js'

// Minimal doc fixture — PreviewService doesn't inspect its contents
const DOC = { modelVersion: 1, root: 'r', nodes: {}, slots: {} } as unknown as TemplateDocument
const DATA = { name: 'test' }

/**
 * Helper that creates a fetch function returning a resolved Blob after a microtask.
 * Captures calls for assertion.
 */
function createMockFetch(blob?: Blob) {
  const calls: { doc: TemplateDocument; data: object | undefined; signal: AbortSignal }[] = []
  const fn: FetchPreviewFn = async (doc, data, signal) => {
    calls.push({ doc, data, signal })
    // Yield to simulate async
    await Promise.resolve()
    return blob ?? new Blob(['%PDF-1.4'], { type: 'application/pdf' })
  }
  return { fn, calls }
}

describe('PreviewService', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts in idle state', () => {
    const onChange = vi.fn()
    const { fn } = createMockFetch()
    const service = new PreviewService(fn, onChange)

    expect(service.state).toEqual({ status: 'idle' })
    expect(onChange).not.toHaveBeenCalled()
    service.dispose()
  })

  it('transitions to loading then success on scheduleRefresh', async () => {
    const states: PreviewState[] = []
    const { fn } = createMockFetch()
    const service = new PreviewService(fn, (s) => states.push({ ...s }), 100)

    service.scheduleRefresh(DOC, DATA)

    // Before debounce fires — still idle
    expect(states).toEqual([])

    // Advance past debounce
    vi.advanceTimersByTime(100)

    // Loading should have been emitted synchronously in _doFetch
    expect(states).toEqual([{ status: 'loading' }])

    // Let the async fetch resolve
    await vi.runAllTimersAsync()

    expect(states.length).toBe(2)
    expect(states[1].status).toBe('success')
    expect((states[1] as { status: 'success'; blobUrl: string }).blobUrl).toMatch(/^blob:/)

    service.dispose()
  })

  it('coalesces multiple scheduleRefresh calls within debounce window', async () => {
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 200)

    service.scheduleRefresh(DOC, DATA)
    vi.advanceTimersByTime(100) // halfway
    service.scheduleRefresh(DOC, DATA) // reset timer
    vi.advanceTimersByTime(100) // still not enough from second call
    expect(calls.length).toBe(0)

    vi.advanceTimersByTime(100) // now 200ms from second call
    expect(calls.length).toBe(1)

    await vi.runAllTimersAsync()
    service.dispose()
  })

  it('forceRefresh bypasses debounce', async () => {
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 5000)

    service.forceRefresh(DOC, DATA)
    expect(calls.length).toBe(1)

    await vi.runAllTimersAsync()
    service.dispose()
  })

  it('aborts in-flight request when new fetch starts', async () => {
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 0)

    service.forceRefresh(DOC, DATA)
    // First fetch is in-flight (awaiting microtask)
    expect(calls.length).toBe(1)
    const firstSignal = calls[0].signal

    // Force another fetch — should abort the first
    service.forceRefresh(DOC, DATA)
    expect(calls.length).toBe(2)
    expect(firstSignal.aborted).toBe(true)
    expect(calls[1].signal.aborted).toBe(false)

    await vi.runAllTimersAsync()
    service.dispose()
  })

  it('transitions to error state when fetch throws', async () => {
    const states: PreviewState[] = []
    const failingFn: FetchPreviewFn = async () => {
      await Promise.resolve()
      throw new Error('Server returned 500')
    }
    const service = new PreviewService(failingFn, (s) => states.push({ ...s }), 0)

    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()

    expect(states).toEqual([
      { status: 'loading' },
      { status: 'error', message: 'Server returned 500' },
    ])

    service.dispose()
  })

  it('silently ignores AbortError', async () => {
    const states: PreviewState[] = []
    const abortFn: FetchPreviewFn = async (_doc, _data, signal) => {
      // Simulate: abort happens, then we check signal
      await Promise.resolve()
      if (signal.aborted) {
        const err = new DOMException('The operation was aborted.', 'AbortError')
        throw err
      }
      return new Blob()
    }
    const service = new PreviewService(abortFn, (s) => states.push({ ...s }), 0)

    service.forceRefresh(DOC, DATA)
    // Abort immediately
    service.forceRefresh(DOC, DATA)

    await vi.runAllTimersAsync()

    // Should see loading twice (one for each forceRefresh), then success for the second
    // The first AbortError should NOT produce an error state
    const errorStates = states.filter((s) => s.status === 'error')
    expect(errorStates).toEqual([])

    service.dispose()
  })

  it('revokes previous blob URL when new success arrives', async () => {
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL')
    const { fn } = createMockFetch()
    const states: PreviewState[] = []
    const service = new PreviewService(fn, (s) => states.push({ ...s }), 0)

    // First fetch
    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()

    const firstBlobUrl = (states.find((s) => s.status === 'success') as { blobUrl: string })
      ?.blobUrl
    expect(firstBlobUrl).toBeTruthy()

    // Second fetch — should revoke the first blob URL
    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()

    expect(revokeObjectURL).toHaveBeenCalledWith(firstBlobUrl)

    revokeObjectURL.mockRestore()
    service.dispose()
  })

  it('dispose cleans up blob URL and prevents further fetches', async () => {
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL')
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 0)

    // Get into success state
    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()
    expect(calls.length).toBe(1)

    service.dispose()

    // Blob URL should have been revoked
    expect(revokeObjectURL).toHaveBeenCalled()

    // Further calls should be no-ops
    service.scheduleRefresh(DOC, DATA)
    service.forceRefresh(DOC, DATA)
    vi.advanceTimersByTime(1000)
    expect(calls.length).toBe(1) // no new calls

    revokeObjectURL.mockRestore()
  })

  it('dispose aborts in-flight request', () => {
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 0)

    service.forceRefresh(DOC, DATA)
    expect(calls.length).toBe(1)
    const signal = calls[0].signal

    service.dispose()
    expect(signal.aborted).toBe(true)
  })

  it('handles non-Error thrown values gracefully', async () => {
    const states: PreviewState[] = []
    const throwStringFn: FetchPreviewFn = async () => {
      await Promise.resolve()
      throw 'unexpected string error'
    }
    const service = new PreviewService(throwStringFn, (s) => states.push({ ...s }), 0)

    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()

    expect(states[1]).toEqual({ status: 'error', message: 'Preview failed' })

    service.dispose()
  })

  it('passes doc, data, and signal to fetch function', async () => {
    const { fn, calls } = createMockFetch()
    const service = new PreviewService(fn, vi.fn(), 0)

    service.forceRefresh(DOC, DATA)
    await vi.runAllTimersAsync()

    expect(calls[0].doc).toBe(DOC)
    expect(calls[0].data).toBe(DATA)
    expect(calls[0].signal).toBeInstanceOf(AbortSignal)

    service.dispose()
  })
})
