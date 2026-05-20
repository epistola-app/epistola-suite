package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.MultipleStencilVersionsInUseException.StencilVersionConflict
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
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class FindStencilVersionExportConflictsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindStencilVersionExportConflicts, List<StencilVersionConflict>> {

    override fun handle(query: FindStencilVersionExportConflicts): List<StencilVersionConflict> = jdbi.withHandle<List<StencilVersionConflict>, Exception> { handle ->
        handle.createQuery(
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
                        template_model
                    FROM template_versions
                    WHERE tenant_key = :tenantKey
                      AND catalog_key = :catalogKey
                      AND status = 'published'
                    ORDER BY tenant_key, catalog_key, template_key, variant_key, id DESC
                ),
                stencil_refs AS (
                    SELECT
                        node.value -> 'props' ->> 'stencilId' AS stencil_id,
                        COALESCE((node.value -> 'props' ->> 'version')::int, 0) AS stencil_version,
                        node.value -> 'props' ->> 'catalogKey' AS ref_catalog_key
                    FROM latest_published_templates lpv
                    CROSS JOIN LATERAL jsonb_each(lpv.template_model -> 'nodes') AS node(key, value)
                    WHERE node.value ->> 'type' = 'stencil'
                )
                SELECT sr.stencil_id, s.name, lsv.latest_published_version,
                       ARRAY_AGG(DISTINCT sr.stencil_version ORDER BY sr.stencil_version) AS versions
                FROM stencil_refs sr
                JOIN stencils s ON s.tenant_key = :tenantKey
                               AND s.catalog_key = :catalogKey
                               AND s.id = sr.stencil_id
                JOIN latest_stencil_version lsv ON lsv.id = s.id
                WHERE sr.stencil_id IS NOT NULL
                  AND (sr.ref_catalog_key IS NULL OR sr.ref_catalog_key = :catalogKey)
                GROUP BY sr.stencil_id, s.name, lsv.latest_published_version
                HAVING bool_or(sr.stencil_version <> lsv.latest_published_version)
                ORDER BY sr.stencil_id
                """,
        )
            .bind("tenantKey", query.tenantKey)
            .bind("catalogKey", query.catalogKey)
            .map { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val versionsArr = rs.getArray("versions")?.array as Array<Any?>?
                StencilVersionConflict(
                    stencilKey = StencilKey(rs.getString("stencil_id")),
                    stencilName = rs.getString("name"),
                    versions = versionsArr?.mapNotNull { (it as? Number)?.toInt() }?.sorted().orEmpty(),
                    latestPublishedVersion = rs.getInt("latest_published_version"),
                )
            }
            .list()
    }
}
