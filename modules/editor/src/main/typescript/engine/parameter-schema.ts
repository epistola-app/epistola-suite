/**
 * Generic parameter-schema lookup helper.
 *
 * Components declare their parameter schema via `ComponentDefinition.parameters`.
 * Two shapes are supported:
 *   - A literal `JsonSchema` (static-parametrised components).
 *   - A function `(node, document) => JsonSchema | null` (dynamic components
 *     like stencils, whose schema depends on per-instance snapshot props).
 *
 * `getNodeParameterSchema` normalises both forms so callers (the scope provider,
 * picker dialog, inspector, etc.) never have to branch on which kind of provider
 * a component supplied.
 */
import type { JsonSchema } from '../data-contract/types.js';
import type { ComponentDefinition } from './registry.js';
import type { Node, TemplateDocument } from '../types/index.js';

export function getNodeParameterSchema(
  node: Node,
  definition: ComponentDefinition | undefined,
  document: TemplateDocument,
): JsonSchema | null {
  const provider = definition?.parameters;
  if (!provider) return null;
  if (typeof provider === 'function') return provider(node, document);
  return provider;
}
