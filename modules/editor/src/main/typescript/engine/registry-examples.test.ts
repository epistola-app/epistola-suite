/**
 * Verifies that every hand-curated `ComponentDefinition.examples` fragment is
 * structurally valid: nodes/slots cross-reference correctly, no cycles, every
 * referenced node type is registered, and parent-child types respect each
 * component's `allowedChildren` rules.
 *
 * Catches authoring slips (mistyped IDs, forgotten slot, wrong child type)
 * before the JSON dump ships.
 */

import { describe, it, expect } from 'vitest';
import { buildIndexes } from './indexes.js';
import { createDefaultRegistry, type ComponentExample } from './registry.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

const registry = createDefaultRegistry();

/**
 * Wrap an example fragment in a minimal valid TemplateDocument by adding a
 * synthetic root node whose child is the example's rootNodeId. Lets us run
 * `buildIndexes` (which otherwise requires `doc.root` to be reachable) on
 * the fragment as if it were a real document.
 */
function wrapInRoot(example: ComponentExample): TemplateDocument {
  const rootNodeId = 'n-test-wrap-root' as NodeId;
  const rootSlotId = 's-test-wrap-children' as SlotId;
  return {
    modelVersion: 1,
    root: rootNodeId,
    themeRef: { type: 'inherit' },
    nodes: {
      [rootNodeId]: {
        id: rootNodeId,
        type: 'root',
        slots: [rootSlotId],
      },
      ...example.fragment.nodes,
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootNodeId,
        name: 'children',
        children: [example.fragment.rootNodeId],
      },
      ...example.fragment.slots,
    },
  };
}

describe('component examples', () => {
  for (const def of registry.all()) {
    const examples = def.examples ?? [];
    if (examples.length === 0) continue;

    describe(def.type, () => {
      for (const example of examples) {
        describe(example.name, () => {
          it('rootNodeId is present in fragment.nodes', () => {
            expect(example.fragment.nodes[example.fragment.rootNodeId]).toBeDefined();
          });

          it('rootNodeId node has the matching component type', () => {
            const node = example.fragment.nodes[example.fragment.rootNodeId];
            expect(node?.type).toBe(def.type);
          });

          it('every slot.nodeId references a node in fragment.nodes', () => {
            for (const slot of Object.values(example.fragment.slots)) {
              expect(
                example.fragment.nodes[slot.nodeId],
                `slot '${slot.id}' has nodeId '${slot.nodeId}' not in fragment.nodes`,
              ).toBeDefined();
            }
          });

          it('every slot.children id is a node in fragment.nodes', () => {
            for (const slot of Object.values(example.fragment.slots)) {
              for (const childId of slot.children) {
                expect(
                  example.fragment.nodes[childId],
                  `slot '${slot.id}' has child '${childId}' not in fragment.nodes`,
                ).toBeDefined();
              }
            }
          });

          it('every node.slots[] entry is a slot in fragment.slots', () => {
            for (const node of Object.values(example.fragment.nodes)) {
              for (const slotId of node.slots) {
                expect(
                  example.fragment.slots[slotId],
                  `node '${node.id}' references slot '${slotId}' not in fragment.slots`,
                ).toBeDefined();
              }
            }
          });

          it('every referenced node.type is a registered component', () => {
            for (const node of Object.values(example.fragment.nodes)) {
              expect(
                registry.has(node.type),
                `node '${node.id}' has unknown type '${node.type}'`,
              ).toBe(true);
            }
          });

          it('buildIndexes succeeds (no cycles, all nodes reachable)', () => {
            // wrapInRoot connects the example's rootNodeId to a synthetic
            // document root, so any orphaned node inside the fragment causes
            // buildIndexes to throw "not connected to root".
            expect(() => buildIndexes(wrapInRoot(example))).not.toThrow();
          });

          it('respects allowedChildren at every parent-child boundary', () => {
            for (const parentNode of Object.values(example.fragment.nodes)) {
              for (const slotId of parentNode.slots) {
                const slot = example.fragment.slots[slotId];
                if (!slot) continue;
                for (const childId of slot.children) {
                  const child = example.fragment.nodes[childId];
                  if (!child) continue;
                  expect(
                    registry.canContain(parentNode.type, child.type),
                    `${parentNode.type} cannot contain ${child.type} (slot '${slot.name}')`,
                  ).toBe(true);
                }
              }
            }
          });
        });
      }
    });
  }
});
