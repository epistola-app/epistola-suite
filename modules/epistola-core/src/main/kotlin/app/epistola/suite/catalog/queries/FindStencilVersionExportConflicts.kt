package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException.StencilVersionConflict
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException.TemplatePin
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Returns one entry per own-catalog stencil whose used version-pins are out of
 * sync with the latest published version of that stencil. Two failure modes
 * are detected together:
 *
 *  - **Inconsistent**: different templates pin different versions of the same
 *    stencil (multi-version usage).
 *  - **Stale**: templates pin a single version that is not the latest published
 *    version of the stencil. The export only ships the latest published
 *    version, so any stale pin would be unresolvable in target.
 *
 * Scope: only own-catalog stencil refs (`props.catalogKey` null or equal to
 * this catalog). Cross-catalog refs are dependencies and are tracked
 * separately.
 *
 * Empty list means the catalog is exportable. Used both as a precheck by the
 * UI and inside `ExportCatalogZip` (fail-fast before building the ZIP).
 */
data class FindStencilVersionExportConflicts(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<StencilVersionConflict>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class FindStencilVersionExportConflictsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindStencilVersionExportConflicts, List<StencilVersionConflict>> {

    /** One offending (stencil, template-variant) row before grouping by stencil. */
    private data class ConflictRow(
        val stencilKey: StencilKey,
        val stencilName: String,
        val latestPublishedVersion: Int,
        val templateName: String,
        val variantKey: String,
        val variantTitle: String?,
        val pinnedVersion: Int,
    )

    override fun handle(query: FindStencilVersionExportConflicts): List<StencilVersionConflict> = jdbi.withHandle<List<StencilVersionConflict>, Exception> { handle ->
        val rows = handle.createQuery(
            """
                WITH latest_stencil_version AS (
                    SELECT s.id, MAX(sv.id) AS latest_published_version
                    FROM stencils s
                    JOIN stencil_versions sv
                      ON sv.tenant_key = s.tenant_key
                     AND sv.catalog_key = s.catalog_key
                     AND sv.stencil_key = s.id
                     AND sv.status = 'published'
                    WHERE s.tenant_key = :tenantKey
                      AND s.catalog_key = :catalogKey
                    GROUP BY s.id
                ),
                latest_published_templates AS (
                    SELECT DISTINCT ON (tenant_key, catalog_key, template_key, variant_key)
                        template_key, variant_key, template_model
                    FROM template_versions
                    WHERE tenant_key = :tenantKey
                      AND catalog_key = :catalogKey
                      AND status = 'published'
                    ORDER BY tenant_key, catalog_key, template_key, variant_key, id DESC
                ),
                stencil_refs AS (
                    SELECT
                        lpv.template_key,
                        lpv.variant_key,
                        node.value -> 'props' ->> 'stencilId' AS stencil_id,
                        COALESCE((node.value -> 'props' ->> 'version')::int, 0) AS stencil_version,
                        node.value -> 'props' ->> 'catalogKey' AS ref_catalog_key
                    FROM latest_published_templates lpv
                    CROSS JOIN LATERAL jsonb_each(lpv.template_model -> 'nodes') AS node(key, value)
                    WHERE node.value ->> 'type' = 'stencil'
                )
                SELECT DISTINCT
                    sr.stencil_id, s.name AS stencil_name, lsv.latest_published_version,
                    sr.template_key, dt.name AS template_name,
                    sr.variant_key, tvar.title AS variant_title,
                    sr.stencil_version AS pinned_version
                FROM stencil_refs sr
                JOIN stencils s ON s.tenant_key = :tenantKey
                               AND s.catalog_key = :catalogKey
                               AND s.id = sr.stencil_id
                JOIN latest_stencil_version lsv ON lsv.id = s.id
                JOIN document_templates dt ON dt.tenant_key = :tenantKey
                                          AND dt.catalog_key = :catalogKey
                                          AND dt.id = sr.template_key
                LEFT JOIN template_variants tvar ON tvar.tenant_key = :tenantKey
                                                AND tvar.catalog_key = :catalogKey
                                                AND tvar.template_key = sr.template_key
                                                AND tvar.id = sr.variant_key
                WHERE sr.stencil_id IS NOT NULL
                  AND (sr.ref_catalog_key IS NULL OR sr.ref_catalog_key = :catalogKey)
                  AND sr.stencil_version <> lsv.latest_published_version
                ORDER BY stencil_name, template_name, sr.variant_key, pinned_version
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                ConflictRow(
                    stencilKey = StencilKey(rs.getString("stencil_id")),
                    stencilName = rs.getString("stencil_name"),
                    latestPublishedVersion = rs.getInt("latest_published_version"),
                    templateName = rs.getString("template_name"),
                    variantKey = rs.getString("variant_key"),
                    variantTitle = rs.getString("variant_title"),
                    pinnedVersion = rs.getInt("pinned_version"),
                )
            }
            .list()

        // Group the flat (stencil, template-variant) rows into one conflict per
        // stencil. groupBy preserves first-seen order, and the SQL already orders
        // by stencil then template then variant.
        rows.groupBy { it.stencilKey }
            .map { (stencilKey, group) ->
                StencilVersionConflict(
                    stencilKey = stencilKey,
                    stencilName = group.first().stencilName,
                    latestPublishedVersion = group.first().latestPublishedVersion,
                    pins = group.map {
                        TemplatePin(
                            templateName = it.templateName,
                            variantKey = it.variantKey,
                            variantTitle = it.variantTitle,
                            pinnedVersion = it.pinnedVersion,
                        )
                    },
                )
            }
    }
}
