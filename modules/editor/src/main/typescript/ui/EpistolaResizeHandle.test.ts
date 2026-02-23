import { describe, expect, it } from 'vitest'
import {
  getResizeResultForKey,
  KEYBOARD_RESIZE_STEP,
  MIN_WIDTH,
  MAX_WIDTH,
} from './EpistolaResizeHandle.js'

describe('getResizeResultForKey', () => {
  it('grows preview width on ArrowLeft using keyboard step', () => {
    expect(getResizeResultForKey('ArrowLeft', 400)).toEqual({
      nextWidth: 400 + KEYBOARD_RESIZE_STEP,
      closePreview: false,
    })
  })

  it('clamps ArrowLeft width changes to the max width', () => {
    expect(getResizeResultForKey('ArrowLeft', MAX_WIDTH)).toEqual({
      nextWidth: MAX_WIDTH,
      closePreview: false,
    })
  })

  it('shrinks preview width on ArrowRight using keyboard step', () => {
    expect(getResizeResultForKey('ArrowRight', MIN_WIDTH + KEYBOARD_RESIZE_STEP)).toEqual({
      nextWidth: MIN_WIDTH,
      closePreview: false,
    })
  })

  it('closes preview on ArrowRight when already at minimum width', () => {
    expect(getResizeResultForKey('ArrowRight', MIN_WIDTH)).toEqual({
      nextWidth: MIN_WIDTH,
      closePreview: true,
    })
  })

  it('ignores non-resize keys', () => {
    expect(getResizeResultForKey('Enter', 400)).toBeNull()
  })
})
