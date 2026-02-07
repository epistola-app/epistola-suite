/**
 * Block selection management.
 *
 * Handles single and multi-block selection state for the editor.
 * Selection is tracked independently from the template state.
 */

// ============================================================================
// Types
// ============================================================================

/**
 * Selection state.
 */
export interface SelectionState {
  /** Currently selected block IDs */
  selectedIds: Set<string>;

  /** The primary/anchor block (for multi-select, this is the first selected) */
  anchorId: string | null;

  /** The focus block (for multi-select, this is the most recently selected) */
  focusId: string | null;
}

/**
 * Selection change listener.
 */
export type SelectionListener = (state: SelectionState) => void;

/**
 * Selection manager interface.
 */
export interface SelectionManager {
  /** Get current selection state */
  getState(): SelectionState;

  /** Select a single block (clears previous selection) */
  select(blockId: string): void;

  /** Toggle selection of a block (for multi-select with Ctrl/Cmd) */
  toggle(blockId: string): void;

  /** Extend selection to a block (for range select with Shift) */
  extendTo(blockId: string, orderedIds: string[]): void;

  /** Add a block to selection without clearing others */
  add(blockId: string): void;

  /** Remove a block from selection */
  remove(blockId: string): void;

  /** Clear all selection */
  clear(): void;

  /** Check if a block is selected */
  isSelected(blockId: string): boolean;

  /** Check if selection is empty */
  isEmpty(): boolean;

  /** Get the number of selected blocks */
  count(): number;

  /** Subscribe to selection changes */
  subscribe(listener: SelectionListener): () => void;

  /** Dispose of the selection manager */
  dispose(): void;
}

// ============================================================================
// Implementation
// ============================================================================

/**
 * Create a selection manager.
 *
 * @example
 * ```typescript
 * const selection = createSelectionManager();
 *
 * // Select a block
 * selection.select('block-1');
 *
 * // Multi-select with Ctrl/Cmd click
 * selection.toggle('block-2');
 *
 * // Range select with Shift click
 * selection.extendTo('block-5', ['block-1', 'block-2', 'block-3', 'block-4', 'block-5']);
 *
 * // Listen for changes
 * selection.subscribe((state) => {
 *   console.log('Selected:', [...state.selectedIds]);
 * });
 * ```
 */
export function createSelectionManager(): SelectionManager {
  let state: SelectionState = {
    selectedIds: new Set(),
    anchorId: null,
    focusId: null,
  };

  const listeners = new Set<SelectionListener>();

  function notify(): void {
    for (const listener of listeners) {
      try {
        listener(state);
      } catch (err) {
        console.error("Error in selection listener:", err);
      }
    }
  }

  function setState(newState: SelectionState): void {
    state = newState;
    notify();
  }

  return {
    getState(): SelectionState {
      return state;
    },

    select(blockId: string): void {
      setState({
        selectedIds: new Set([blockId]),
        anchorId: blockId,
        focusId: blockId,
      });
    },

    toggle(blockId: string): void {
      const newIds = new Set(state.selectedIds);
      if (newIds.has(blockId)) {
        newIds.delete(blockId);
        // Update anchor/focus if we removed them
        const remaining = [...newIds];
        setState({
          selectedIds: newIds,
          anchorId: state.anchorId === blockId ? (remaining[0] ?? null) : state.anchorId,
          focusId: remaining.length > 0 ? remaining[remaining.length - 1] : null,
        });
      } else {
        newIds.add(blockId);
        setState({
          selectedIds: newIds,
          anchorId: state.anchorId ?? blockId,
          focusId: blockId,
        });
      }
    },

    extendTo(blockId: string, orderedIds: string[]): void {
      if (!state.anchorId) {
        // No anchor, just select this block
        this.select(blockId);
        return;
      }

      const anchorIndex = orderedIds.indexOf(state.anchorId);
      const targetIndex = orderedIds.indexOf(blockId);

      if (anchorIndex === -1 || targetIndex === -1) {
        // Block not in ordered list, just select it
        this.select(blockId);
        return;
      }

      const start = Math.min(anchorIndex, targetIndex);
      const end = Math.max(anchorIndex, targetIndex);
      const rangeIds = orderedIds.slice(start, end + 1);

      setState({
        selectedIds: new Set(rangeIds),
        anchorId: state.anchorId,
        focusId: blockId,
      });
    },

    add(blockId: string): void {
      if (state.selectedIds.has(blockId)) {
        return;
      }
      const newIds = new Set(state.selectedIds);
      newIds.add(blockId);
      setState({
        selectedIds: newIds,
        anchorId: state.anchorId ?? blockId,
        focusId: blockId,
      });
    },

    remove(blockId: string): void {
      if (!state.selectedIds.has(blockId)) {
        return;
      }
      const newIds = new Set(state.selectedIds);
      newIds.delete(blockId);
      const remaining = [...newIds];
      setState({
        selectedIds: newIds,
        anchorId: state.anchorId === blockId ? (remaining[0] ?? null) : state.anchorId,
        focusId: state.focusId === blockId ? (remaining[remaining.length - 1] ?? null) : state.focusId,
      });
    },

    clear(): void {
      if (state.selectedIds.size === 0) {
        return;
      }
      setState({
        selectedIds: new Set(),
        anchorId: null,
        focusId: null,
      });
    },

    isSelected(blockId: string): boolean {
      return state.selectedIds.has(blockId);
    },

    isEmpty(): boolean {
      return state.selectedIds.size === 0;
    },

    count(): number {
      return state.selectedIds.size;
    },

    subscribe(listener: SelectionListener): () => void {
      listeners.add(listener);
      // Immediately notify with current state
      listener(state);
      return () => {
        listeners.delete(listener);
      };
    },

    dispose(): void {
      listeners.clear();
      state = {
        selectedIds: new Set(),
        anchorId: null,
        focusId: null,
      };
    },
  };
}

// ============================================================================
// DOM Helpers
// ============================================================================

/**
 * CSS class names for selection states.
 */
export const SELECTION_CLASSES = {
  selected: "ev2-block--selected",
  anchor: "ev2-block--anchor",
  focus: "ev2-block--focus",
} as const;

/**
 * Apply selection state to a block element.
 */
export function applySelectionClasses(
  element: HTMLElement,
  blockId: string,
  state: SelectionState,
): void {
  const isSelected = state.selectedIds.has(blockId);
  const isAnchor = state.anchorId === blockId;
  const isFocus = state.focusId === blockId;

  element.classList.toggle(SELECTION_CLASSES.selected, isSelected);
  element.classList.toggle(SELECTION_CLASSES.anchor, isAnchor);
  element.classList.toggle(SELECTION_CLASSES.focus, isFocus);
}

/**
 * Handle click event for block selection.
 */
export function handleSelectionClick(
  event: MouseEvent,
  blockId: string,
  selection: SelectionManager,
  orderedIds: string[],
): void {
  // Prevent default to avoid text selection
  event.preventDefault();

  if (event.shiftKey && selection.getState().anchorId) {
    // Range select
    selection.extendTo(blockId, orderedIds);
  } else if (event.metaKey || event.ctrlKey) {
    // Toggle select (Cmd/Ctrl + click)
    selection.toggle(blockId);
  } else {
    // Single select
    selection.select(blockId);
  }
}
