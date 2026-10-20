// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.templateJoin
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.mapper.Nested
import org.springframework.stereotype.Component

/**
 * One [Catalog] plus the catalog-management-list-only `pendingChanges` flag.
 *
 * `pendingChanges` is **not** a property of a catalog — it is "this catalog
 * *as shown in the management list right now*": an AUTHORED-and-released
 * catalog whose working copy has drifted since the last release/import. It is
 * deliberately *not* on [Catalog] (which browse, REST and MCP also read and
 * which must stay a plain `SELECT *` via `listForTenant`); it lives only on
 * this list-page row, computed in the same query.
 */
data class CatalogListRow(
    @Nested val catalog: Catalog,
    val pendingChanges: Boolean,
)

/**
 * Whether an AUTHORED catalog has been touched since it was last released, in three shareable
 * pieces: the activity roll-up, the flag computed from it, and the join between them.
 *
 * Kept apart so the rule has one home: two copies of a drift rule is how one page comes to say
 * "unreleased changes" while another says there are none.
 *
 * Conservative by design — see the note on [ListCatalogsForManagement]: it over-warns on an
 * edit-then-revert and under-warns only on a deletion-only change, and the export's `-dev` label
 * and fingerprint remain the authoritative answer.
 */
internal val CATALOG_ACTIVITY_CTE = """
            WITH activity(catalog_id, ts) AS (
                SELECT catalog_id, content_updated_at FROM catalogs                      WHERE tenant_key = :t
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM document_templates            WHERE tenant_key = :t AND ${inCurrentVersion("document_templates")} GROUP BY catalog_id
                UNION ALL SELECT template.catalog_id, MAX(variants.updated_at)
                                 FROM template_variants variants ${templateJoin("variants")}
                                 WHERE variants.tenant_key = :t AND ${inCurrentVersion("variants")} GROUP BY template.catalog_id
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM themes               WHERE tenant_key = :t AND ${inCurrentVersion("themes")} GROUP BY catalog_id
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM stencils             WHERE tenant_key = :t AND ${inCurrentVersion("stencils")} GROUP BY catalog_id
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM variant_attribute_definitions WHERE tenant_key = :t AND ${inCurrentVersion("variant_attribute_definitions")} GROUP BY catalog_id
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM code_lists           WHERE tenant_key = :t AND ${inCurrentVersion("code_lists")} GROUP BY catalog_id
                UNION ALL SELECT catalog_id, MAX(updated_at)   FROM fonts                WHERE tenant_key = :t AND ${inCurrentVersion("fonts")} GROUP BY catalog_id
                UNION ALL SELECT catalog_id, MAX(created_at)   FROM assets               WHERE tenant_key = :t AND ${inCurrentVersion("assets")} GROUP BY catalog_id
            )
"""

internal const val CATALOG_PENDING_CHANGES = """COALESCE(
                       c.type = 'AUTHORED'
                       AND c.released_at IS NOT NULL
                       AND a.last_activity > GREATEST(c.released_at, c.imported_at),
                       FALSE
                   ) AS pending_changes"""

internal const val CATALOG_ACTIVITY_JOIN = """LEFT JOIN (SELECT catalog_id, MAX(ts) AS last_activity FROM activity GROUP BY catalog_id) a
              ON a.catalog_id = c.catalog_id"""

/**
 * The catalog-management list. [ListCatalogs] (the shared read model, used by
 * browse / REST / MCP) plus the cheap AUTHORED working-copy drift signal —
 * computed here in **one** `LEFT JOIN` against a timestamp aggregate, so the
 * UI never cross-references a parallel id set in the template.
 *
 * `pendingChanges` ⇔ `type = AUTHORED` AND `released_at IS NOT NULL` AND the
 * most recent content-activity timestamp across the catalog's resources is
 * **after** `GREATEST(released_at, imported_at)`. **Not** a content
 * fingerprint (that is O(catalog-size); `ExportCatalogZip` remains the
 * authoritative `-dev` truth).
 *
 * The baseline includes `imported_at` so a no-op ZIP **re-import** (which
 * bumps resources' timestamps) advances the baseline in lockstep and is
 * **not** mistaken for drift — only an edit *after* the last release/import
 * trips it.
 *
 * Column choice per table mirrors what the export fingerprint actually sees:
 *  - `updated_at` for the live resource rows
 *    (`document_templates`, `template_variants`, `themes`, `stencils`,
 *    `variant_attribute_definitions`, `code_lists`, `fonts`), which hold the
 *    working copy a release and an export are built from;
 *  - `created_at` for `assets` (effectively immutable; a new asset is a
 *    change).
 *
 * Conservative by design: it can over-warn (edit-then-revert to
 * byte-identical; a re-publish of unchanged content) but the only under-warn
 * is a deletion-only change (no surviving row's timestamp moves) — for all of
 * these the export's `-dev` label/fingerprint is the authoritative backstop.
 */
data class ListCatalogsForManagement(
    override val tenantKey: TenantKey,
) : Query<List<CatalogListRow>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListCatalogsForManagementHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListCatalogsForManagement, List<CatalogListRow>> {

    override fun handle(query: ListCatalogsForManagement): List<CatalogListRow> = jdbi.withHandle<List<CatalogListRow>, Exception> { handle ->
        handle.createQuery(
            """
            $CATALOG_ACTIVITY_CTE
            SELECT c.*, $CATALOG_PENDING_CHANGES
            FROM catalogs c
            $CATALOG_ACTIVITY_JOIN
            WHERE c.tenant_key = :t
            ORDER BY c.name
            """,
        )
            .bind("t", query.tenantKey)
            .mapTo<CatalogListRow>()
            .list()
    }
}
