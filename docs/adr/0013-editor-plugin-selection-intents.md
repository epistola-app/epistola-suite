<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0013: Editor plugin selection intents

- **Status:** Draft — discussion record, not accepted
- **Date:** 2026-07-22
- **Discussants:** Epistola team
- **Tags:** editor, plugins, quality, ui

## Context

The Quality sidebar panel can navigate from a finding to the canvas node it references. The editor's
normal behavior is that selecting a canvas node switches the sidebar to the Inspector. That is right
for manual canvas/tree selection, but it is wrong for Quality finding navigation: the author should
stay in the Quality panel while the referenced block is revealed.

The first implementation put `keepOpenOnSelection` on the sidebar tab contribution. That kept the
Quality tab open, but it attached behavior to the tab rather than to the action. As a result, any
manual selection made while Quality was open also stayed on Quality.

The editor already uses bubbling DOM `CustomEvent`s for shell-level commands (`force-save`,
`toggle-preview`, `toggle-clean-mode`). Selection, however, is otherwise a typed engine operation
through `engine.selectNode(...)`, and plugin sidebar rendering already receives a typed
`PluginContext`.

## Options

### Option A — tab metadata

Let a sidebar tab declare that it stays open during selection.

**Pros:** small implementation and easy to understand locally.

**Cons:** the behavior leaks to unrelated selections while that tab is active. It cannot distinguish
"the Quality panel selected this finding" from "the user manually selected a block while Quality was
open."

### Option B — DOM event

Let plugin UI dispatch a bubbling custom event such as `select-node`, carrying selection behavior in
the event detail. The editor host listens and performs the selection.

**Pros:** similar to existing editor shell commands and keeps plugin UI decoupled from host
internals.

**Cons:** introduces a new stringly typed selection contract, weaker discoverability than
`PluginContext`, awkward return/error semantics, and a dependence on DOM containment and event
propagation.

### Option C — typed plugin context action

Expose a typed host action on `PluginContext`, for example
`selectNode(nodeId, { keepCurrentSidebarTabOpen, revealInCanvas })`. The editor host owns the
coordination with the sidebar and canvas.

**Pros:** behavior metadata travels with the selection action, the contract is discoverable and type
checked, and the sidebar remains unaware of plugin identities.

**Cons:** expands the plugin context API and gives plugins another host capability to use
responsibly.

## Candidate direction

Use **Option C** for plugin-driven selection intents.

The sidebar should expose only a generic one-shot primitive: preserve the active tab for the next
selection change. The editor host decides when to arm that primitive, then calls `engine.selectNode`.
If the target node is already selected, the host must not arm the one-shot preservation flag, because
there will be no selection-change event to consume it.

DOM events remain appropriate for shell commands where the UI intent is coarse and not part of the
typed plugin contract. Plugin-to-host behavior that changes editor state or surrounding UI should
prefer typed `PluginContext` actions.

## Consequences

- Quality finding navigation keeps the Quality panel open only for that plugin-driven navigation.
- Manual canvas/tree selections continue to switch the sidebar to Inspector.
- The plugin context becomes the extension point for future host-owned UI intents, without requiring
  the sidebar to know about individual plugins.
