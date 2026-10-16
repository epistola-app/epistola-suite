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
     * The stencil declares it reads the template's data (ADR 0006 part 5). Without it, the stencil's
     * content sees only its own parameters and `sys`. Carried on the instance while expanded.
     */
    const val PROP_READS_TEMPLATE_DATA = "readsTemplateData"
}

object PlaceholderNodeKeys {
    const val NODE_TYPE = "placeholder"
    const val SLOT_DEFAULT = "default"
    const val SLOT_FILL = "fill"

    /** The stencil author lets this placeholder pass the stencil's parameters to its fill (ADR 0006). */
    const val PROP_PASS_PARAMETERS = "passParameters"

    /** The stencil author's default name for the passed parameters. */
    const val PROP_PARAMETERS_AS = "parametersAs"

    /** The name the using document chose for the passed parameters; carried while expanded. */
    const val PROP_FILL_PARAMETERS_AS = "fillParametersAs"

    const val DEFAULT_PARAMETERS_NAME = "params"
}
