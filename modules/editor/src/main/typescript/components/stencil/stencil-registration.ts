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
import type { EditorEngine } from '../../engine/EditorEngine.js';
import type { Node, Slot, NodeId, SlotId, TemplateDocument } from '../../types/index.js';
import type { StencilCallbacks } from './types.js';
import { openStencilPickerDialog } from './stencil-picker-dialog.js';
import { reKeyContent } from './rekey-content.js';
import './StencilInspector.js';
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
    getLabel: (node, eng) => {
      const engine = eng as EditorEngine;
      const stencilId = node.props?.stencilId as string | null;
      const version = node.props?.version as number | null;
      const isDraft = node.props?.isDraft as boolean | undefined;

      if (!stencilId) return 'Stencil';

      const upgrades = engine.getComponentState<Record<string, number>>('stencil:upgrades');
      const latestVersion = upgrades?.[stencilId];
      const hasUpgrade =
        latestVersion != null && version != null && latestVersion > version && !isDraft;

      if (isDraft) return `Stencil: ${stencilId} — editing draft`;
      if (hasUpgrade) return `Stencil: ${stencilId} v${version} ⬆ v${latestVersion}`;
      return `Stencil: ${stencilId} v${version}`;
    },
    icon: 'puzzle',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'denylist', types: ['stencil'] },
    applicableStyles: 'all',
    inspector: [],
    defaultProps: {
      stencilId: null,
      catalogKey: null,
      version: null,
      isDraft: false,
    },

    renderCanvas: ({ node, renderSlot }) => {
      const stencilId = node.props?.stencilId as string | null;
      const isDraft = node.props?.isDraft as boolean | undefined;
      const isLocked = stencilId !== null && !isDraft;

      // IMPORTANT: Template structure must be identical across all states.
      // Lit's template diffing caches by template string shape. The
      // locked/editable difference is CSS-only. State info (name, version,
      // upgrade indicator) is in the block header via getLabel.
      return html`
        <div class=${isLocked ? 'canvas-stencil-locked' : 'canvas-stencil-content'}>
          ${node.slots.map((slotId) => renderSlot(slotId))}
        </div>
      `;
    },

    renderInspector: ({ node, engine: eng }) => {
      const engine = eng as EditorEngine;
      return html`<stencil-inspector
        .node=${node}
        .engine=${engine}
        .callbacks=${options.callbacks}
      ></stencil-inspector>`;
    },

    onBeforeInsert: async () => {
      if (!options.callbacks) {
        // No callbacks — create empty stencil container
        return {};
      }

      const result = await openStencilPickerDialog(options.callbacks);
      if (!result) return null; // Cancelled

      if (result.action === 'create-new') {
        // Stencil entity created on backend — insert with stencilId in draft mode
        return {
          stencilId: result.ref.stencilId,
          catalogKey: result.ref.catalogKey,
          version: result.version,
          isDraft: true,
        };
      }

      // Use existing stencil — store content temporarily for createSubtree
      return {
        stencilId: result.versionInfo.ref.stencilId,
        catalogKey: result.versionInfo.ref.catalogKey,
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
