import { describe, expect, it } from 'vitest';
import type { Node, Slot, TemplateDocument } from '../../types/index.js';
import { replaceStencilSlotContent } from './replace-content.js';

function createEngineWithHostSlot(initialChildren: string[]) {
  const hostSlot = {
    id: 'host-slot',
    nodeId: 'stencil-node',
    name: 'children',
    children: [...initialChildren],
  } as unknown as Slot;

  const doc = {
    slots: {
      'host-slot': hostSlot,
    } as Record<string, Slot>,
  };

  const commands: Array<Record<string, unknown>> = [];

  const engine = {
    doc,
    dispatch(command: Record<string, unknown>) {
      commands.push(command);

      if (command.type === 'RemoveNode') {
        const nodeId = command.nodeId as string;
        doc.slots['host-slot']!.children = doc.slots['host-slot']!.children.filter((id) => id !== nodeId);
      }

      if (command.type === 'InsertNode') {
        const targetSlotId = command.targetSlotId as string;
        const node = command.node as Node;
        doc.slots[targetSlotId]!.children.push(node.id as never);
      }

      return { ok: true };
    },
  };

  return { engine, commands };
}

function emptyContent(): TemplateDocument {
  return {
    modelVersion: 1,
    root: 'root',
    nodes: {
      root: { id: 'root', type: 'root', slots: ['root-slot'] },
    },
    slots: {
      'root-slot': { id: 'root-slot', nodeId: 'root', name: 'children', children: [] },
    },
    themeRef: { type: 'inherit' },
  } as unknown as TemplateDocument;
}

describe('replaceStencilSlotContent', () => {
  it('removes existing slot children, then inserts re-keyed top-level nodes', () => {
    const { engine, commands } = createEngineWithHostSlot(['old-1', 'old-2']);
    const stencilNode = { id: 'stencil-node', slots: ['host-slot'] } as unknown as Pick<Node, 'slots'>;

    const childA = { id: 'new-a', type: 'text', slots: [] } as unknown as Node;
    const childB = { id: 'new-b', type: 'container', slots: ['slot-b'] } as unknown as Node;
    const nested = { id: 'new-b-1', type: 'text', slots: [] } as unknown as Node;
    const slotB = {
      id: 'slot-b',
      nodeId: 'new-b',
      name: 'children',
      children: ['new-b-1'],
    } as unknown as Slot;

    const fakeRekey = () => ({
      childNodeIds: ['new-a', 'new-b'],
      nodes: [childA, childB, nested],
      slots: [slotB],
    });

    replaceStencilSlotContent(
      engine as never,
      stencilNode,
      emptyContent(),
      fakeRekey as never,
    );

    expect(commands.slice(0, 2)).toEqual([
      { type: 'RemoveNode', nodeId: 'old-1' },
      { type: 'RemoveNode', nodeId: 'old-2' },
    ]);

    const insertCommands = commands.filter((cmd) => cmd.type === 'InsertNode');
    expect(insertCommands).toHaveLength(2);

    expect(insertCommands[0]).toMatchObject({
      node: childA,
      targetSlotId: 'host-slot',
      index: -1,
      _restoreNodes: undefined,
    });

    expect(insertCommands[1]).toMatchObject({
      node: childB,
      targetSlotId: 'host-slot',
      index: -1,
      _restoreNodes: [nested],
    });
  });

  it('skips missing child ids without throwing', () => {
    const { engine, commands } = createEngineWithHostSlot([]);
    const stencilNode = { id: 'stencil-node', slots: ['host-slot'] } as unknown as Pick<Node, 'slots'>;
    const child = { id: 'new-a', type: 'text', slots: [] } as unknown as Node;

    const fakeRekey = () => ({
      childNodeIds: ['missing', 'new-a'],
      nodes: [child],
      slots: [],
    });

    expect(() => {
      replaceStencilSlotContent(
        engine as never,
        stencilNode,
        emptyContent(),
        fakeRekey as never,
      );
    }).not.toThrow();

    const insertCommands = commands.filter((cmd) => cmd.type === 'InsertNode');
    expect(insertCommands).toHaveLength(1);
    expect(insertCommands[0]).toMatchObject({ node: child });
  });

  it('is a no-op when stencil node has no slot', () => {
    const { engine, commands } = createEngineWithHostSlot(['old-1']);
    const stencilNode = { id: 'stencil-node', slots: [] } as unknown as Pick<Node, 'slots'>;

    replaceStencilSlotContent(engine as never, stencilNode, emptyContent());

    expect(commands).toHaveLength(0);
    expect(engine.doc.slots['host-slot']?.children).toEqual(['old-1']);
  });
});
