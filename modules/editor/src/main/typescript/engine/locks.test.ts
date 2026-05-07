/**
 * Tests for the generic slot-lock mechanism.
 *
 * Uses synthetic component definitions (not real stencil/placeholder ones) so
 * the test exercises the engine primitive in isolation, proving the engine
 * has no domain-specific knowledge of what "locked" means.
 */

import { describe, it, expect } from 'vitest';
import { isSlotLocked } from './locks.js';
import { ComponentRegistry } from './registry.js';
import { buildIndexes } from './indexes.js';
import type { NodeId, SlotId, TemplateDocument } from '../types/index.js';

/**
 * Build a minimal registry with three test components:
 *  - `root`        — generic root container, no lock predicates.
 *  - `lockable`    — lock fires when `props.locked === true`.
 *  - `editable`    — slot has `editable: true`, breaking inherited locks.
 *  - `child`       — leaf, no slots.
 */
function makeRegistry(): ComponentRegistry {
  const registry = new ComponentRegistry();
  registry.register({
    type: 'root',
    label: 'Root',
    category: 'layout',
    slots: [{ name: 'children' }],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  });
  registry.register({
    type: 'lockable',
    label: 'Lockable',
    category: 'layout',
    slots: [
      {
        name: 'body',
        locked: (node) => node.props?.locked === true,
      },
    ],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  });
  registry.register({
    type: 'editable',
    label: 'EditableHole',
    category: 'layout',
    slots: [
      // First slot inherits the surrounding lock.
      { name: 'inner-default' },
      // Second slot explicitly breaks the lock chain.
      { name: 'inner-fill', editable: true },
    ],
    allowedChildren: { mode: 'all' },
    applicableStyles: 'all',
    inspector: [],
  });
  registry.register({
    type: 'child',
    label: 'Child',
    category: 'content',
    slots: [],
    allowedChildren: { mode: 'none' },
    applicableStyles: 'all',
    inspector: [],
  });
  return registry;
}

function makeDoc(opts: {
  /** Whether the lockable's lock predicate should fire. */
  outerLocked: boolean;
  /** Optional inner editable node holding two slots. */
  withEditableHole?: boolean;
}): TemplateDocument {
  const baseNodes: Record<string, unknown> = {
    root: { id: 'root', type: 'root', slots: ['root-slot'] },
    lockable: {
      id: 'lockable',
      type: 'lockable',
      slots: ['lockable-body'],
      props: { locked: opts.outerLocked },
    },
  };
  const baseSlots: Record<string, unknown> = {
    'root-slot': { id: 'root-slot', nodeId: 'root', name: 'children', children: ['lockable'] },
    'lockable-body': {
      id: 'lockable-body',
      nodeId: 'lockable',
      name: 'body',
      children: opts.withEditableHole ? ['hole'] : [],
    },
  };
  if (opts.withEditableHole) {
    baseNodes['hole'] = {
      id: 'hole',
      type: 'editable',
      slots: ['hole-default', 'hole-fill'],
    };
    baseSlots['hole-default'] = {
      id: 'hole-default',
      nodeId: 'hole',
      name: 'inner-default',
      children: [],
    };
    baseSlots['hole-fill'] = {
      id: 'hole-fill',
      nodeId: 'hole',
      name: 'inner-fill',
      children: [],
    };
  }
  return {
    modelVersion: 1,
    root: 'root' as NodeId,
    nodes: baseNodes as TemplateDocument['nodes'],
    slots: baseSlots as TemplateDocument['slots'],
    themeRef: { type: 'inherit' },
  };
}

describe('isSlotLocked', () => {
  const registry = makeRegistry();

  it('returns false for slots whose template has no predicate', () => {
    const doc = makeDoc({ outerLocked: false });
    expect(isSlotLocked(doc, 'root-slot' as SlotId, buildIndexes(doc), registry)).toBe(false);
  });

  it('returns true when a slot template locks itself', () => {
    const doc = makeDoc({ outerLocked: true });
    expect(isSlotLocked(doc, 'lockable-body' as SlotId, buildIndexes(doc), registry)).toBe(true);
  });

  it('returns false when the lock predicate does not fire', () => {
    const doc = makeDoc({ outerLocked: false });
    expect(isSlotLocked(doc, 'lockable-body' as SlotId, buildIndexes(doc), registry)).toBe(false);
  });

  it('inherits lock from a parent slot', () => {
    const doc = makeDoc({ outerLocked: true, withEditableHole: true });
    // The hole is inside the lockable's locked body. Its `inner-default` slot
    // has no predicate, so it inherits the outer lock.
    expect(isSlotLocked(doc, 'hole-default' as SlotId, buildIndexes(doc), registry)).toBe(true);
  });

  it('breaks the lock chain on `editable: true`', () => {
    const doc = makeDoc({ outerLocked: true, withEditableHole: true });
    // The `inner-fill` slot has editable: true, so even though the outer
    // lockable is locked, drops here are allowed.
    expect(isSlotLocked(doc, 'hole-fill' as SlotId, buildIndexes(doc), registry)).toBe(false);
  });

  it('inside-out walk: the first predicate that fires wins', () => {
    // Same doc as above, but with the outer lock OFF — the editable slot's
    // explicit `editable: true` simply confirms editable; nothing's locked.
    const doc = makeDoc({ outerLocked: false, withEditableHole: true });
    expect(isSlotLocked(doc, 'hole-default' as SlotId, buildIndexes(doc), registry)).toBe(false);
    expect(isSlotLocked(doc, 'hole-fill' as SlotId, buildIndexes(doc), registry)).toBe(false);
  });

  it('returns false for unknown slot ids (defensive)', () => {
    const doc = makeDoc({ outerLocked: true });
    expect(isSlotLocked(doc, 'nope' as SlotId, buildIndexes(doc), registry)).toBe(false);
  });
});
