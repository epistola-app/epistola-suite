/**
 * Dump the editor's component registry to JSON for backend consumers.
 *
 * The Kotlin MCP module reads the resulting file from the classpath and
 * exposes it as the `list_component_types` MCP tool. The TypeScript registry
 * remains the canonical source — running this script is wired into
 * `pnpm build`, so the JSON regenerates whenever the registry changes.
 *
 * Output schema (per component):
 *   {
 *     type, label, icon?, category, hidden,
 *     slots: [{ name, dynamic? }],
 *     allowedChildren: { mode: 'all' | 'none' | 'allowlist' | 'denylist', types? },
 *     applicableStyles: 'all' | string[],
 *     inspector: [{ key, label, type, options?, defaultValue?, units? }],
 *     defaultStyles?, defaultProps?, maxInstancesPerDocument?,
 *     examples?: [{ name, description, fragment: { rootNodeId, nodes, slots } }],
 *     parameters?: JsonSchema | null
 *   }
 *   - undefined: component has no parameter support
 *   - null: dynamic per-instance (e.g. stencil — use get_stencil_version to fetch)
 *   - JsonSchema literal: static parameter schema (same for every instance)
 *
 * Non-serializable hooks (renderCanvas, renderInspector, callbacks) are
 * intentionally dropped — backend consumers don't need them.
 */

import { mkdirSync, writeFileSync } from 'fs';
import { dirname, resolve } from 'path';
import { Window } from 'happy-dom';

// Component registration files transitively import Lit-based web components
// whose `@customElement` decorators run at module load and which expect a
// browser environment. We bootstrap a happy-dom window before any of that
// code is imported. We never render with these modules — only read the
// serializable shape of the definitions they register.
const window = new Window();
const target = globalThis as Record<string, unknown>;
const source = window as unknown as Record<string, unknown>;
// Copy every property happy-dom exposes onto globalThis (Document, Node,
// HTMLElement, customElements, etc.) so module-load-time code in Lit and the
// editor's web components finds the symbols it expects.
for (const key of Reflect.ownKeys(source)) {
  if (typeof key === 'symbol') continue;
  if (!(key in target)) {
    target[key] = source[key];
  }
}
target.window = window;

// Type-only import — erased before runtime so it doesn't trigger the Lit modules.
import type { ComponentDefinition } from '../src/main/typescript/engine/registry.ts';
import type { JsonSchema } from '../src/main/typescript/data-contract/types.js';

// Runtime import happens only AFTER the browser globals are installed above.
const { createDefaultRegistry } = await import('../src/main/typescript/engine/registry.ts');
const { createStencilDefinition } =
  await import('../src/main/typescript/components/stencil/stencil-registration.ts');

interface SerializedComponent {
  type: string;
  label: string;
  icon?: string;
  category: string;
  hidden: boolean;
  slots: Array<{ name: string; dynamic?: boolean }>;
  allowedChildren: ComponentDefinition['allowedChildren'];
  applicableStyles: 'all' | string[];
  inspector: ComponentDefinition['inspector'];
  defaultStyles?: Record<string, unknown>;
  defaultProps?: Record<string, unknown>;
  maxInstancesPerDocument?: number;
  examples?: ComponentDefinition['examples'];
  /** Parameter schema: null = dynamic per-instance; undefined = no parameter support. */
  parameters?: JsonSchema | null;
}

function serializeParameters(def: ComponentDefinition): SerializedComponent['parameters'] {
  if (def.parameters === undefined) return undefined;
  if (typeof def.parameters === 'function') return null; // dynamic per-instance
  return def.parameters; // static JsonSchema literal
}

function describe(def: ComponentDefinition): SerializedComponent {
  return {
    type: def.type,
    label: def.label,
    icon: def.icon,
    category: def.category,
    hidden: def.hidden ?? false,
    slots: def.slots,
    allowedChildren: def.allowedChildren,
    applicableStyles: def.applicableStyles,
    inspector: def.inspector,
    defaultStyles: def.defaultStyles,
    defaultProps: def.defaultProps,
    maxInstancesPerDocument: def.maxInstancesPerDocument,
    examples: def.examples,
    parameters: serializeParameters(def),
  };
}

const outputPath = resolve(process.argv[2] ?? 'dist/component-registry.json');

const registry = createDefaultRegistry();
// The stencil component is registered at runtime by `lib.ts` (it needs a
// callbacks bag from the host application). For the registry dump we
// register a null-callbacks variant so its descriptor — including the
// `with-placeholder` / `with-overridden-placeholder` examples — surfaces
// to backend consumers like the MCP server.
registry.register(createStencilDefinition({ callbacks: null }));
const components = registry.all().map(describe);

const payload = {
  schemaVersion: 1,
  components,
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, JSON.stringify(payload, null, 2) + '\n');

console.log(`Wrote ${components.length} component descriptors to ${outputPath}`);
