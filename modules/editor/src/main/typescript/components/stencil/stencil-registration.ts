/**
 * Stencil component definition for the component registry.
 *
 * A stencil is a container-like node that wraps a copy of a published stencil
 * version's content. The node's props carry the stencil reference metadata
 * (stencilId + version) for tracking origin and detecting available upgrades.
 *
 * On the canvas, the stencil renders its children normally but with a visual
 * badge indicating the stencil name and version. When stencilId is null, the
 * stencil is "unpublished" — content lives only in the template until the user
 * explicitly publishes it.
 */

import type { ComponentDefinition } from '../../engine/registry.js';
import type { Node, Slot, NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';
import { openStencilPickerDialog } from './stencil-picker-dialog.js';
import { reKeyContent } from './rekey-content.js';
import { nanoid } from 'nanoid';
import { html } from 'lit';

export interface StencilOptions {
  /** Null when stencil operations aren't wired — component still renders but browse is disabled. */
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
    defaultProps: {
      stencilId: null,
      version: null,
    },

    renderCanvas: ({ node }) => {
      const stencilId = node.props?.stencilId as string | null;
      const version = node.props?.version as number | null;

      if (!stencilId) {
        return html`<div class="canvas-stencil-badge canvas-stencil-badge--unpublished">
          <span class="canvas-stencil-badge-label">Unpublished stencil</span>
        </div>`;
      }

      return html`<div class="canvas-stencil-badge">
        <span class="canvas-stencil-badge-label">${stencilId} v${version ?? '?'}</span>
      </div>`;
    },

    onBeforeInsert: async () => {
      if (!options.callbacks) {
        // No callbacks — create empty stencil container
        return {};
      }

      const result = await openStencilPickerDialog(options.callbacks);
      if (!result) return null; // Cancelled

      if (result.action === 'create-new') {
        // Empty stencil — no stencilId/version yet
        return {};
      }

      // Use existing stencil — store content temporarily for createSubtree
      return {
        stencilId: result.versionInfo.stencilId,
        version: result.versionInfo.version,
        _content: result.versionInfo.content,
      };
    },

    createSubtree: (nodeId: NodeId, props?: Record<string, unknown>) => {
      const content = props?._content as TemplateDocument | undefined;

      // Remove transient _content from persisted props
      if (props) {
        delete props._content;
      }

      if (content) {
        // Deep-copy the stencil version's content with fresh IDs
        const reKeyed = reKeyContent(content);

        const slotId = nanoid() as SlotId;
        const parentSlot: Slot = {
          id: slotId,
          nodeId,
          name: 'children',
          children: reKeyed.childNodeIds,
        };

        return {
          slots: [parentSlot],
          extraNodes: reKeyed.nodes,
          extraSlots: reKeyed.slots,
        };
      }

      // Empty stencil — just create an empty slot
      const slotId = nanoid() as SlotId;
      return {
        slots: [
          {
            id: slotId,
            nodeId,
            name: 'children',
            children: [] as NodeId[],
          },
        ],
        extraNodes: [] as Node[],
        extraSlots: [] as Slot[],
      };
    },
  };
}
