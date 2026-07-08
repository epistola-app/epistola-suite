/**
 * Floating bubble menu for text formatting.
 *
 * Uses @floating-ui/dom for positioning relative to the text selection.
 * Implemented as a ProseMirror plugin that shows/hides on selection change.
 */

import { Plugin, PluginKey } from 'prosemirror-state';
import type { EditorView } from 'prosemirror-view';
import type { Schema, MarkType, NodeType } from 'prosemirror-model';
import { toggleMark, setBlockType } from 'prosemirror-commands';
import { wrapInList, liftListItem, sinkListItem } from 'prosemirror-schema-list';
import { computePosition, offset, flip, shift } from '@floating-ui/dom';
import { TEXT_SHORTCUT_COMMAND_IDS, getTextBubbleTitle } from '../shortcuts/text-runtime.js';

export const BUBBLE_MENU_KEY = new PluginKey('bubbleMenu');

/** Convert a list from one type to another (e.g., bullet → ordered) by changing the node type. */
function convertListType(view: EditorView, fromType: NodeType, toType: NodeType): void {
  const { $from } = view.state.selection;
  for (let d = $from.depth; d > 0; d--) {
    const node = $from.node(d);
    if (node.type === fromType) {
      const tr = view.state.tr.setNodeMarkup($from.before(d), toType, node.attrs);
      view.dispatch(tr);
      return;
    }
  }
}

// ---------------------------------------------------------------------------
// Mark / block active helpers
// ---------------------------------------------------------------------------

function markActive(view: EditorView, markType: MarkType): boolean {
  const { from, $from, to, empty } = view.state.selection;
  if (empty) return !!markType.isInSet(view.state.storedMarks || $from.marks());
  return view.state.doc.rangeHasMark(from, to, markType);
}

function blockActive(
  view: EditorView,
  nodeType: NodeType,
  attrs?: Record<string, unknown>,
): boolean {
  const { $from } = view.state.selection;
  for (let d = $from.depth; d > 0; d--) {
    const node = $from.node(d);
    if (node.type === nodeType) {
      if (!attrs) return true;
      return Object.entries(attrs).every(([k, v]) => node.attrs[k] === v);
    }
  }
  return false;
}

// ---------------------------------------------------------------------------
// Button definitions
// ---------------------------------------------------------------------------

interface ButtonDef {
  label: string;
  title: string;
  className: string;
  isActive: (view: EditorView) => boolean;
  command: (view: EditorView) => void;
  /** When provided and it returns false, the button is hidden for the current selection. */
  isVisible?: (view: EditorView) => boolean;
}

export interface BubbleMenuPluginOptions {
  isEditable?: (view: EditorView) => boolean;
}

/** True when the selection sits inside a list item (any list type / depth). */
function inListItem(view: EditorView): boolean {
  const { $from } = view.state.selection;
  for (let d = $from.depth; d > 0; d--) {
    if ($from.node(d).type === view.state.schema.nodes.list_item) return true;
  }
  return false;
}

/**
 * Buttons grouped by section (marks, headings, lists, expression). Each group
 * is guarded on the schema, so a schema missing a whole section yields an empty
 * group. Empty groups are dropped, and the caller renders one divider *between*
 * surviving groups — never leading, trailing, or doubled.
 */
function createButtonGroups(schema: Schema): ButtonDef[][] {
  const groups: ButtonDef[][] = [];
  // Accumulator for the group currently being built; flushed at each boundary.
  let defs: ButtonDef[] = [];

  // Bold
  if (schema.marks.strong) {
    defs.push({
      label: 'B',
      title: getTextBubbleTitle(TEXT_SHORTCUT_COMMAND_IDS.bold, 'Bold'),
      className: 'pm-bubble-btn bold',
      isActive: (view) => markActive(view, schema.marks.strong),
      command: (view) => toggleMark(schema.marks.strong)(view.state, view.dispatch, view),
    });
  }

  // Italic
  if (schema.marks.em) {
    defs.push({
      label: 'I',
      title: getTextBubbleTitle(TEXT_SHORTCUT_COMMAND_IDS.italic, 'Italic'),
      className: 'pm-bubble-btn italic',
      isActive: (view) => markActive(view, schema.marks.em),
      command: (view) => toggleMark(schema.marks.em)(view.state, view.dispatch, view),
    });
  }

  // Underline
  if (schema.marks.underline) {
    defs.push({
      label: 'U',
      title: getTextBubbleTitle(TEXT_SHORTCUT_COMMAND_IDS.underline, 'Underline'),
      className: 'pm-bubble-btn underline',
      isActive: (view) => markActive(view, schema.marks.underline),
      command: (view) => toggleMark(schema.marks.underline)(view.state, view.dispatch, view),
    });
  }

  // Strikethrough
  if (schema.marks.strikethrough) {
    defs.push({
      label: 'S',
      title: 'Strikethrough',
      className: 'pm-bubble-btn strikethrough',
      isActive: (view) => markActive(view, schema.marks.strikethrough),
      command: (view) => toggleMark(schema.marks.strikethrough)(view.state, view.dispatch, view),
    });
  }

  // Subscript
  if (schema.marks.subscript) {
    defs.push({
      label: 'X₂',
      title: 'Subscript',
      className: 'pm-bubble-btn subscript',
      isActive: (view) => markActive(view, schema.marks.subscript),
      command: (view) => toggleMark(schema.marks.subscript)(view.state, view.dispatch, view),
    });
  }

  // Superscript
  if (schema.marks.superscript) {
    defs.push({
      label: 'X²',
      title: 'Superscript',
      className: 'pm-bubble-btn superscript',
      isActive: (view) => markActive(view, schema.marks.superscript),
      command: (view) => toggleMark(schema.marks.superscript)(view.state, view.dispatch, view),
    });
  }

  // End marks group.
  groups.push(defs);
  defs = [];

  // Headings
  if (schema.nodes.heading) {
    for (const level of [1, 2, 3]) {
      defs.push({
        label: `H${level}`,
        title: `Heading ${level}`,
        className: 'pm-bubble-btn heading',
        isActive: (view) => blockActive(view, schema.nodes.heading, { level }),
        command: (view) => {
          const isHeading = blockActive(view, schema.nodes.heading, { level });
          if (isHeading) {
            setBlockType(schema.nodes.paragraph)(view.state, view.dispatch, view);
          } else {
            setBlockType(schema.nodes.heading, { level })(view.state, view.dispatch, view);
          }
        },
      });
    }
  }

  // End headings group.
  groups.push(defs);
  defs = [];

  // Bullet list (toggle: wrap if not in list, lift if already bullet, convert if ordered)
  if (schema.nodes.bullet_list && schema.nodes.list_item) {
    defs.push({
      label: 'UL',
      title: 'Bullet List',
      className: 'pm-bubble-btn',
      isActive: (view) => blockActive(view, schema.nodes.bullet_list),
      command: (view) => {
        if (blockActive(view, schema.nodes.bullet_list)) {
          liftListItem(schema.nodes.list_item)(view.state, view.dispatch, view);
        } else if (blockActive(view, schema.nodes.ordered_list)) {
          convertListType(view, schema.nodes.ordered_list, schema.nodes.bullet_list);
        } else {
          wrapInList(schema.nodes.bullet_list)(view.state, view.dispatch, view);
        }
      },
    });
  }

  // Ordered list (toggle: wrap if not in list, lift if already ordered, convert if bullet)
  if (schema.nodes.ordered_list && schema.nodes.list_item) {
    defs.push({
      label: 'OL',
      title: 'Ordered List',
      className: 'pm-bubble-btn',
      isActive: (view) => blockActive(view, schema.nodes.ordered_list),
      command: (view) => {
        if (blockActive(view, schema.nodes.ordered_list)) {
          liftListItem(schema.nodes.list_item)(view.state, view.dispatch, view);
        } else if (blockActive(view, schema.nodes.bullet_list)) {
          convertListType(view, schema.nodes.bullet_list, schema.nodes.ordered_list);
        } else {
          wrapInList(schema.nodes.ordered_list)(view.state, view.dispatch, view);
        }
      },
    });
  }

  // List style cycling: # button cycles through styles within the current list type
  if (schema.nodes.ordered_list || schema.nodes.bullet_list) {
    const olStyles = ['decimal', 'lower-alpha', 'upper-alpha', 'lower-roman', 'upper-roman'];
    const ulStyles = ['disc', 'circle', 'square', 'dash'];

    defs.push({
      label: '#',
      title: 'Cycle list style',
      className: 'pm-bubble-btn list-type-btn',
      isActive: () => false,
      command: (view) => {
        const { $from } = view.state.selection;
        for (let d = $from.depth; d > 0; d--) {
          const node = $from.node(d);

          if (node.type === schema.nodes.ordered_list) {
            const current = (node.attrs.listType as string) || 'decimal';
            const idx = olStyles.indexOf(current);
            const next = olStyles[(idx + 1) % olStyles.length];
            const tr = view.state.tr.setNodeMarkup($from.before(d), undefined, {
              ...node.attrs,
              listType: next,
            });
            view.dispatch(tr);
            break;
          }

          if (node.type === schema.nodes.bullet_list) {
            const current = (node.attrs.listStyle as string) || 'disc';
            const idx = ulStyles.indexOf(current);
            const next = ulStyles[(idx + 1) % ulStyles.length];
            const tr = view.state.tr.setNodeMarkup($from.before(d), undefined, {
              ...node.attrs,
              listStyle: next,
            });
            view.dispatch(tr);
            break;
          }
        }
      },
    });

    // Outdent: lift the current item one level (Shift-Tab). Shown only in a list.
    if (schema.nodes.list_item) {
      defs.push({
        label: '⇤',
        title: 'Outdent (Shift-Tab)',
        className: 'pm-bubble-btn list-outdent-btn',
        isActive: () => false,
        isVisible: (view) => inListItem(view),
        command: (view) => liftListItem(schema.nodes.list_item)(view.state, view.dispatch, view),
      });

      // Indent: sink the current item into a nested sub-list (Tab). Shown only in a list.
      defs.push({
        label: '⇥',
        title: 'Indent / sub-list (Tab)',
        className: 'pm-bubble-btn list-indent-btn',
        isActive: () => false,
        isVisible: (view) => inListItem(view),
        command: (view) => sinkListItem(schema.nodes.list_item)(view.state, view.dispatch, view),
      });
    }
  }

  // End lists group.
  groups.push(defs);
  defs = [];

  // Expression insert
  if (schema.nodes.expression) {
    defs.push({
      label: '{{}}',
      title: 'Insert Expression',
      className: 'pm-bubble-btn expression',
      isActive: () => false,
      command: (view) => {
        const node = schema.nodes.expression.create({ expression: '', isNew: true });
        const tr = view.state.tr.replaceSelectionWith(node);
        view.dispatch(tr);
      },
    });
  }

  // End expression group.
  groups.push(defs);

  return groups.filter((group) => group.length > 0);
}

// ---------------------------------------------------------------------------
// Menu DOM
// ---------------------------------------------------------------------------

export function createMenuElement(schema: Schema): {
  menuEl: HTMLElement;
  buttons: { el: HTMLElement; def: ButtonDef }[];
} {
  const menuEl = document.createElement('div');
  menuEl.className = 'pm-bubble-menu';
  menuEl.style.display = 'none';

  const groups = createButtonGroups(schema);
  const buttons: { el: HTMLElement; def: ButtonDef }[] = [];

  // One divider between adjacent non-empty groups — never leading/trailing/doubled.
  groups.forEach((group, groupIndex) => {
    if (groupIndex > 0) {
      const sep = document.createElement('span');
      sep.className = 'pm-bubble-sep';
      menuEl.appendChild(sep);
    }

    for (const def of group) {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = def.className;
      button.textContent = def.label;
      button.title = def.title;
      button.addEventListener('mousedown', (e) => {
        e.preventDefault(); // Prevent blur
        e.stopPropagation();
      });
      menuEl.appendChild(button);
      buttons.push({ el: button, def });
    }
  });

  return { menuEl, buttons };
}

// ---------------------------------------------------------------------------
// Position update
// ---------------------------------------------------------------------------

function updatePosition(menuEl: HTMLElement, view: EditorView): void {
  const { from, to } = view.state.selection;

  // Get the DOM range for the selection. With a collapsed cursor from === to,
  // yielding a zero-width caret rect — @floating-ui handles that fine. Guard
  // against coordsAtPos throwing on a freshly-focused / not-yet-laid-out block.
  let start: { left: number; right: number; top: number; bottom: number };
  let end: { left: number; right: number; top: number; bottom: number };
  try {
    start = view.coordsAtPos(from);
    end = view.coordsAtPos(to);
  } catch {
    hideMenu(menuEl);
    return;
  }

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
      };
    },
  };

  computePosition(virtualEl, menuEl, {
    // The menu is position: fixed and coordsAtPos yields viewport coords, so
    // compute in viewport space too — the default 'absolute' strategy adds the
    // page scroll offset, dropping the menu scrollY pixels too low on pages
    // that scroll the window (the canvas editor never does, form pages do).
    strategy: 'fixed',
    placement: 'top',
    middleware: [offset(8), flip(), shift({ padding: 8 })],
  }).then(({ x, y }) => {
    menuEl.style.left = `${x}px`;
    menuEl.style.top = `${y}px`;
  });
}

function hideMenu(menuEl: HTMLElement | null): void {
  if (!menuEl) return;
  menuEl.style.display = 'none';
}

/**
 * Show the bubble menu whenever the editor has focus — no text selection
 * required. Shared by the plugin's transaction `update()` and the `focus`
 * DOM listener (focus alone produces no transaction, so `update()` would
 * not otherwise run for a click into an empty block).
 */
function showMenu(
  menuEl: HTMLElement | null,
  buttons: { el: HTMLElement; def: ButtonDef }[],
  view: EditorView,
  isEditable: (view: EditorView) => boolean,
): void {
  if (!menuEl) return;

  if (!view.hasFocus() || !isEditable(view)) {
    hideMenu(menuEl);
    return;
  }

  const { from, to } = view.state.selection;
  const size = view.state.doc.content.size;
  if (from < 0 || to < 0 || from > size || to > size) {
    hideMenu(menuEl);
    return;
  }

  menuEl.style.display = 'flex';

  // Update active + visibility states
  for (const { el, def } of buttons) {
    el.style.display = def.isVisible && !def.isVisible(view) ? 'none' : '';
    if (def.isActive(view)) {
      el.classList.add('active');
    } else {
      el.classList.remove('active');
    }
  }

  updatePosition(menuEl, view);
}

// ---------------------------------------------------------------------------
// Plugin
// ---------------------------------------------------------------------------

export function bubbleMenuPlugin(schema: Schema, options: BubbleMenuPluginOptions = {}): Plugin {
  let menuEl: HTMLElement | null = null;
  let buttons: { el: HTMLElement; def: ButtonDef }[] = [];
  const isEditable = options.isEditable ?? ((view: EditorView) => view.editable);

  return new Plugin({
    key: BUBBLE_MENU_KEY,

    view(view) {
      const result = createMenuElement(schema);
      menuEl = result.menuEl;
      buttons = result.buttons;
      const ownerDocument = view.dom.ownerDocument;

      const onDocumentPointerDown = (event: PointerEvent) => {
        if (!menuEl) return;
        const target = event.target;
        if (!(target instanceof Node)) return;
        if (view.dom.contains(target) || menuEl.contains(target)) return;
        hideMenu(menuEl);
      };

      const onEditorBlur = () => {
        hideMenu(menuEl);
      };

      const onEditorFocus = () => {
        showMenu(menuEl, buttons, view, isEditable);
      };

      // While the menu is visible, re-anchor it to the selection on scroll.
      // The menu is position: fixed, so without this it stays pinned to the
      // viewport as the page scrolls; updatePosition reads the selection's
      // current coords, so re-running it makes the menu track the text.
      // Capture phase catches scroll from the window and any nested scroller.
      const onScroll = () => {
        if (menuEl && menuEl.style.display !== 'none') updatePosition(menuEl, view);
      };

      // Wire up click handlers (need view reference)
      for (const { el, def } of buttons) {
        el.addEventListener('click', (e) => {
          e.preventDefault();
          if (!isEditable(view)) {
            hideMenu(menuEl);
            return;
          }
          def.command(view);
          view.focus();
        });
      }

      // Append to document body for absolute positioning
      ownerDocument.body.appendChild(menuEl);
      ownerDocument.addEventListener('pointerdown', onDocumentPointerDown, true);
      ownerDocument.addEventListener('scroll', onScroll, true);
      view.dom.addEventListener('blur', onEditorBlur, true);
      view.dom.addEventListener('focus', onEditorFocus, true);

      return {
        update(view, _prevState) {
          showMenu(menuEl, buttons, view, isEditable);
        },

        destroy() {
          ownerDocument.removeEventListener('pointerdown', onDocumentPointerDown, true);
          ownerDocument.removeEventListener('scroll', onScroll, true);
          view.dom.removeEventListener('blur', onEditorBlur, true);
          view.dom.removeEventListener('focus', onEditorFocus, true);
          menuEl?.remove();
          menuEl = null;
          buttons = [];
        },
      };
    },
  });
}

export { markActive, blockActive };
