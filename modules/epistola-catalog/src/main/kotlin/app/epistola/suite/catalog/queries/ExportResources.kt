package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.protocol.AttributeResource
import app.epistola.suite.catalog.protocol.StencilResource
import app.epistola.suite.catalog.protocol.ThemeResource
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.themes.BlockStylePresets
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// ── Export Themes ────────────────────────────────────────────────────────────

data class ExportThemes(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
) : Query<List<ThemeResource>>,
    RequiresPermission {
    override val permission get() = Permission.THEME_VIEW
}

@Component
class ExportThemesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportThemes, List<ThemeResource>> {

    override fun handle(query: ExportThemes): List<ThemeResource> = jdbi.withHandle<List<ThemeResource>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, name, description, document_styles::text, page_settings::text, block_style_presets::text, spacing_unit FROM themes WHERE tenant_key = :tenantKey")
            if (query.slugs != null) append(" AND id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            ThemeResource(
                slug = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                documentStyles = rs.getString("document_styles")?.let { objectMapper.readValue(it, DocumentStyles::class.java) } ?: emptyMap(),
                pageSettings = rs.getString("page_settings")?.let { objectMapper.readValue(it, PageSettings::class.java) },
                blockStylePresets = rs.getString("block_style_presets")?.let { objectMapper.readValue(it, BlockStylePresets::class.java) },
                spacingUnit = rs.getObject("spacing_unit") as? Float,
            )
        }.list()
    }
}

// ── Export Attributes ────────────────────────────────────────────────────────

data class ExportAttributes(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
) : Query<List<AttributeResource>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class ExportAttributesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportAttributes, List<AttributeResource>> {

    override fun handle(query: ExportAttributes): List<AttributeResource> = jdbi.withHandle<List<AttributeResource>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, display_name, allowed_values::text FROM variant_attribute_definitions WHERE tenant_key = :tenantKey")
            if (query.slugs != null) append(" AND id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            val allowedValues: List<String> = rs.getString("allowed_values")?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
            } ?: emptyList()
            AttributeResource(
                slug = rs.getString("id"),
                name = rs.getString("display_name"),
                allowedValues = allowedValues,
            )
        }.list()
    }
}

// ── Export Stencils ──────────────────────────────────────────────────────────

data class ExportStencils(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
) : Query<List<StencilResource>>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_VIEW
}

@Component
class ExportStencilsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportStencils, List<StencilResource>> {

    override fun handle(query: ExportStencils): List<StencilResource> = jdbi.withHandle<List<StencilResource>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT s.id, s.name, s.description, s.tags::text, sv.content::text
                FROM stencils s
                JOIN LATERAL (
                    SELECT content FROM stencil_versions
                    WHERE tenant_key = s.tenant_key AND stencil_key = s.id
                    ORDER BY CASE WHEN status = 'published' THEN 0 ELSE 1 END, id DESC
                    LIMIT 1
                ) sv ON TRUE
                WHERE s.tenant_key = :tenantKey
                """,
            )
            if (query.slugs != null) append(" AND s.id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            val tags: List<String> = rs.getString("tags")?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
            } ?: emptyList()
            StencilResource(
                slug = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                tags = tags,
                content = objectMapper.readValue(rs.getString("content"), TemplateDocument::class.java),
            )
        }.list()
    }
}
