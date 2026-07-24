// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Typed view of a placeholder `Node` with branded narrowing + accessors.
 *
 * The base `Node` type from the document model has `props: Record<string, unknown>`
 * for forward-compat reasons. We can't widen `Node` itself (it has to accommodate
 * all component types), but we can offer per-component typed views via guards:
 *
 *   if (isPlaceholder(node)) {
 *     // node.props.name is now typed as string
 *   }
 *
 * Plus accessors for the common one-shot reads where a guard is overkill:
 *
 *   const name = placeholderName(node);  // string | undefined
 *
 * The guard's contract is: "if a node has type === PLACEHOLDER_TYPE, the rest
 * of its shape conforms to PlaceholderProps." This is a *trust* boundary; the
 * server-side `PlaceholderValidator` and the editor's own dispatch validation
 * are what keep the contract honest at runtime.
 */

import type { Node } from '../../types/index.js';
import { PLACEHOLDER_TYPE } from './constants.js';

/** Strongly-typed props for placeholder nodes. */
export interface PlaceholderProps {
  /** Kebab-case slug, unique within the stencil. */
  name: string;
  /** Optional human-readable description. */
  description?: string;
  /** Reserved; only `'block'` in v1. */
  kind?: 'block';
}

/** Branded view of a placeholder node. Obtain via {@link isPlaceholder}. */
export type PlaceholderNode = Node & {
  readonly type: typeof PLACEHOLDER_TYPE;
  readonly props: PlaceholderProps;
};

/** Type guard: narrows `Node` to `PlaceholderNode` when `node.type === 'placeholder'`. */
export function isPlaceholder(node: Node | undefined): node is PlaceholderNode {
  return node?.type === PLACEHOLDER_TYPE;
}

/** Read the placeholder's name. Returns undefined when the node is not a placeholder. */
export function placeholderName(node: Node | undefined): string | undefined {
  if (!isPlaceholder(node)) return undefined;
  return node.props.name;
}

/** Read the placeholder's description. Returns undefined when absent or not a placeholder. */
export function placeholderDescription(node: Node | undefined): string | undefined {
  if (!isPlaceholder(node)) return undefined;
  return node.props.description;
}
