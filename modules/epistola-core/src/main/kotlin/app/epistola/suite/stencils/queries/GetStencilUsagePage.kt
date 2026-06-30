package app.epistola.suite.stencils.queries

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.model.StencilUsageDetail
import app.epistola.suite.stencils.model.StencilUsagePage
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

const val USAGE_FILTER_BOTH = "both"
const val USAGE_FILTER_UPGRADABLE = "upgradable"
const val USAGE_FILTER_NOT_UPGRADABLE = "not-upgradable"

private const val USAGE_PAGE_SIZE = 100

/**
 * A filtered, paginated page of the templates using a stencil, for the bulk-upgrade
 * table. The per-variant `upgradable` flag and block reason, the filter, the
 * pagination and the counts are all computed in SQL — the full usage set is never
 * loaded into memory.
 *
 * Upgrade always lands in a variant's draft (created from its latest published
 * version when none exists), so exactly one row per variant is upgradable: its
 * draft if it has one, otherwise its latest published version. Subscribed catalogs
 * are never upgradable.
 */
data class GetStencilUsagePage(
    val stencilId: StencilId,
    val filter: String = USAGE_FILTER_BOTH,
    val page: Int = 1,
) : Query<StencilUsagePage>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey
}

/**
 * Shared CTEs: `latest_stencil` (the stencil's latest published version),
 * `usage` (one row per template-version/stencil-version with an instance count),
 * `flagged` (adds the per-variant candidate flag), and `reasoned` (adds the
 * `upgradable` flag and the block reason for non-upgradable rows).
 *
 * A candidate row is only `upgradable` when it pins a version other than the
 * stencil's latest published version — a row already on the latest version has
 * nothing to upgrade to and is reported as `UP_TO_DATE`, not upgradable.
 */
private val USAGE_CTES = """
    WITH latest_stencil AS (
        SELECT COALESCE(MAX(id), 0) AS latest_published_version
        FROM stencil_versions
        WHERE tenant_key = :tenantId AND catalog_key = :catalogKey
          AND stencil_key = :stencilId AND status = 'published'
    ),
    usage AS (
        SELECT tv.template_key, tv.catalog_key, c.type AS catalog_type, dt.name AS template_name,
               tv.variant_key, tv.id AS version_id, tv.status AS version_status,
               COALESCE((node.value -> 'props' ->> 'version')::int, 0) AS stencil_version,
               COUNT(*) AS instance_count
        FROM template_versions tv
        JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
        JOIN catalogs c ON c.tenant_key = tv.tenant_key AND c.id = tv.catalog_key
        CROSS JOIN LATERAL jsonb_each(tv.template_model -> 'nodes') AS node(key, value)
        WHERE tv.tenant_key = :tenantId
          AND node.value ->> 'type' = 'stencil'
          AND node.value -> 'props' ->> 'stencilId' = :stencilId
        GROUP BY tv.template_key, tv.catalog_key, c.type, dt.name, tv.variant_key, tv.id, tv.status,
                 COALESCE((node.value -> 'props' ->> 'version')::int, 0)
    ),
    flagged AS (
        SELECT u.*,
               (u.catalog_type = 'AUTHORED' AND u.version_status IN ('draft', 'published')) AS is_candidate,
               bool_or(u.catalog_type = 'AUTHORED' AND u.version_status = 'draft')
                   OVER (PARTITION BY u.catalog_key, u.template_key, u.variant_key) AS variant_has_draft,
               ROW_NUMBER() OVER (
                   PARTITION BY u.catalog_key, u.template_key, u.variant_key
                   ORDER BY (u.catalog_type = 'AUTHORED' AND u.version_status IN ('draft', 'published')) DESC,
                            (u.version_status = 'draft') DESC,
                            u.version_id DESC
               ) AS rn
        FROM usage u
    ),
    reasoned AS (
        SELECT f.*,
               (f.is_candidate AND f.rn = 1 AND f.stencil_version <> ls.latest_published_version) AS upgradable,
               CASE
                   WHEN f.is_candidate AND f.rn = 1 AND f.stencil_version <> ls.latest_published_version THEN NULL
                   WHEN f.catalog_type <> 'AUTHORED' THEN 'SUBSCRIBED'
                   WHEN f.is_candidate AND f.rn = 1 THEN 'UP_TO_DATE'
                   WHEN f.variant_has_draft THEN 'HAS_DRAFT'
                   ELSE 'SUPERSEDED'
               END AS block_reason
        FROM flagged f
        CROSS JOIN latest_stencil ls
    )
"""

@Component
class GetStencilUsagePageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetStencilUsagePage, StencilUsagePage> {
    override fun handle(query: GetStencilUsagePage): StencilUsagePage = jdbi.withHandle<StencilUsagePage, Exception> { handle ->
        val filter = when (query.filter) {
            USAGE_FILTER_UPGRADABLE, USAGE_FILTER_NOT_UPGRADABLE -> query.filter
            else -> USAGE_FILTER_BOTH
        }

        // Counts over the full (unfiltered) set — one row, always present.
        val counts = handle.createQuery(
            "$USAGE_CTES SELECT COUNT(*) AS total_all, COUNT(*) FILTER (WHERE upgradable) AS upgradable_count FROM reasoned",
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("catalogKey", query.stencilId.catalogKey)
            .bind("stencilId", query.stencilId.key.value)
            .map { rs, _ -> rs.getInt("total_all") to rs.getInt("upgradable_count") }
            .one()
        val totalAll = counts.first
        val upgradableCount = counts.second

        val total = when (filter) {
            USAGE_FILTER_UPGRADABLE -> upgradableCount
            USAGE_FILTER_NOT_UPGRADABLE -> totalAll - upgradableCount
            else -> totalAll
        }
        val totalPages = if (total == 0) 1 else (total + USAGE_PAGE_SIZE - 1) / USAGE_PAGE_SIZE
        val current = query.page.coerceIn(1, totalPages)
        val offset = (current - 1) * USAGE_PAGE_SIZE

        val items = handle.createQuery(
            """
            $USAGE_CTES
            SELECT template_key, catalog_key, catalog_type, template_name, variant_key, version_id,
                   version_status, stencil_version, instance_count, upgradable, block_reason
            FROM reasoned
            WHERE (:filter = 'both'
                OR (:filter = 'upgradable' AND upgradable)
                OR (:filter = 'not-upgradable' AND NOT upgradable))
            ORDER BY (CASE WHEN catalog_type = 'AUTHORED' THEN 0 ELSE 1 END), catalog_key, template_name, variant_key, version_id DESC
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("catalogKey", query.stencilId.catalogKey)
            .bind("stencilId", query.stencilId.key.value)
            .bind("filter", filter)
            .bind("limit", USAGE_PAGE_SIZE)
            .bind("offset", offset)
            .map { rs, _ ->
                StencilUsageDetail(
                    templateId = TemplateKey.of(rs.getString("template_key")),
                    catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                    catalogType = CatalogType.valueOf(rs.getString("catalog_type")),
                    templateName = rs.getString("template_name"),
                    variantId = VariantKey.of(rs.getString("variant_key")),
                    versionId = VersionKey.of(rs.getInt("version_id")),
                    versionStatus = rs.getString("version_status"),
                    stencilVersion = rs.getInt("stencil_version"),
                    instanceCount = rs.getInt("instance_count"),
                    upgradable = rs.getBoolean("upgradable"),
                    upgradeBlockReason = rs.getString("block_reason")
                        ?.let { StencilUsageDetail.UpgradeBlockReason.valueOf(it) },
                )
            }
            .list()

        StencilUsagePage(
            items = items,
            page = current,
            totalPages = totalPages,
            total = total,
            totalAll = totalAll,
            upgradableCount = upgradableCount,
            filter = filter,
        )
    }
}
