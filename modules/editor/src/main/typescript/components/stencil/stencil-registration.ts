/**
 * Stencil component definition for the component registry.
 *
 * A stencil is a container-like node that wraps a copy of a published stencil
 * version's content. The node's props carry the stencil reference metadata
 * (stencilId + version) for tracking origin and detecting available upgrades.
 *
 * On the canvas, the stencil renders its children normally but with a visual
 * badge indicating the stencil name and version.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { NodeId, SlotId } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';
import { html } from 'lit';

export interface StencilOptions {
  /** Null when stencil operations aren't wired — component still renders but insert is disabled. */
  callbacks: StencilCallbacks | null;
}

export function createStencilDefinition(options: StencilOptions): ComponentDefinition {
  return {
    type: 'stencil',
    label: 'Stencil',
    icon: 'puzzle',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [
      { key: 'stencilId', label: 'Stencil ID', type: 'text' },
      { key: 'version', label: 'Version', type: 'number' },
    ],
    // Hide from block palette when callbacks aren't wired (read-only stencil rendering)
    hidden: !options.callbacks,
    defaultProps: {
      stencilId: null,
      version: null,
    },

    renderCanvas: ({ node }) => {
      const stencilId = node.props?.stencilId as string | null;
      const version = node.props?.version as number | null;

      if (!stencilId) {
        return html`<div class="canvas-stencil-empty">
          <span>Empty stencil reference</span>
        </div>`;
      }

      // The children are rendered by the engine's default slot rendering.
      // We just add a badge overlay to identify this as a stencil instance.
      return html`<div class="canvas-stencil-badge">
        <span class="canvas-stencil-badge-label">${stencilId} v${version ?? '?'}</span>
      </div>`;
    },

    onBeforeInsert: options.callbacks
      ? async () => {
          const callbacks = options.callbacks!;
          // Open stencil browser — search, pick a stencil and version, return props.
          // For now, use a simple prompt. The full browser dialog is Phase 4 step 4.
          const stencilId = prompt('Enter stencil ID:');
          if (!stencilId) return null;

          // Fetch the latest published version
          const summaries = await callbacks.searchStencils(stencilId);
          const match = summaries.find((s) => s.id === stencilId);
          if (!match || !match.latestPublishedVersion) {
            alert(`Stencil "${stencilId}" not found or has no published version.`);
            return null;
          }

          const versionInfo = await callbacks.getStencilVersion(
            stencilId,
            match.latestPublishedVersion,
          );
          if (!versionInfo) {
            alert(`Could not load stencil version.`);
            return null;
          }

          return {
            stencilId: versionInfo.stencilId,
            version: versionInfo.version,
          };
        }
      : undefined,

    // When a stencil is inserted, copy the stencil version's content as
    // child nodes inside this component's slot.
    createSubtree: (nodeId: NodeId) => {
      // createSubtree is called synchronously after onBeforeInsert returns.
      // The actual content injection from the stencil version will be handled
      // by the insert command in a later phase. For now, create an empty slot.
      const slotId = `slot-${nodeId}-children` as SlotId;
      return {
        slots: [
          {
            id: slotId,
            nodeId,
            name: 'children',
            children: [] as NodeId[],
          },
        ],
        extraNodes: [],
        extraSlots: [],
      };
    },
  };
}
