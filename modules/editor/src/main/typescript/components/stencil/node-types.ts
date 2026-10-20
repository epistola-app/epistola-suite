// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Typed view of a stencil `Node` with branded narrowing + accessors.
 *
 * Companion to `node-types.ts` in the placeholder folder. Pattern:
 *
 *   if (isStencil(node)) {
 *     // node.props.stencilKey is typed as string | null
 *   }
 *
 *   const ref = stencilRefOf(node, 'my-catalog'); // { stencilKey, catalogKey } | null
 *
 * The runtime layout is enforced by the server-side validator and by the
 * contract's JSON Schema; these types are a TypeScript contract over that.
 */

import type { JsonSchema } from '../../data-contract/types.js';
import type { Node } from '../../types/index.js';
import { STENCIL_TYPE } from './constants.js';
import type { StencilRef } from './types.js';

/**
 * Strongly-typed props for stencil nodes (wire v8: an instance names its
 * stencil, it pins no version).
 *
 * `stencilKey` is nullable because a stencil node can be unlinked: present
 * in the document without a stencil behind it (inserted without stencil
 * callbacks).
 */
export interface StencilProps {
  stencilKey: string | null;
  /** The stencil's catalog; absent or null means the referencing document's own catalog. */
  catalogKey?: string | null;
  /**
   * Map of `paramName → JSONata expression`. Each entry binds one parameter
   * declared in `parameterSchemaSnapshot` to an expression evaluated at
   * render time against the *outer* render context. Absent / empty when the
   * stencil has no parameters or none have been bound yet.
   */
  parameterBindings?: Record<string, string>;
  /**
   * The stencil's parameter schema, set while the instance is expanded (by
   * the server when it loads the template, by the editor when it inserts or
   * refreshes the instance). Never stored with the template.
   */
  parameterSchemaSnapshot?: JsonSchema;
  /** Set while expanded when the stencil reads the using template's data. Never stored. */
  readsTemplateData?: boolean;
  /**
   * Optional namespace alias under which this stencil's parameters are
   * exposed inside its content. Defaults to `'params'`.
   */
  paramsAlias?: string;
}

/** Branded view of a stencil node. Obtain via {@link isStencil}. */
export type StencilNode = Node & {
  readonly type: typeof STENCIL_TYPE;
  readonly props: StencilProps;
};

/** Type guard: narrows `Node` to `StencilNode` when `node.type === 'stencil'`. */
export function isStencil(node: Node | undefined): node is StencilNode {
  return node?.type === STENCIL_TYPE;
}

/** True for a stencil node that names a stencil. */
export function isLinkedStencil(node: Node | undefined): node is StencilNode {
  return isStencil(node) && typeof node.props?.stencilKey === 'string' && !!node.props.stencilKey;
}

/** Read the stencil's key. Returns undefined when not a stencil. */
export function stencilKey(node: Node | undefined): string | null | undefined {
  if (!isStencil(node)) return undefined;
  return node.props?.stencilKey ?? null;
}

/**
 * The stencil an instance names, with an absent catalog resolved to
 * [contextCatalogKey] (the catalog of the document it is written in).
 * Null for an unlinked stencil, or a non-stencil node.
 */
export function stencilRefOf(
  node: Node | undefined,
  contextCatalogKey?: string,
): StencilRef | null {
  if (!isLinkedStencil(node)) return null;
  const catalogKey = node.props.catalogKey || contextCatalogKey || '';
  return { stencilKey: node.props.stencilKey!, catalogKey };
}

/** One string per stencil, for sets and maps: `{catalogKey}/{stencilKey}`. */
export function stencilIdentity(ref: StencilRef): string {
  return `${ref.catalogKey}/${ref.stencilKey}`;
}
