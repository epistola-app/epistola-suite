/**
 * Floating bubble menu for text formatting.
 *
 * Uses @floating-ui/dom for positioning relative to the text selection.
 * Implemented as a ProseMirror plugin that shows/hides on selection change.
 */

import { Plugin, PluginKey } from 'prosemirror-state'
import type { EditorView } from 'prosemirror-view'
import type { Schema, MarkType, NodeType } from 'prosemirror-model'
import { toggleMark, setBlockType } from 'prosemirror-commands'
import { wrapInList } from 'prosemirror-schema-list'
import { computePosition, offset, flip, shift } from '@floating-ui/dom'

const BUBBLE_MENU_KEY = new PluginKey('bubbleMenu')

// ---------------------------------------------------------------------------
// Mark / block active helpers
// ---------------------------------------------------------------------------

function markActive(view: EditorView, markType: MarkType): boolean {
  const { from, $from, to, empty } = view.state.selection
  if (empty) return !!markType.isInSet(view.state.storedMarks || $from.marks())
  return view.state.doc.rangeHasMark(from, to, markType)
}

function blockActive(view: EditorView, nodeType: NodeType, attrs?: Record<string, unknown>): boolean {
  const { $from } = view.state.selection
  for (let d = $from.depth; d > 0; d--) {
    const node = $from.node(d)
    if (node.type === nodeType) {
      if (!attrs) return true
      return Object.entries(attrs).every(([k, v]) => node.attrs[k] === v)
    }
  }
  return false
}

// ---------------------------------------------------------------------------
// Button definitions
// ---------------------------------------------------------------------------

interface ButtonDef {
  label: string
  title: string
  className: string
  isActive: (view: EditorView) => boolean
  command: (view: EditorView) => void
}

function createButtonDefs(schema: Schema): ButtonDef[] {
  const defs: ButtonDef[] = []

  // Bold
  if (schema.marks.strong) {
    defs.push({
      label: 'B',
      title: 'Bold (Ctrl+B)',
      className: 'pm-bubble-btn bold',
      isActive: (view) => markActive(view, schema.marks.strong),
      command: (view) => toggleMark(schema.marks.strong)(view.state, view.dispatch, view),
    })
  }

  // Italic
  if (schema.marks.em) {
    defs.push({
      label: 'I',
      title: 'Italic (Ctrl+I)',
      className: 'pm-bubble-btn italic',
      isActive: (view) => markActive(view, schema.marks.em),
      command: (view) => toggleMark(schema.marks.em)(view.state, view.dispatch, view),
    })
  }

  // Underline
  if (schema.marks.underline) {
    defs.push({
      label: 'U',
      title: 'Underline (Ctrl+U)',
      className: 'pm-bubble-btn underline',
      isActive: (view) => markActive(view, schema.marks.underline),
      command: (view) => toggleMark(schema.marks.underline)(view.state, view.dispatch, view),
    })
  }

  // Strikethrough
  if (schema.marks.strikethrough) {
    defs.push({
      label: 'S',
      title: 'Strikethrough',
      className: 'pm-bubble-btn strikethrough',
      isActive: (view) => markActive(view, schema.marks.strikethrough),
      command: (view) => toggleMark(schema.marks.strikethrough)(view.state, view.dispatch, view),
    })
  }

  // Separator
  defs.push({ label: '', title: '', className: 'pm-bubble-sep', isActive: () => false, command: () => {} })

  // Headings
  if (schema.nodes.heading) {
    for (const level of [1, 2, 3]) {
      defs.push({
        label: `H${level}`,
        title: `Heading ${level}`,
        className: 'pm-bubble-btn heading',
        isActive: (view) => blockActive(view, schema.nodes.heading, { level }),
        command: (view) => {
          const isHeading = blockActive(view, schema.nodes.heading, { level })
          if (isHeading) {
            setBlockType(schema.nodes.paragraph)(view.state, view.dispatch, view)
          } else {
            setBlockType(schema.nodes.heading, { level })(view.state, view.dispatch, view)
          }
        },
      })
    }
  }

  // Separator
  defs.push({ label: '', title: '', className: 'pm-bubble-sep', isActive: () => false, command: () => {} })

  // Bullet list
  if (schema.nodes.bullet_list) {
    defs.push({
      label: 'UL',
      title: 'Bullet List',
      className: 'pm-bubble-btn',
      isActive: (view) => blockActive(view, schema.nodes.bullet_list),
      command: (view) => wrapInList(schema.nodes.bullet_list)(view.state, view.dispatch, view),
    })
  }

  // Ordered list
  if (schema.nodes.ordered_list) {
    defs.push({
      label: 'OL',
      title: 'Ordered List',
      className: 'pm-bubble-btn',
      isActive: (view) => blockActive(view, schema.nodes.ordered_list),
      command: (view) => wrapInList(schema.nodes.ordered_list)(view.state, view.dispatch, view),
    })
  }

  // Separator
  defs.push({ label: '', title: '', className: 'pm-bubble-sep', isActive: () => false, command: () => {} })

  // Expression insert
  if (schema.nodes.expression) {
    defs.push({
      label: '{{}}',
      title: 'Insert Expression',
      className: 'pm-bubble-btn expression',
      isActive: () => false,
      command: (view) => {
        const node = schema.nodes.expression.create({ expression: '', isNew: true })
        const tr = view.state.tr.replaceSelectionWith(node)
        view.dispatch(tr)
      },
    })
  }

  return defs
}

// ---------------------------------------------------------------------------
// Menu DOM
// ---------------------------------------------------------------------------

function createMenuElement(schema: Schema): {
  menuEl: HTMLElement
  buttons: { el: HTMLElement; def: ButtonDef }[]
} {
  const menuEl = document.createElement('div')
  menuEl.className = 'pm-bubble-menu'
  menuEl.style.display = 'none'

  const buttonDefs = createButtonDefs(schema)
  const buttons: { el: HTMLElement; def: ButtonDef }[] = []

  for (const def of buttonDefs) {
    if (def.className === 'pm-bubble-sep') {
      const sep = document.createElement('span')
      sep.className = 'pm-bubble-sep'
      menuEl.appendChild(sep)
      continue
    }

    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = def.className
    btn.textContent = def.label
    btn.title = def.title
    btn.addEventListener('mousedown', (e) => {
      e.preventDefault() // Prevent blur
      e.stopPropagation()
    })
    menuEl.appendChild(btn)
    buttons.push({ el: btn, def })
  }

  return { menuEl, buttons }
}

// ---------------------------------------------------------------------------
// Position update
// ---------------------------------------------------------------------------

function updatePosition(menuEl: HTMLElement, view: EditorView): void {
  const { from, to } = view.state.selection

  // Get the DOM range for the selection
  const start = view.coordsAtPos(from)
  const end = view.coordsAtPos(to)

  // Create a virtual reference element spanning the selection
  const virtualEl = {
    getBoundingClientRect() {
      return {
        x: start.left,
        y: start.top,
        width: end.right - start.left,
        height: end.bottom - start.top,
        top: start.top,
        right: end.right,
        bottom: end.bottom,
        left: start.left,
      }
    },
  }

  computePosition(virtualEl, menuEl, {
    placement: 'top',
    middleware: [offset(8), flip(), shift({ padding: 8 })],
  }).then(({ x, y }) => {
    menuEl.style.left = `${x}px`
    menuEl.style.top = `${y}px`
  })
}

// ---------------------------------------------------------------------------
// Plugin
// ---------------------------------------------------------------------------

export function bubbleMenuPlugin(schema: Schema): Plugin {
  let menuEl: HTMLElement | null = null
  let buttons: { el: HTMLElement; def: ButtonDef }[] = []

  return new Plugin({
    key: BUBBLE_MENU_KEY,

    view(view) {
      const result = createMenuElement(schema)
      menuEl = result.menuEl
      buttons = result.buttons

      // Wire up click handlers (need view reference)
      for (const { el, def } of buttons) {
        el.addEventListener('click', (e) => {
          e.preventDefault()
          def.command(view)
          view.focus()
        })
      }

      // Append to document body for absolute positioning
      document.body.appendChild(menuEl)

      return {
        update(view, _prevState) {
          if (!menuEl) return

          const { state } = view
          const { selection } = state
          const { empty } = selection

          // Hide if selection is empty or collapsed
          if (empty || !view.hasFocus()) {
            menuEl.style.display = 'none'
            return
          }

          // Show and update
          menuEl.style.display = 'flex'

          // Update active states
          for (const { el, def } of buttons) {
            if (def.isActive(view)) {
              el.classList.add('active')
            } else {
              el.classList.remove('active')
            }
          }

          updatePosition(menuEl, view)
        },

        destroy() {
          menuEl?.remove()
          menuEl = null
          buttons = []
        },
      }
    },
  })
}

export { markActive, blockActive }
