// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Component-agnostic prop keys carried by any node type that supports parameters.
 *
 * Today the only parametrised component is the stencil; tomorrow other component
 * types (static-parametrised "snippets", fragments, ...) plug into the same
 * render-time scope plumbing by reusing these keys.
 *
 * Mirrored in:
 *   - Kotlin: `modules/epistola-core/.../templates/model/NodeParameterKeys.kt`
 *
 * Stencil-specific data conventions (the per-instance schema snapshot, because
 * a stencil's schema is itself dynamic) stay in `components/stencil/constants.ts`.
 */

/** Prop key — `Record<paramName, JSONata expression>`. */
export const PROP_PARAMETER_BINDINGS = 'parameterBindings';

/**
 * Prop key — namespace alias under which the node's parameters are exposed
 * inside its content. Defaults to `'params'`. Configurable per-instance so
 * nested parametrised nodes don't shadow each other's scopes.
 */
export const PROP_PARAMS_ALIAS = 'paramsAlias';

/** Default value for `PROP_PARAMS_ALIAS` when the prop is not set on a node. */
export const DEFAULT_PARAMS_ALIAS = 'params';

/** Aliases that collide with engine-provided scopes (system parameters / loops). */
export const RESERVED_ALIASES: ReadonlySet<string> = new Set(['sys', 'item', 'index']);
