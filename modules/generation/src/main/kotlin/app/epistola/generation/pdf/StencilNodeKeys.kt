// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * String keys used by stencil + placeholder nodes throughout the document model.
 *
 * Mirrored from `modules/epistola-core/.../stencils/StencilNodeKeys.kt` because
 * the `generation` Gradle module does not depend on `epistola-core`. The
 * editor TS holds the same values in
 * `modules/editor/src/main/typescript/components/{stencil,placeholder}/constants.ts`.
 *
 * The values are anchored by the JSON Schema in the `epistola-contract` repo;
 * any divergence would surface in integration runs against that schema.
 */
object StencilNodeKeys {
    const val NODE_TYPE = "stencil"
    const val PROP_STENCIL_ID = "stencilId"

    /**
     * Set by the 2.0 upgrade on an instance whose fills use the stencil's parameters in a way the
     * upgrade could not rewrite: its fills keep seeing the stencil's scope, as they did before 2.0.
     */
    const val PROP_LEGACY_FILL_SCOPE = "legacyFillScope"
}

object PlaceholderNodeKeys {
    const val NODE_TYPE = "placeholder"
    const val SLOT_DEFAULT = "default"
    const val SLOT_FILL = "fill"
}
