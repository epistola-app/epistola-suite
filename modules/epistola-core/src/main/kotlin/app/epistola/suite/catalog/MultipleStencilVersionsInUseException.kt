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
    "Cannot export catalog '${catalogKey.value}': stencil version-pins out of sync with latest published — ${
        stencils.joinToString(", ") {
            "'${it.stencilKey.value}' templates pin v${it.versions.joinToString(", v")}, latest published is v${it.latestPublishedVersion}"
        }
    }",
) {
    data class StencilVersionConflict(
        val stencilKey: StencilKey,
        val stencilName: String,
        val versions: List<Int>,
        val latestPublishedVersion: Int,
    )
}
