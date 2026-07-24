// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.common.ids.StencilKey

/**
 * Thrown by `ExportCatalogZip` when published templates in the catalog being
 * exported reference an own-catalog stencil at any version other than that
 * stencil's *latest* published version. Two failure modes are folded together:
 *
 *  - **Inconsistent**: different templates pin different versions of the same
 *    stencil. The export wire format carries one version per stencil, so a
 *    downstream import would resolve only one of the pinned versions.
 *  - **Stale**: all templates pin the same version, but that version is not
 *    the latest published. The exporter ships the latest published version
 *    (and only that one), so the import would not have the pinned version
 *    available even though the source state looks consistent.
 *
 * In both cases the operator must republish the templates against the latest
 * stencil version before the catalog is exportable.
 */
class MultipleStencilVersionsInUseException(
    val catalogKey: CatalogKey,
    val stencils: List<StencilVersionConflict>,
) : RuntimeException(
    buildString {
        append("Cannot export catalog '${catalogKey.value}': ")
        append(
            stencils.joinToString("; ") { s ->
                "stencil '${s.stencilKey.value}' (latest published v${s.latestPublishedVersion}) is still pinned at " +
                    "an older version by ${s.pins.joinToString(", ") { p -> "'${p.displayLabel}' v${p.pinnedVersion}" }}"
            },
        )
        append(". Upgrade and publish those template variants, then export.")
    },
) {
    data class StencilVersionConflict(
        val stencilKey: StencilKey,
        val stencilName: String,
        val latestPublishedVersion: Int,
        /** The published template-variants that pin an out-of-date version of this stencil. */
        val pins: List<TemplatePin>,
    )

    /**
     * One published template-variant whose latest published version pins a
     * version of the stencil other than the latest published one.
     */
    data class TemplatePin(
        val templateName: String,
        val variantKey: String,
        val variantTitle: String?,
        val pinnedVersion: Int,
    ) {
        /** Human label: the template name, plus the variant title when it has one. */
        val displayLabel: String get() = variantTitle?.let { "$templateName · $it" } ?: templateName
    }
}
