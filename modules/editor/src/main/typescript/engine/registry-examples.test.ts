// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Verifies that every hand-curated `ComponentDefinition.examples` fragment is
 * structurally valid: nodes/slots cross-reference correctly, no cycles, every
 * referenced node type is registered, and parent-child types respect each
 * component's `allowedChildren` rules.
 *
 * Also enforces COVERAGE: every registered component (including mount-time
 * registrations like the stencil) must declare at least one example — the
 * examples are the canonical AI-facing usage contract served by the MCP
 * server's `list_component_types` / `get_component_type` tools, so a
 * component without one ships undocumented.
 *
 * Catches authoring slips (mistyped IDs, forgotten slot, wrong child type)
 * before the JSON dump ships.
 */

import { describe, it, expect } from 'vitest';
import { buildIndexes } from './indexes.js';
import { createDefaultRegistry, liftExampleFragment, type ComponentExample } from './registry.js';
import { createStencilDefinition } from '../components/stencil/stencil-registration.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

const registry = createDefaultRegistry();
// The stencil is registered at mount time (lib.ts) rather than in
// createDefaultRegistry; register it here so its examples are validated and
// counted toward coverage, matching the set the registry dump serializes.
registry.register(createStencilDefinition({ callbacks: null }));

/**
 * Components exempt from the at-least-one-example requirement. Must stay
 * EMPTY unless a component is genuinely impossible to exemplify (abstract /
 * internal-only, never serialized into a document). Do not add entries just
 * to silence the coverage test — write the example instead; missing examples
 * are a PR blocker (see CLAUDE.md "Editor component registrations").
 */
const EXAMPLE_EXEMPT_TYPES: ReadonlySet<string> = new Set([]);

describe('example coverage', () => {
  it('every registered component declares at least one example', () => {
    const missing = registry
      .all()
      .filter((def) => !EXAMPLE_EXEMPT_TYPES.has(def.type))
      .filter((def) => (def.examples ?? []).length === 0)
      .map((def) => def.type);
    expect(
      missing,
      `components without examples[]: ${missing.join(', ')} — every ComponentDefinition must ship at least one usage example (issue #677)`,
    ).toEqual([]);
  });
});

/**
 * Wrap an example fragment in a minimal valid TemplateDocument by adding a
 * synthetic root node whose child is the example's rootNodeId. Lets us run
 * `buildIndexes` (which otherwise requires `doc.root` to be reachable) on
 * the fragment as if it were a real document.
 */
function wrapInRoot(example: ComponentExample): TemplateDocument {
  const rootNodeId = 'n-test-wrap-root' as NodeId;
  const rootSlotId = 's-test-wrap-children' as SlotId;
  const fragment = liftExampleFragment(example.fragment);
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
      ...fragment.nodes,
    },
    slots: {
      [rootSlotId]: {
        id: rootSlotId,
        nodeId: rootNodeId,
        name: 'children',
        children: [fragment.rootNodeId],
      },
      ...fragment.slots,
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
