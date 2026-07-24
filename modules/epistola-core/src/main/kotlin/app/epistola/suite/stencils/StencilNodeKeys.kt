// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

/**
 * String keys used by stencil + placeholder nodes throughout the document model.
 *
 * Centralised here so a typo in any single literal can't silently break the
 * mechanism. Mirrored in:
 *  - Editor TS: `modules/editor/src/main/typescript/components/stencil/constants.ts`
 *  - Editor TS: `modules/editor/src/main/typescript/components/placeholder/constants.ts`
 *  - Renderer:  `modules/generation/src/main/kotlin/app/epistola/generation/pdf/StencilNodeKeys.kt`
 *
 * The values are anchored by the JSON Schema in the `epistola-contract` repo;
 * any divergence would surface in integration runs against that schema.
 */
object StencilNodeKeys {
    const val NODE_TYPE = "stencil"
    const val SLOT_CHILDREN = "children"
    const val PROP_STENCIL_ID = "stencilId"
    const val PROP_CATALOG_KEY = "catalogKey"
    const val PROP_VERSION = "version"
    const val PROP_IS_DRAFT = "isDraft"

    /**
     * Snapshot of the stencil version's parameter schema, copied onto the
     * consuming stencil node's props at insert/upgrade time. Stencils are
     * dynamic components — each version has its own schema — so the schema
     * cannot live in a static component definition; the snapshot keeps it
     * accessible to the renderer / editor without a DB lookup.
     *
     * For the *generic* parameter-binding prop (used by every parametrised
     * component, not just stencils), see
     * [app.epistola.suite.templates.model.NodeParameterKeys.PROP_PARAMETER_BINDINGS].
     */
    const val PROP_PARAMETER_SCHEMA_SNAPSHOT = "parameterSchemaSnapshot"
}

/**
 * Companion to [StencilNodeKeys] — keys for placeholder nodes. Lives in the
 * stencils package because the placeholder is a stencil-domain concept (it
 * only exists inside stencils).
 */
object PlaceholderNodeKeys {
    const val NODE_TYPE = "placeholder"
    const val SLOT_DEFAULT = "default"
    const val SLOT_FILL = "fill"
    const val PROP_NAME = "name"
    const val PROP_DESCRIPTION = "description"
}
