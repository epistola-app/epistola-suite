# uhtml + Stimulus Architecture (vanilla-editor)

This document explains why `@epistola/vanilla-editor` uses both `uhtml` and `Stimulus`, and how they work with SortableJS.

## Why this stack exists

The editor has three core concerns:

- Render nested block trees efficiently
- Attach behavior to dynamic DOM nodes (controllers, events, editor lifecycle)
- Support drag/drop with SortableJS without DOM/state drift

No single library here solves all three cleanly, so responsibilities are split.

## What uhtml does

`uhtml` is used for declarative block/canvas rendering.

- Templates define block structure in `renderer.ts`
- Re-renders happen from editor state changes
- Main benefit: avoids manual `createElement/appendChild` code for the full block tree

uhtml is the primary rendering layer for:

- block wrappers
- block headers and content regions
- nested containers/rows/cells layouts

## What Stimulus does

Stimulus is the behavior/lifecycle layer.

- `connect()`/`disconnect()` hooks for controller-managed resources
- Target/action wiring via `data-controller`, `data-target`, `data-action`
- Reconnects behavior after uhtml-driven DOM updates

Stimulus is used for:

- editor shell actions (save/undo/redo/add)
- text block TipTap lifecycle
- expression input interactions

## Why we still use `document.createElement`

Even with Stimulus, we still use imperative DOM creation in focused places.

Reason: Stimulus does not replace DOM construction; it manages behavior and lifecycle.

`createElement` is still useful for:

- short-lived, highly dynamic UI fragments (suggestion dropdown items, popovers)
- precise keyboard/mouse interaction handling
- incremental updates where full template re-render would be overkill

In short:

- uhtml = render structure
- Stimulus = lifecycle/behavior wiring
- createElement = targeted imperative sub-UI when that is simpler

## How this works with SortableJS

Sortable mutates DOM during drag operations. The editor state is still the source of truth.

Current pattern:

1. Destroy Sortable instances before re-render
2. Re-render DOM from state (uhtml)
3. Re-initialize Sortable on the fresh DOM

This avoids stale Sortable references and prevents mismatch between state tree and DOM order.

## Styling boundary rule

Resolved block styles are applied to `.block-content` only.

Editor chrome remains stable outside style cascade:

- block headers
- controls (`.block-ui`)
- drag/delete buttons

This keeps WYSIWYG content styling from leaking into editor controls.

## Trade-offs

Pros:

- lightweight stack
- clear separation of rendering vs behavior
- good fit for SortableJS integration

Cons:

- mixed declarative + imperative patterns require discipline
- controller/runtime contracts must be well documented

## Practical guidance

- Use uhtml for block/tree structure updates.
- Use Stimulus for event/lifecycle orchestration.
- Use `createElement` only for localized dynamic widgets.
- Keep drag/drop compatibility tied to declared drop containers from headless core.
