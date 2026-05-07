/**
 * Typed view of a stencil `Node` with branded narrowing + accessors.
 *
 * Companion to `node-types.ts` in the placeholder folder. Pattern:
 *
 *   if (isStencil(node)) {
 *     // node.props.stencilId is typed as string | null
 *     // node.props.isDraft is typed as boolean
 *   }
 *
 *   const sid = stencilId(node);     // string | null | undefined
 *   const isPub = isPublishedStencil(node);  // narrows to a stencil whose
 *                                            // stencilId is non-null and
 *                                            // isDraft is false
 *
 * The runtime layout is enforced by the server-side `PlaceholderValidator`
 * and by the JSON Schema; these types are a TypeScript contract over that.
 */

import type { Node } from '../../types/index.js';
import { STENCIL_TYPE } from './constants.js';

/**
 * Strongly-typed props for stencil nodes.
 *
 * `stencilId` and `catalogKey` are nullable because a stencil node can be
 * "unlinked" — present in the document but not yet published. `version` is
 * the published-version number, also null when unlinked.
 */
export interface StencilProps {
  stencilId: string | null;
  catalogKey: string | null;
  version: number | null;
  isDraft: boolean;
  /**
   * Reserved for the future stencil-parameters feature. The validator
   * rejects content with this field set in v1.
   */
  parameterBindings?: never;
}

/** Branded view of a stencil node. Obtain via {@link isStencil}. */
export type StencilNode = Node & {
  readonly type: typeof STENCIL_TYPE;
  readonly props: StencilProps;
};

/** Branded view of a *published* stencil — `stencilId` non-null AND `isDraft === false`. */
export type PublishedStencilNode = StencilNode & {
  readonly props: StencilProps & { stencilId: string; isDraft: false };
};

/** Type guard: narrows `Node` to `StencilNode` when `node.type === 'stencil'`. */
export function isStencil(node: Node | undefined): node is StencilNode {
  return node?.type === STENCIL_TYPE;
}

/**
 * True for a stencil that is linked to a published definition AND not in
 * draft mode — i.e. its content is frozen.
 */
export function isPublishedStencil(node: Node | undefined): node is PublishedStencilNode {
  if (!isStencil(node)) return false;
  return node.props.stencilId != null && !node.props.isDraft;
}

/** Read the stencil's ID. Returns undefined when not a stencil. */
export function stencilId(node: Node | undefined): string | null | undefined {
  if (!isStencil(node)) return undefined;
  return node.props.stencilId;
}

/** Read the stencil's catalog key. Returns undefined when not a stencil. */
export function stencilCatalogKey(node: Node | undefined): string | null | undefined {
  if (!isStencil(node)) return undefined;
  return node.props.catalogKey;
}

/** Read the stencil's version. Returns undefined when not a stencil. */
export function stencilVersion(node: Node | undefined): number | null | undefined {
  if (!isStencil(node)) return undefined;
  return node.props.version;
}

/** Read the stencil's draft flag. Returns undefined when not a stencil. */
export function stencilIsDraft(node: Node | undefined): boolean | undefined {
  if (!isStencil(node)) return undefined;
  return node.props.isDraft;
}
