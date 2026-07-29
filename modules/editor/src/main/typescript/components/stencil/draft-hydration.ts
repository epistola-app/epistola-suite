// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import { EditorEngine } from '../../engine/EditorEngine.js';
import type { ComponentRegistry } from '../../engine/registry.js';
import type { NodeId, TemplateDocument } from '../../types/index.js';
import { hydrateVersion } from './stencil-actions.js';
import { isStencil } from './node-types.js';
import type { StencilCallbacks, StencilVersionInfo } from './types.js';

export interface DraftRecovery {
  reason: 'missing' | 'archived' | 'unavailable';
  message: string;
}

export interface DraftHydrationResult {
  document: TemplateDocument;
  recovery: Record<string, DraftRecovery>;
}

/**
 * Hydrate exact draft references on an isolated engine. The caller can swap
 * the finished document into the visible editor as one system-origin change,
 * avoiding undo entries and autosave churn.
 */
export async function hydrateStencilDrafts(
  document: TemplateDocument,
  registry: ComponentRegistry,
  callbacks: StencilCallbacks,
): Promise<DraftHydrationResult> {
  const engine = new EditorEngine(document, registry);
  const processed = new Set<string>();
  const cache = new Map<string, Promise<StencilVersionInfo | null>>();
  const recovery: Record<string, DraftRecovery> = {};

  while (true) {
    const next = Object.values(engine.doc.nodes)
      .filter(
        (node) =>
          isStencil(node) &&
          node.props.stencilId &&
          node.props.catalogKey &&
          node.props.draftVersion != null &&
          !processed.has(`${node.id}:${node.props.draftVersion}`),
      )
      .toSorted((left, right) => depth(engine, left.id) - depth(engine, right.id))[0];
    if (!next || !isStencil(next)) break;
    const { stencilId, catalogKey, draftVersion } = next.props;
    if (!stencilId || !catalogKey || draftVersion == null) break;

    const processedKey = `${next.id}:${draftVersion}`;
    processed.add(processedKey);
    const ref = { stencilId, catalogKey };
    const cacheKey = `${catalogKey}/${stencilId}/${draftVersion}`;

    try {
      let request = cache.get(cacheKey);
      if (!request) {
        request = callbacks.getStencilVersion(ref, draftVersion);
        cache.set(cacheKey, request);
      }
      // Exact draft hydration is intentionally sequential: replacing an outer
      // stencil can introduce or remove nested draft references.
      // eslint-disable-next-line no-await-in-loop
      const version = await request;
      if (!version) {
        recovery[next.id] = {
          reason: 'missing',
          message: `Draft v${draftVersion} no longer exists. Recover the embedded copy or discard it.`,
        };
        continue;
      }
      if (version.status === 'archived') {
        recovery[next.id] = {
          reason: 'archived',
          message: `Draft v${version.version} was archived. Recover the embedded copy or discard it.`,
        };
        continue;
      }
      hydrateVersion({ engine, callbacks, stencilNodeId: next.id }, version);
    } catch (error) {
      recovery[next.id] = {
        reason: 'unavailable',
        message: error instanceof Error ? error.message : 'The exact draft could not be loaded.',
      };
    }
  }

  return { document: engine.doc, recovery };
}

function depth(engine: EditorEngine, nodeId: NodeId): number {
  let result = 0;
  let current: NodeId | undefined = nodeId;
  const visited = new Set<NodeId>();
  while (current && visited.add(current)) {
    current = engine.indexes.parentNodeByNodeId.get(current);
    if (current) result += 1;
  }
  return result;
}
