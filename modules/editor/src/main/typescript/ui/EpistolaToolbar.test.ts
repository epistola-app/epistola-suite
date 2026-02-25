import { describe, expect, it } from 'vitest'
import { EpistolaToolbar } from './EpistolaToolbar.js'

function templateToMarkup(template: unknown): string {
  if (!template || typeof template !== 'object' || !('strings' in template)) {
    return ''
  }
  const strings = (template as { strings: string[] }).strings
  return strings.join('')
}

describe('EpistolaToolbar shortcut popover accessibility', () => {
  it('renders shortcut trigger accessibility attributes', () => {
    const toolbar = new EpistolaToolbar()
    const toolbarAny = toolbar as unknown as {
      _renderExampleSelector: (examples: object[]) => unknown
    }

    const template = toolbarAny._renderExampleSelector([{}])
    const markup = templateToMarkup(template)

    expect(markup).toContain('aria-label="Keyboard shortcuts"')
    expect(markup).toContain('aria-haspopup="dialog"')
    expect(markup).toContain('aria-controls=')
  })

  it('defines popover dialog and filter input labels in render template', () => {
    const toolbar = new EpistolaToolbar()
    const renderSource = String((toolbar as unknown as { _renderExampleSelector: (examples: object[]) => unknown })._renderExampleSelector)

    expect(renderSource).toContain('role="dialog"')
    expect(renderSource).toContain('aria-label="Filter keyboard shortcuts"')
  })

  it('closes shortcut popover on Escape and prevents default', () => {
    const toolbar = new EpistolaToolbar()
    const toolbarAny = toolbar as unknown as {
      _shortcutsOpen: boolean
      _onWindowKeydown: (e: KeyboardEvent) => void
    }
    toolbarAny._shortcutsOpen = true

    let prevented = false
    toolbarAny._onWindowKeydown({
      key: 'Escape',
      preventDefault: () => {
        prevented = true
      },
    } as KeyboardEvent)

    expect(prevented).toBe(true)
    expect(toolbarAny._shortcutsOpen).toBe(false)
  })

})
