// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.catalog.protocol.CodeListEntryEntry
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.FontVariantEntry
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.catalogsAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
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
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

// ── Export Themes ────────────────────────────────────────────────────────────

data class ExportThemes(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val slugs: List<String>? = null,
) : Query<List<ThemeResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ExportThemesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportThemes, List<ThemeResource>> {

    override fun handle(query: ExportThemes): List<ThemeResource> = jdbi.withHandle<List<ThemeResource>, Exception> { handle ->
        val sql = buildString {
            append("SELECT id, name, description, document_styles::text, page_settings::text, block_style_presets::text, spacing_unit FROM current_themes WHERE tenant_key = :tenantKey")
            if (query.catalogKey != null) append(" AND catalog_id = ${catalogAt("tenantKey", "catalogKey")}")
            if (query.slugs != null) append(" AND id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bindCatalog("catalogKey", query.catalog)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            ThemeResource(
                key = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                documentStyles = rs.getString("document_styles")?.let { objectMapper.readValue(it, DocumentStyles::class.java) } ?: emptyMap(),
                pageSettings = rs.getString("page_settings")?.let { objectMapper.readValue(it, PageSettings::class.java) },
                blockStylePresets = rs.getString("block_style_presets")?.let { objectMapper.readValue(it, BlockStylePresets::class.java) },
                // NUMERIC, so the driver answers a BigDecimal: read as a Float, it was always null.
                spacingUnit = rs.getBigDecimal("spacing_unit")?.toFloat(),
            )
        }.list()
    }
}

// ── Export Attributes ────────────────────────────────────────────────────────

data class ExportAttributes(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val slugs: List<String>? = null,
) : Query<List<AttributeResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ExportAttributesHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportAttributes, List<AttributeResource>> {

    // The released form of each, as its content holds it (ADR 0033): the binding by alias and key.
    override fun handle(query: ExportAttributes): List<AttributeResource> = jdbi.withHandle<List<AttributeResource>, Exception> { handle ->
        val sql = buildString {
            append("SELECT (t.payload -> 'resource')::text AS resource FROM current_attributes t WHERE t.tenant_key = :tenantKey")
            if (query.catalogKey != null) append(" AND t.catalog_id = ${catalogAt("tenantKey", "catalogKey")}")
            if (query.slugs != null) append(" AND t.id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bindCatalog("catalogKey", query.catalog)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ -> objectMapper.readValue(rs.getString("resource"), AttributeResource::class.java) }.list()
    }
}

// ── Export Code Lists ────────────────────────────────────────────────────────

data class ExportCodeLists(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val slugs: List<String>? = null,
) : Query<List<CodeListResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ExportCodeListsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<ExportCodeLists, List<CodeListResource>> {

    // The released form of each, as its content holds it (ADR 0033): its source stays here.
    override fun handle(query: ExportCodeLists): List<CodeListResource> = jdbi.withHandle<List<CodeListResource>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT t.resource::text AS resource
                FROM current_code_lists t
                JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.tenant_key = :tenantKey
                """,
            )
            if (query.catalogKey != null) append(" AND t.catalog_id = ${catalogAt("tenantKey", "catalogKey")}")
            if (query.slugs != null) append(" AND t.id IN (<slugs>)")
            append(" ORDER BY c.catalog_key, t.id")
        }
        handle.createQuery(sql).bind("tenantKey", query.tenantKey).apply {
            if (query.catalogKey != null) bindCatalog("catalogKey", query.catalog)
            if (query.slugs != null) bindList("slugs", query.slugs)
        }
            .map { rs, _ -> objectMapper.readValue(rs.getString("resource"), CodeListResource::class.java) }
            .list()
    }
}

// ── Export Stencils ──────────────────────────────────────────────────────────

data class ExportStencils(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val slugs: List<String>? = null,
) : Query<List<StencilResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
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
                SELECT s.id, s.name, s.description, s.tags::text,
                       s.content::text, s.parameter_schema::text, s.reads_template_data
                FROM stencils s
                WHERE s.tenant_key = :tenantKey AND ${inCurrentVersion("s")}
                """,
            )
            if (query.catalogKey != null) append(" AND s.version_id = ${versionAt("tenantKey", "catalogKey")}")
            if (query.slugs != null) append(" AND s.id IN (<slugs>)")
        }
        val q = handle.createQuery(sql).bind("tenantKey", query.tenantKey)
        if (query.catalogKey != null) q.bindCatalog("catalogKey", query.catalog)
        if (query.slugs != null) q.bindList("slugs", query.slugs)
        q.map { rs, _ ->
            val tags: List<String> = rs.getString("tags")?.let {
                objectMapper.readValue(it, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
            } ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val parameterSchema: Map<String, Any?>? = rs.getString("parameter_schema")?.let {
                objectMapper.readValue(it, Map::class.java) as Map<String, Any?>
            }
            StencilResource(
                key = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                tags = tags,
                content = objectMapper.readValue(rs.getString("content"), TemplateDocument::class.java),
                parameterSchema = parameterSchema,
                readsTemplateData = rs.getBoolean("reads_template_data"),
            )
        }.list()
    }
}

// ── Export Fonts ─────────────────────────────────────────────────────────────

data class ExportFonts(
    override val tenantKey: TenantKey,
    val catalog: CatalogId? = null,
    val slugs: List<String>? = null,
) : Query<List<FontResource>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantKey == tenantKey) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission get() = Permission.CATALOG_VIEW
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
                SELECT c.catalog_id::text AS catalog_ref, c.catalog_key, f.slug, f.name, f.kind
                FROM fonts f
                JOIN catalogs c ON c.tenant_key = f.tenant_key AND c.catalog_id = f.catalog_id
                WHERE f.tenant_key = :tenantKey AND ${inCurrentVersion("f")}
                """,
            )
            if (query.catalogKey != null) append(" AND f.version_id = ${versionAt("tenantKey", "catalogKey")}")
            if (query.slugs != null) append(" AND f.slug IN (<slugs>)")
            append(" ORDER BY c.catalog_key, f.slug")
        }
        data class FontRow(val catalogKey: String, val slug: String, val name: String, val kind: String)
        val fonts = handle.createQuery(fontSql).bind("tenantKey", query.tenantKey).apply {
            if (query.catalogKey != null) bindCatalog("catalogKey", query.catalog)
            if (query.slugs != null) bindList("slugs", query.slugs)
        }.map { rs, _ ->
            FontRow(rs.getString("catalog_ref"), rs.getString("slug"), rs.getString("name"), rs.getString("kind"))
        }.list()

        if (fonts.isEmpty()) return@withHandle emptyList()

        // System (CLASSPATH) faces are never exported — the wire format only
        // describes catalog-authored, asset-backed fonts. Filter the candidate
        // variants in Kotlin against the actual font set (JDBI has no tuple-IN
        // binder and the row count is tiny).
        val wantedKeys = fonts.map { it.catalogKey to it.slug }.toSet()
        val variantsByFont = handle.createQuery(
            """
            SELECT family_catalog.catalog_id::text AS catalog_ref, family.slug AS font_slug,
                   faces.weight, faces.italic, binary_asset.id AS asset_key,
                   binary_asset.media_type, faces.content_hash
            FROM font_variants faces
            JOIN fonts family
              ON family.tenant_key = faces.tenant_key AND family.version_id = faces.version_id
             AND family.resource_id = faces.font_resource_id
            JOIN catalogs family_catalog
              ON family_catalog.tenant_key = family.tenant_key AND family_catalog.catalog_id = family.catalog_id
            JOIN assets binary_asset
              ON binary_asset.tenant_key = faces.tenant_key
             AND binary_asset.resource_id = faces.asset_resource_id
             AND ${inCurrentVersion("binary_asset")}
            WHERE faces.tenant_key = :tenantKey
              AND ${inCurrentVersion("family")}
              AND faces.source = 'ASSET'
              AND family_catalog.catalog_id::text IN (<catalogs>)
              AND family.slug IN (<slugs>)
            ORDER BY family_catalog.catalog_id, family.slug, faces.italic, faces.weight
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .bindList("catalogs", fonts.map { it.catalogKey }.distinct())
            .bindList("slugs", fonts.map { it.slug }.distinct())
            .map { rs, _ ->
                (rs.getString("catalog_ref") to rs.getString("font_slug")) to FontVariantEntry(
                    weight = rs.getInt("weight"),
                    italic = rs.getBoolean("italic"),
                    mediaType = rs.getString("media_type"),
                    contentHash = requireNotNull(rs.getString("content_hash")) {
                        "font face has no content hash; the content backfill has not run for it"
                    },
                )
            }
            .list()
            .filter { it.first in wantedKeys }
            .groupBy({ it.first }, { it.second })

        fonts.map { font ->
            FontResource(
                key = font.slug,
                name = font.name,
                kind = font.kind,
                variants = variantsByFont[font.catalogKey to font.slug] ?: emptyList(),
            )
        }
    }
}
