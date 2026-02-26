import { describe, expect, it, vi } from 'vitest'
import { EpistolaEditor } from './EpistolaEditor.js'

type EpistolaEditorTiming = {
  NOTICE_RESULT_MS: number
  NOTICE_CLEAR_MS: number
}

describe('EpistolaEditor notice timing', () => {
  it('keeps notice text until hide animation window completes', () => {
    vi.useFakeTimers()
    try {
      const editor = new EpistolaEditor()
      const editorAny = editor as unknown as {
        _showNotice: (message: string, tone: 'info' | 'error') => void
        _noticeVisible: boolean
        _noticeMessage: string
        _noticeTone: 'info' | 'error'
      }
      const timing = EpistolaEditor as unknown as EpistolaEditorTiming

      editorAny._showNotice('Cannot move page footer', 'error')

      expect(editorAny._noticeVisible).toBe(true)
      expect(editorAny._noticeMessage).toBe('Cannot move page footer')
      expect(editorAny._noticeTone).toBe('error')

      vi.advanceTimersByTime(timing.NOTICE_RESULT_MS)
      expect(editorAny._noticeVisible).toBe(false)
      expect(editorAny._noticeMessage).toBe('Cannot move page footer')

      vi.advanceTimersByTime(timing.NOTICE_CLEAR_MS)
      expect(editorAny._noticeMessage).toBe('')
      expect(editorAny._noticeTone).toBe('info')
    } finally {
      vi.useRealTimers()
    }
  })

  it('resets hide timers when a new notice arrives', () => {
    vi.useFakeTimers()
    try {
      const editor = new EpistolaEditor()
      const editorAny = editor as unknown as {
        _showNotice: (message: string, tone: 'info' | 'error') => void
        _noticeVisible: boolean
        _noticeMessage: string
      }
      const timing = EpistolaEditor as unknown as EpistolaEditorTiming

      editorAny._showNotice('First message', 'error')
      vi.advanceTimersByTime(Math.floor(timing.NOTICE_RESULT_MS / 2))

      editorAny._showNotice('Second message', 'error')
      expect(editorAny._noticeVisible).toBe(true)
      expect(editorAny._noticeMessage).toBe('Second message')

      vi.advanceTimersByTime(Math.floor(timing.NOTICE_RESULT_MS / 2))
      expect(editorAny._noticeVisible).toBe(true)
      expect(editorAny._noticeMessage).toBe('Second message')
    } finally {
      vi.useRealTimers()
    }
  })
})
