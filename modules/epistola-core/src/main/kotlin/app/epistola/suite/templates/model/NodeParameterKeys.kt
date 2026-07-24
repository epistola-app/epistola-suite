// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.model

/**
 * Component-agnostic prop keys carried by any node type that supports parameters.
 *
 * Today: the only parametrised component is [stencil]. The keys are deliberately
 * not in `StencilNodeKeys` so that a future parametrised component (e.g. a
 * static "snippet") plugs into the same render-time scope plumbing without a
 * refactor.
 *
 * Mirrored in:
 *   - Editor TS: `modules/editor/src/main/typescript/engine/node-parameter-keys.ts`
 *
 * Stencil-specific data conventions (the per-instance schema snapshot,
 * because a stencil's schema is itself dynamic) stay in `StencilNodeKeys`.
 */
object NodeParameterKeys {
    /**
     * Map of `paramName → JSONata expression`. Each entry binds one parameter
     * declared on the node's schema to an expression evaluated at render time
     * against the *outer* render context.
     */
    const val PROP_PARAMETER_BINDINGS = "parameterBindings"

    /**
     * Optional namespace alias under which the node's parameters are exposed
     * inside its content. Defaults to [DEFAULT_PARAMS_ALIAS]. Configurable
     * per-instance so two nested parametrised nodes can coexist without
     * shadowing each other's `params.*` scopes.
     *
     * Mirrors how `for-each.itemAlias` works.
     */
    const val PROP_PARAMS_ALIAS = "paramsAlias"

    /** Default alias used when [PROP_PARAMS_ALIAS] is not set on a node. */
    const val DEFAULT_PARAMS_ALIAS = "params"

    /**
     * Aliases that may not be used because they collide with engine-provided
     * scopes (system parameters, conventional loop iteration variables).
     */
    val RESERVED_ALIASES: Set<String> = setOf("sys", "item", "index")
}
