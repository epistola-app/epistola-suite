// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils.queries

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.stencilAtAddress
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** A template variant with an instance of the stencil, and whether that instance is held back. */
data class StencilInstance(
    val templateKey: TemplateKey,
    val templateName: String,
    val variantKey: VariantKey,
    /**
     * True when the instance renders an older version of the stencil than its latest published one.
     * Interim, until stencils lose their versions (WP4): instances do not move to newer stencil content
     * on their own yet, so every instance on an older version is held back.
     */
    val heldBack: Boolean,
)

data class StencilInstancePage(val items: List<StencilInstance>, val total: Long)

/**
 * The template variants of a tenant that use a stencil, as a release would contain them: each
 * variant's latest published version. Ordered by template name and variant, paged in the database.
 */
data class ListStencilInstances(
    val stencilId: StencilId,
    val offset: Int = 0,
    val limit: Int = 20,
) : Query<StencilInstancePage>,
    RequiresPermission {
    override val permission = Permission.STENCIL_VIEW
    override val tenantKey: TenantKey get() = stencilId.tenantKey

    init {
        validate("offset", offset >= 0) { "offset must not be negative" }
        validate("limit", limit in 1..200) { "limit must be between 1 and 200" }
    }
}

@Component
class ListStencilInstancesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListStencilInstances, StencilInstancePage> {
    override fun handle(query: ListStencilInstances): StencilInstancePage = jdbi.withHandle<StencilInstancePage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            WITH latest_stencil AS (
                SELECT COALESCE(MAX(id), 0) AS version
                FROM stencil_versions
                WHERE tenant_key = :tenantId AND status = 'published'
                  AND stencil_resource_id = ${stencilAtAddress("tenantId", "catalogKey", "stencilId")}
            ),
            latest_published AS (
                SELECT DISTINCT ON (tv.template_resource_id, tv.variant_key)
                       tv.template_resource_id, tv.variant_key, tv.template_model
                FROM template_versions tv
                WHERE tv.tenant_key = :tenantId AND tv.status = 'published'
                ORDER BY tv.template_resource_id, tv.variant_key, tv.id DESC
            ),
            instances AS (
                SELECT dt.id AS template_key, dt.name AS template_name, lp.variant_key,
                       MIN(COALESCE((node.value -> 'props' ->> 'version')::int, 0)) AS oldest_version
                FROM latest_published lp
                JOIN document_templates dt ON dt.tenant_key = :tenantId AND dt.resource_id = lp.template_resource_id
                CROSS JOIN LATERAL jsonb_each(lp.template_model -> 'nodes') AS node(key, value)
                WHERE node.value ->> 'type' = 'stencil'
                  AND node.value -> 'props' ->> 'stencilId' = :stencilId
                GROUP BY dt.id, dt.name, lp.variant_key
            )
            SELECT i.template_key, i.template_name, i.variant_key,
                   i.oldest_version < (SELECT version FROM latest_stencil) AS held_back
            FROM instances i
            ORDER BY i.template_name, i.template_key, i.variant_key
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("catalogKey", query.stencilId.catalogKey)
            .bind("stencilId", query.stencilId.key.value)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                StencilInstance(
                    templateKey = TemplateKey.of(rs.getString("template_key")),
                    templateName = rs.getString("template_name"),
                    variantKey = VariantKey.of(rs.getString("variant_key")),
                    heldBack = rs.getBoolean("held_back"),
                )
            }
            .list()
        // Counted apart from the page, so a page past the end still reports the real total.
        val total = handle.createQuery(
            """
            WITH latest_stencil AS (
                SELECT COALESCE(MAX(id), 0) AS version
                FROM stencil_versions
                WHERE tenant_key = :tenantId AND status = 'published'
                  AND stencil_resource_id = ${stencilAtAddress("tenantId", "catalogKey", "stencilId")}
            ),
            latest_published AS (
                SELECT DISTINCT ON (tv.template_resource_id, tv.variant_key)
                       tv.template_resource_id, tv.variant_key, tv.template_model
                FROM template_versions tv
                WHERE tv.tenant_key = :tenantId AND tv.status = 'published'
                ORDER BY tv.template_resource_id, tv.variant_key, tv.id DESC
            ),
            instances AS (
                SELECT dt.id AS template_key, dt.name AS template_name, lp.variant_key,
                       MIN(COALESCE((node.value -> 'props' ->> 'version')::int, 0)) AS oldest_version
                FROM latest_published lp
                JOIN document_templates dt ON dt.tenant_key = :tenantId AND dt.resource_id = lp.template_resource_id
                CROSS JOIN LATERAL jsonb_each(lp.template_model -> 'nodes') AS node(key, value)
                WHERE node.value ->> 'type' = 'stencil'
                  AND node.value -> 'props' ->> 'stencilId' = :stencilId
                GROUP BY dt.id, dt.name, lp.variant_key
            )
            SELECT COUNT(*) FROM instances
            """,
        )
            .bind("tenantId", query.stencilId.tenantKey)
            .bind("catalogKey", query.stencilId.catalogKey)
            .bind("stencilId", query.stencilId.key.value)
            .mapTo(Long::class.java)
            .one()
        StencilInstancePage(items = rows, total = total)
    }
}
