package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.StencilKey

/**
 * Strategy applied when an imported stencil version collides with a different
 * already-installed version of the same (catalog, slug, version).
 *
 * `FAIL` (default) — abort the whole import and surface the conflict to the
 * operator. The catalog is left exactly as it was.
 *
 * `RENUMBER` — install the imported version at the next available version
 * number in the target instead of the requested one. The orchestrator records
 * the assigned number so [ImportTemplates] can rewrite stencil-node
 * `props.version` on templates from the same ZIP that pinned the original
 * (requested) version. Pre-existing templates in the target keep their pins
 * intact and continue to resolve their own version.
 *
 * Only meaningful for `AUTHORED MERGE` imports. SUBSCRIBED and AUTHORED
 * REPLACE are mirror semantics — source wins, so renumber would contradict
 * the import contract and is rejected.
 */
enum class OnStencilConflict {
    FAIL,
    RENUMBER,
}

/**
 * Record of a single renumber decision applied during a `RENUMBER`-mode ZIP
 * import. The orchestrator (`ImportCatalogZip`) collects one entry per
 * stencil whose source version collided with a different target version, and
 * hands the map to `ImportTemplates` so stencil-node `props.version` pins on
 * the templates from the *same ZIP* are rewritten from [sourceVersion] to
 * [assignedVersion]. Pre-existing templates in the target are not touched —
 * they keep resolving the target's pre-renumber version.
 */
data class StencilRenumber(
    val sourceVersion: Int,
    val assignedVersion: Int,
)

/**
 * Thrown by [ImportStencil] when the (catalog, slug, version) the source ZIP
 * wants to install already exists in the target with **different content**
 * (semantic JSONB compare) and the import is configured for `FAIL` resolution.
 *
 * Collected by [ImportCatalogZip] across the whole ZIP so the operator sees
 * every conflict at once (not one-by-one), then surfaced as
 * [StencilVersionImportConflictsException].
 */
class StencilVersionConflictException(
    val catalogKey: CatalogKey,
    val stencilKey: StencilKey,
    val version: Int,
) : RuntimeException(
    "Stencil '${stencilKey.value}' v$version already exists in catalog '${catalogKey.value}' with different content",
)

/**
 * Aggregate exception thrown by [ImportCatalogZip] after collecting every
 * single-stencil conflict from a multi-stencil import in one pass. Lets the UI
 * dialog show the full list of conflicts at once instead of failing on the
 * first one.
 */
class StencilVersionImportConflictsException(
    val catalogKey: CatalogKey,
    val conflicts: List<StencilImportConflict>,
) : RuntimeException(
    "Cannot import catalog '${catalogKey.value}': ${conflicts.size} stencil version-conflict(s) — ${
        conflicts.joinToString(", ") { "'${it.stencilKey.value}' v${it.version}" }
    }",
) {
    data class StencilImportConflict(
        val stencilKey: StencilKey,
        val stencilName: String,
        val version: Int,
    )
}
