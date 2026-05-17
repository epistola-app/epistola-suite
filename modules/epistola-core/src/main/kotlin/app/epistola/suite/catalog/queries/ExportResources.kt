package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.common.ids.CatalogKey
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
    val catalogKey: CatalogKey? = null,
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
            if (query.catalogKey != null) append(" AND catalog_key = :catalogKey")
            if (query.slugs != null) append(" AND id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bind("catalogKey", query.catalogKey)
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
    val catalogKey: CatalogKey? = null,
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
            append(
                """
                SELECT id, catalog_key, display_name, allowed_values::text,
                       code_list_catalog_key, code_list_slug
                FROM variant_attribute_definitions
                WHERE tenant_key = :tenantKey
                """,
            )
            if (query.catalogKey != null) append(" AND catalog_key = :catalogKey")
            if (query.slugs != null) append(" AND id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bind("catalogKey", query.catalogKey)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            val allowedValues: List<String> = rs.getString("allowed_values")?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
            } ?: emptyList()
            val bindingSlug = rs.getString("code_list_slug")
            val bindingCatalogKey = rs.getString("code_list_catalog_key")
            val ownCatalogKey = rs.getString("catalog_key")
            val binding = if (bindingSlug != null && bindingCatalogKey != null) {
                // Emit `catalogKey = null` when the binding points at the same
                // catalog as the attribute — keeps the wire format compact and
                // makes the manifest portable across renames of the attribute's
                // own catalog. Cross-catalog bindings keep the explicit
                // catalogKey so `DependencyRef.CodeList` can be matched.
                CodeListBindingRef(
                    catalogKey = if (bindingCatalogKey == ownCatalogKey) null else bindingCatalogKey,
                    slug = bindingSlug,
                )
            } else {
                null
            }
            AttributeResource(
                slug = rs.getString("id"),
                name = rs.getString("display_name"),
                allowedValues = allowedValues,
                codeListBinding = binding,
            )
        }.list()
    }
}

// ── Export Code Lists ────────────────────────────────────────────────────────

data class ExportCodeLists(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
    val catalogKey: CatalogKey? = null,
) : Query<List<CodeListResource>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class ExportCodeListsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ExportCodeLists, List<CodeListResource>> {

    override fun handle(query: ExportCodeLists): List<CodeListResource> = jdbi.withHandle<List<CodeListResource>, Exception> { handle ->
        // Two-query load: list the matching code lists first, then load all
        // their entries in a single batched fetch. Avoids the per-list query
        // an in-loop fetch would do and keeps row count predictable.
        val codeListSql = buildString {
            append(
                """
                SELECT catalog_key, slug, display_name, description
                FROM code_lists
                WHERE tenant_key = :tenantKey
                """,
            )
            if (query.catalogKey != null) append(" AND catalog_key = :catalogKey")
            if (query.slugs != null) append(" AND slug IN (<slugs>)")
            append(" ORDER BY catalog_key, slug")
        }
        val codeLists = handle.createQuery(codeListSql).bind("tenantKey", query.tenantKey).apply {
            if (query.catalogKey != null) bind("catalogKey", query.catalogKey)
            if (query.slugs != null) bindList("slugs", query.slugs)
        }.map { rs, _ ->
            Triple(rs.getString("catalog_key"), rs.getString("slug"), rs.getString("display_name") to rs.getString("description"))
        }.list()

        if (codeLists.isEmpty()) return@withHandle emptyList()

        // Load all candidate entries in one query — filter the result set
        // against the actual list of code lists in Kotlin. JDBI doesn't have
        // a native tuple-IN binder, and the entry set is small (a few
        // hundred rows max for the typical catalog), so a Kotlin post-filter
        // is cleaner than reaching for raw SQL templating.
        val wantedKeys = codeLists.map { (cat, slug, _) -> cat to slug }.toSet()
        val entriesByList = handle.createQuery(
            """
            SELECT catalog_key, code_list_slug, code, label, sort_order, hidden
            FROM code_list_entries
            WHERE tenant_key = :tenantKey
              AND catalog_key IN (<catalogs>)
              AND code_list_slug IN (<slugs>)
            ORDER BY catalog_key, code_list_slug, sort_order, code
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindList("catalogs", codeLists.map { it.first }.distinct())
            .bindList("slugs", codeLists.map { it.second }.distinct())
            .map { rs, _ ->
                (rs.getString("catalog_key") to rs.getString("code_list_slug")) to CodeListEntryEntry(
                    code = rs.getString("code"),
                    label = rs.getString("label"),
                    sortOrder = rs.getInt("sort_order"),
                    hidden = rs.getBoolean("hidden"),
                )
            }
            .list()
            .filter { it.first in wantedKeys }
            .groupBy({ it.first }, { it.second })

        codeLists.map { (catalogKey, slug, names) ->
            CodeListResource(
                slug = slug,
                name = names.first,
                description = names.second,
                entries = entriesByList[catalogKey to slug] ?: emptyList(),
            )
        }
    }
}

// ── Export Stencils ──────────────────────────────────────────────────────────

data class ExportStencils(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
    val catalogKey: CatalogKey? = null,
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
                      AND status = 'published'
                    ORDER BY id DESC
                    LIMIT 1
                ) sv ON TRUE
                WHERE s.tenant_key = :tenantKey
                """,
            )
            if (query.catalogKey != null) append(" AND s.catalog_key = :catalogKey")
            if (query.slugs != null) append(" AND s.id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bind("catalogKey", query.catalogKey)
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

// ── Export Fonts ─────────────────────────────────────────────────────────────

data class ExportFonts(
    override val tenantKey: TenantKey,
    val slugs: List<String>? = null,
    val catalogKey: CatalogKey? = null,
) : Query<List<FontResource>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
}

@Component
class ExportFontsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ExportFonts, List<FontResource>> {

    override fun handle(query: ExportFonts): List<FontResource> = jdbi.withHandle<List<FontResource>, Exception> { handle ->
        // Two-query load: list the matching fonts first, then load all their
        // variants in a single batched fetch (mirrors `ExportCodeLists`).
        val fontSql = buildString {
            append(
                """
                SELECT catalog_key, slug, name, kind
                FROM fonts
                WHERE tenant_key = :tenantKey
                """,
            )
            if (query.catalogKey != null) append(" AND catalog_key = :catalogKey")
            if (query.slugs != null) append(" AND slug IN (<slugs>)")
            append(" ORDER BY catalog_key, slug")
        }
        data class FontRow(val catalogKey: String, val slug: String, val name: String, val kind: String)
        val fonts = handle.createQuery(fontSql).bind("tenantKey", query.tenantKey).apply {
            if (query.catalogKey != null) bind("catalogKey", query.catalogKey)
            if (query.slugs != null) bindList("slugs", query.slugs)
        }.map { rs, _ ->
            FontRow(rs.getString("catalog_key"), rs.getString("slug"), rs.getString("name"), rs.getString("kind"))
        }.list()

        if (fonts.isEmpty()) return@withHandle emptyList()

        // System (CLASSPATH) faces are never exported — the wire format only
        // describes catalog-authored, asset-backed fonts. Filter the candidate
        // variants in Kotlin against the actual font set (JDBI has no tuple-IN
        // binder and the row count is tiny).
        val wantedKeys = fonts.map { it.catalogKey to it.slug }.toSet()
        val variantsByFont = handle.createQuery(
            """
            SELECT catalog_key, font_slug, weight, italic, is_variable, asset_key
            FROM font_variants
            WHERE tenant_key = :tenantKey
              AND source = 'ASSET'
              AND catalog_key IN (<catalogs>)
              AND font_slug IN (<slugs>)
            ORDER BY catalog_key, font_slug, italic, weight
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindList("catalogs", fonts.map { it.catalogKey }.distinct())
            .bindList("slugs", fonts.map { it.slug }.distinct())
            .map { rs, _ ->
                (rs.getString("catalog_key") to rs.getString("font_slug")) to FontVariantEntry(
                    weight = rs.getInt("weight"),
                    italic = rs.getBoolean("italic"),
                    assetSlug = rs.getObject("asset_key", java.util.UUID::class.java).toString(),
                    variable = rs.getBoolean("is_variable"),
                )
            }
            .list()
            .filter { it.first in wantedKeys }
            .groupBy({ it.first }, { it.second })

        fonts.map { font ->
            FontResource(
                slug = font.slug,
                name = font.name,
                kind = font.kind,
                variants = variantsByFont[font.catalogKey to font.slug] ?: emptyList(),
            )
        }
    }
}
