// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { buildParameterScope } from './parameter-scope.js';
import { clearParameterCache } from './parameter-evaluation-cache.js';
import { ExpressionNodeView } from '../prosemirror/ExpressionNodeView.js';
import type { Node } from '../types/index.js';
import { nodeId } from './test-helpers.js';
import { materializeScopeDeclaration } from './schema-scopes.js';
import { buildIterationScope } from './scoped-fields.js';

function makeStencilNode(
  schema: Record<string, unknown>,
  bindings: Record<string, string> = {},
  alias?: string,
): Node {
  return {
    id: nodeId('stencil'),
    type: 'stencil',
    slots: [],
    props: {
      stencilId: 'test',
      catalogKey: 'default',
      version: 1,

      parameterSchemaSnapshot: schema,
      ...(Object.keys(bindings).length > 0 ? { parameterBindings: bindings } : {}),
      ...(alias ? { paramsAlias: alias } : {}),
    },
  };
}

describe('buildParameterScope', () => {
  beforeEach(() => {
    clearParameterCache();
  });

  it('returns null when the node has no schema snapshot', () => {
    const node: Node = { id: nodeId('s'), type: 'stencil', slots: [] };
    expect(buildParameterScope(node, {})).toBeNull();
  });

  it('exposes declared parameters as scoped FieldPath entries', () => {
    const node = makeStencilNode({
      type: 'object',
      properties: {
        recipientName: { type: 'string', description: 'Recipient' },
        pageCount: { type: 'integer' },
      },
    });
    const declaration = buildParameterScope(node, {})!;
    const scope = materializeScopeDeclaration(declaration, { bindings: {} });
    expect(scope).not.toBeNull();
    const paths = scope.variables.map((fp) => fp.path).toSorted();
    expect(paths).toEqual(['params.pageCount', 'params.recipientName']);
  });

  it('marks declared parameters as stencil parameter scope', () => {
    const node = makeStencilNode({
      type: 'object',
      properties: {
        recipientName: { type: 'string', description: 'Recipient' },
      },
    });
    const declaration = buildParameterScope(node, {})!;
    const scope = materializeScopeDeclaration(declaration, { bindings: {} });

    expect(scope.variables[0]?.scope).toBe('params');
    expect(scope.variables[0]?.scopeKind).toBe('stencil-parameter');
  });

  it('uses the configured paramsAlias for scope variable paths', () => {
    const node = makeStencilNode(
      { type: 'object', properties: { title: { type: 'string' } } },
      {},
      'letter',
    );
    const declaration = buildParameterScope(node, {})!;
    const scope = materializeScopeDeclaration(declaration, { bindings: {} });
    expect(scope.variables[0].path).toBe('letter.title');
  });

  it('provides a schema root that nested loops can navigate', () => {
    const node = makeStencilNode({
      type: 'object',
      properties: {
        groups: {
          type: 'array',
          items: {
            type: 'object',
            properties: {
              members: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: { name: { type: 'string' } },
                },
              },
            },
          },
        },
      },
    });
    const paramsDeclaration = buildParameterScope(node, {})!;
    const params = materializeScopeDeclaration(paramsDeclaration, { bindings: {} });
    const groupDeclaration = buildIterationScope(
      {
        id: nodeId('groups'),
        type: 'loop',
        slots: [],
        props: { expression: { raw: 'params.groups' }, itemAlias: 'group' },
      },
      {},
    )!;
    const group = materializeScopeDeclaration(groupDeclaration, params.environment);
    const memberDeclaration = buildIterationScope(
      {
        id: nodeId('members'),
        type: 'loop',
        slots: [],
        props: { expression: { raw: 'group.members' }, itemAlias: 'member' },
      },
      {},
    )!;
    const member = materializeScopeDeclaration(memberDeclaration, group.environment);

    expect(member.variables).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ path: 'member', type: 'object' }),
        expect.objectContaining({ path: 'member.name', type: 'string' }),
      ]),
    );
  });

  it('returns synthetic <name> placeholder when there is no binding and no default', () => {
    const node = makeStencilNode({
      type: 'object',
      properties: { recipientName: { type: 'string' } },
    });
    const scope = buildParameterScope(node, { evaluationContext: {} });
    expect((scope!.evaluationData!.params as Record<string, unknown>).recipientName).toBe(
      '<recipientName>',
    );
  });

  it('returns the schema default when no binding is set', () => {
    const node = makeStencilNode({
      type: 'object',
      properties: { recipientName: { type: 'string', default: 'Anonymous' } },
    });
    const scope = buildParameterScope(node, { evaluationContext: {} });
    expect((scope!.evaluationData!.params as Record<string, unknown>).recipientName).toBe(
      'Anonymous',
    );
  });

  it('resolves a JSONata literal binding asynchronously, then refreshes chips', async () => {
    const refreshSpy = vi.spyOn(ExpressionNodeView, 'refreshAll').mockImplementation(() => {});
    const node = makeStencilNode(
      { type: 'object', properties: { p: { type: 'string' } } },
      { p: "'p2'" },
    );
    // First call: cache miss, kicks off async eval, returns synthetic placeholder.
    let scope = buildParameterScope(node, { evaluationContext: {} });
    expect((scope!.evaluationData!.params as Record<string, unknown>).p).toBe('<p>');

    // Wait for the async eval to settle.
    await vi.waitFor(() => expect(refreshSpy).toHaveBeenCalled());

    // Second call: cache hit, returns the resolved literal.
    scope = buildParameterScope(node, { evaluationContext: {} });
    expect((scope!.evaluationData!.params as Record<string, unknown>).p).toBe('p2');

    refreshSpy.mockRestore();
  });

  it('resolves a sys.* path binding against the outer evaluation context', async () => {
    const refreshSpy = vi.spyOn(ExpressionNodeView, 'refreshAll').mockImplementation(() => {});
    const node = makeStencilNode(
      { type: 'object', properties: { totalPages: { type: 'integer' } } },
      { totalPages: 'sys.pages.total' },
    );
    const evaluationContext = { sys: { pages: { total: 5 } } };

    // Kick off async eval.
    buildParameterScope(node, { evaluationContext });

    await vi.waitFor(() => expect(refreshSpy).toHaveBeenCalled());

    // After resolution, the cache should hold the value.
    const scope = buildParameterScope(node, { evaluationContext });
    expect((scope!.evaluationData!.params as Record<string, unknown>).totalPages).toBe(5);

    refreshSpy.mockRestore();
  });

  it('resolves expression concatenation against the outer context', async () => {
    const refreshSpy = vi.spyOn(ExpressionNodeView, 'refreshAll').mockImplementation(() => {});
    const node = makeStencilNode(
      { type: 'object', properties: { greeting: { type: 'string' } } },
      { greeting: "'Hello ' & customer.name" },
    );
    const evaluationContext = { customer: { name: 'Alice' } };
    buildParameterScope(node, { evaluationContext });

    await vi.waitFor(() => expect(refreshSpy).toHaveBeenCalled());

    const scope = buildParameterScope(node, { evaluationContext });
    expect((scope!.evaluationData!.params as Record<string, unknown>).greeting).toBe('Hello Alice');

    refreshSpy.mockRestore();
  });
});
