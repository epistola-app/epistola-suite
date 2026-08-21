// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

data class GetTenantResourceGraph(
    override val tenantKey: TenantKey,
    val includeHistory: Boolean = false,
) : Query<TenantResourceGraph>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class GetTenantResourceGraphHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetTenantResourceGraph, TenantResourceGraph> {

    override fun handle(query: GetTenantResourceGraph): TenantResourceGraph = jdbi.inTransaction<TenantResourceGraph, Exception> { handle ->
        val nodes = loadNodes(handle, query.tenantKey)
        val occurrences = buildList {
            addAll(loadRelationalReferences(handle, query.tenantKey))
            addAll(loadThemeReferences(handle, query.tenantKey))
            addAll(loadTemplateReferences(handle, query.tenantKey, query.includeHistory))
            addAll(loadStencilReferences(handle, query.tenantKey, query.includeHistory))
        }
        TenantResourceGraph(nodes, resolveAndAggregate(nodes, occurrences))
    }

    private fun loadNodes(handle: Handle, tenantKey: TenantKey): List<ResourceNode> = handle.createQuery(
        """
            SELECT resource_type, catalog_key, resource_key, resource_name, catalog_name, catalog_type
            FROM (
                SELECT 'asset' resource_type, a.catalog_key::text, a.id::text resource_key, a.name resource_name, c.name catalog_name, c.type::text catalog_type FROM assets a JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key WHERE a.tenant_key = :tenantKey
                UNION ALL SELECT 'codeList', l.catalog_key::text, l.slug::text, l.display_name, c.name, c.type::text FROM code_lists l JOIN catalogs c ON c.tenant_key = l.tenant_key AND c.id = l.catalog_key WHERE l.tenant_key = :tenantKey
                UNION ALL SELECT 'font', f.catalog_key::text, f.slug::text, f.name, c.name, c.type::text FROM fonts f JOIN catalogs c ON c.tenant_key = f.tenant_key AND c.id = f.catalog_key WHERE f.tenant_key = :tenantKey
                UNION ALL SELECT 'attribute', a.catalog_key::text, a.id::text, a.display_name, c.name, c.type::text FROM variant_attribute_definitions a JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.id = a.catalog_key WHERE a.tenant_key = :tenantKey
                UNION ALL SELECT 'theme', t.catalog_key::text, t.id::text, t.name, c.name, c.type::text FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.id = t.catalog_key WHERE t.tenant_key = :tenantKey
                UNION ALL SELECT 'stencil', s.catalog_key::text, s.id::text, s.name, c.name, c.type::text FROM stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.id = s.catalog_key WHERE s.tenant_key = :tenantKey
                UNION ALL SELECT 'template', t.catalog_key::text, t.id::text, t.name, c.name, c.type::text FROM document_templates t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.id = t.catalog_key WHERE t.tenant_key = :tenantKey
            ) resources
            ORDER BY catalog_name, resource_type, resource_name
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            ResourceNode(
                address = ResourceAddress(resourceType(rs.getString("resource_type")), rs.getString("catalog_key"), rs.getString("resource_key")),
                name = rs.getString("resource_name"),
                catalogName = rs.getString("catalog_name"),
                catalogType = rs.getString("catalog_type"),
            )
        }.list()

    private fun loadRelationalReferences(handle: Handle, tenantKey: TenantKey): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        handle.createQuery(
            """
                SELECT catalog_key::text, id::text, theme_catalog_key::text, theme_key::text
                FROM document_templates
                WHERE tenant_key = :tenantKey AND theme_key IS NOT NULL
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            Occurrence(
                source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("catalog_key"), rs.getString("id")),
                selector = ReferenceSelector(CatalogResourceType.THEME, rs.getString("theme_catalog_key"), rs.getString("theme_key")),
                kind = "template-default-theme",
                semantics = ReferenceSemantics.RUNTIME,
                qualification = ReferenceQualification.EXPLICIT,
                evidence = ReferenceEvidence("Template defaults", ReferenceLifecycle.LIVE, location = "theme"),
            )
        }.list().let(result::addAll)

        handle.createQuery(
            """
                SELECT catalog_key::text, id::text, code_list_catalog_key::text, code_list_slug::text
                FROM variant_attribute_definitions
                WHERE tenant_key = :tenantKey AND code_list_slug IS NOT NULL
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            Occurrence(
                source = ResourceAddress(CatalogResourceType.ATTRIBUTE, rs.getString("catalog_key"), rs.getString("id")),
                selector = ReferenceSelector(CatalogResourceType.CODE_LIST, rs.getString("code_list_catalog_key"), rs.getString("code_list_slug")),
                kind = "attribute-code-list",
                semantics = ReferenceSemantics.AUTHORING,
                qualification = ReferenceQualification.EXPLICIT,
                evidence = ReferenceEvidence("Attribute definition", ReferenceLifecycle.LIVE, location = "codeList"),
            )
        }.list().let(result::addAll)

        handle.createQuery(
            """
                SELECT catalog_key::text, font_slug::text, asset_key::text, weight, italic
                FROM font_variants
                WHERE tenant_key = :tenantKey AND source = 'ASSET'
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            Occurrence(
                source = ResourceAddress(CatalogResourceType.FONT, rs.getString("catalog_key"), rs.getString("font_slug")),
                selector = ReferenceSelector(CatalogResourceType.ASSET, rs.getString("catalog_key"), rs.getString("asset_key")),
                kind = "font-face-asset",
                semantics = ReferenceSemantics.RUNTIME,
                qualification = ReferenceQualification.EXPLICIT,
                evidence = ReferenceEvidence("Font face", ReferenceLifecycle.LIVE, location = "faces[${rs.getInt("weight")},${if (rs.getBoolean("italic")) "italic" else "normal"}]"),
            )
        }.list().let(result::addAll)

        handle.createQuery(
            """
                SELECT tv.catalog_key::text, tv.template_key::text, tv.id::text variant_key, attribute.key attribute_key
                FROM template_variants tv
                CROSS JOIN LATERAL jsonb_object_keys(tv.attributes) attribute(key)
                WHERE tv.tenant_key = :tenantKey
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            val rawKey = rs.getString("attribute_key")
            val parts = rawKey.split('.', limit = 2)
            val explicit = parts.size == 2
            Occurrence(
                source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("catalog_key"), rs.getString("template_key")),
                selector = ReferenceSelector(CatalogResourceType.ATTRIBUTE, parts.getOrNull(0).takeIf { explicit }, parts.last()),
                kind = "variant-attribute",
                semantics = ReferenceSemantics.AUTHORING,
                qualification = if (explicit) ReferenceQualification.EXPLICIT else ReferenceQualification.TENANT_GLOBAL,
                evidence = ReferenceEvidence("Variant ${rs.getString("variant_key")}", ReferenceLifecycle.LIVE, location = "attributes.$rawKey"),
            )
        }.list().let(result::addAll)
        return result
    }

    private fun loadThemeReferences(handle: Handle, tenantKey: TenantKey): List<Occurrence> = handle.createQuery(
        """
            SELECT catalog_key::text, id::text, document_styles::text, COALESCE(block_style_presets, '{}'::jsonb)::text presets
            FROM themes WHERE tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        val source = ResourceAddress(CatalogResourceType.THEME, rs.getString("catalog_key"), rs.getString("id"))
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("document_styles")), "documentStyles", ReferenceLifecycle.LIVE, null, null, this)
            scanJson(source, objectMapper.readTree(rs.getString("presets")), "blockStylePresets", ReferenceLifecycle.LIVE, null, null, this)
        }
    }.list().flatten()

    private fun loadTemplateReferences(handle: Handle, tenantKey: TenantKey, includeHistory: Boolean): List<Occurrence> = handle.createQuery(
        """
            SELECT catalog_key::text, template_key::text, variant_key::text, id, status, template_model::text, resolved_theme::text
            FROM template_versions
            WHERE tenant_key = :tenantKey AND (:includeHistory OR status IN ('draft', 'published'))
        """,
    ).bind("tenantKey", tenantKey).bind("includeHistory", includeHistory).map { rs, _ ->
        val source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("catalog_key"), rs.getString("template_key"))
        val status = rs.getString("status")
        val lifecycle = if (status == "archived") ReferenceLifecycle.HISTORICAL else ReferenceLifecycle.LIVE
        val owner = "Variant ${rs.getString("variant_key")}"
        val version = rs.getInt("id")
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("template_model")), "templateModel", lifecycle, status, version, this, owner)
            rs.getString("resolved_theme")?.let { scanResolvedFonts(source, objectMapper.readTree(it), lifecycle, status, version, this, owner) }
        }
    }.list().flatten()

    private fun loadStencilReferences(handle: Handle, tenantKey: TenantKey, includeHistory: Boolean): List<Occurrence> = handle.createQuery(
        """
            SELECT catalog_key::text, stencil_key::text, id, status, content::text
            FROM stencil_versions
            WHERE tenant_key = :tenantKey AND (:includeHistory OR status IN ('draft', 'published'))
        """,
    ).bind("tenantKey", tenantKey).bind("includeHistory", includeHistory).map { rs, _ ->
        val source = ResourceAddress(CatalogResourceType.STENCIL, rs.getString("catalog_key"), rs.getString("stencil_key"))
        val status = rs.getString("status")
        val lifecycle = if (status == "archived") ReferenceLifecycle.HISTORICAL else ReferenceLifecycle.LIVE
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("content")), "content", lifecycle, status, rs.getInt("id"), this, "Stencil version")
        }
    }.list().flatten()

    private fun scanJson(
        source: ResourceAddress,
        node: JsonNode,
        path: String,
        lifecycle: ReferenceLifecycle,
        status: String?,
        version: Int?,
        destination: MutableList<Occurrence>,
        owner: String = source.key,
    ) {
        if (node.isObject) {
            val type = node.path("type").textOrNull().orEmpty()
            val props = node.path("props")
            if (type == "stencil" && props.path("stencilId").textOrNull() != null) {
                destination += jsonOccurrence(source, CatalogResourceType.STENCIL, props.path("catalogKey").textOrNull(), props.path("stencilId").stringValue(), "stencil-insertion", ReferenceSemantics.PROVENANCE, "$path.props.stencilId", lifecycle, status, version, owner, props.path("version").intOrNull())
            }
            if (type == "image" && props.path("assetId").textOrNull() != null) {
                destination += jsonOccurrence(source, CatalogResourceType.ASSET, props.path("catalogKey").textOrNull(), props.path("assetId").stringValue(), "image-asset", ReferenceSemantics.RUNTIME, "$path.props.assetId", lifecycle, status, version, owner)
            }
            node.path("themeRef").takeIf { it.isObject && it.path("themeId").textOrNull() != null }?.let { ref ->
                destination += jsonOccurrence(source, CatalogResourceType.THEME, ref.path("catalogKey").textOrNull(), ref.path("themeId").stringValue(), "theme-override", ReferenceSemantics.RUNTIME, "$path.themeRef", lifecycle, status, version, owner)
            }
            node.path("fontFamily").takeIf { it.isObject && it.path("slug").textOrNull() != null }?.let { ref ->
                destination += jsonOccurrence(source, CatalogResourceType.FONT, ref.path("catalogKey").textOrNull(), ref.path("slug").stringValue(), "font-family", ReferenceSemantics.RUNTIME, "$path.fontFamily", lifecycle, status, version, owner)
            }
            node.properties().forEach { (key, child) -> scanJson(source, child, "$path.$key", lifecycle, status, version, destination, owner) }
        } else if (node.isArray) {
            node.forEachIndexed { index, child -> scanJson(source, child, "$path[$index]", lifecycle, status, version, destination, owner) }
        }
    }

    private fun scanResolvedFonts(source: ResourceAddress, snapshot: JsonNode, lifecycle: ReferenceLifecycle, status: String?, version: Int, destination: MutableList<Occurrence>, owner: String) {
        val fingerprints = snapshot.path("fontFingerprints")
        if (!fingerprints.isObject) return
        fingerprints.properties().forEach { (rawKey, _) ->
            val separator = rawKey.indexOf('/')
            val catalog = rawKey.substring(0, separator.coerceAtLeast(0)).ifBlank { null }
            val slug = if (separator >= 0) rawKey.substring(separator + 1) else rawKey
            destination += jsonOccurrence(source, CatalogResourceType.FONT, catalog, slug, "published-font-snapshot", ReferenceSemantics.PROVENANCE, "resolvedTheme.fontFingerprints.$rawKey", lifecycle, status, version, owner)
        }
    }

    private fun jsonOccurrence(source: ResourceAddress, type: CatalogResourceType, catalog: String?, key: String, kind: String, semantics: ReferenceSemantics, location: String, lifecycle: ReferenceLifecycle, status: String?, version: Int?, owner: String, pinnedVersion: Int? = null): Occurrence {
        val qualification = if (catalog != null) {
            ReferenceQualification.EXPLICIT
        } else if (type == CatalogResourceType.ASSET) {
            ReferenceQualification.TENANT_GLOBAL
        } else {
            ReferenceQualification.RELATIVE
        }
        val effectiveCatalog = if (qualification == ReferenceQualification.RELATIVE) source.catalogKey else catalog
        return Occurrence(
            source,
            ReferenceSelector(type, effectiveCatalog, key),
            kind,
            semantics,
            qualification,
            ReferenceEvidence(owner, lifecycle, status, version, location, pinnedVersion),
        )
    }

    private fun resolveAndAggregate(nodes: List<ResourceNode>, occurrences: List<Occurrence>): List<ResourceEdge> {
        val byAddress = nodes.associateBy { it.address }
        val byTypeAndKey = nodes.groupBy { it.address.type to it.address.key }
        return occurrences.groupBy { occurrence ->
            val candidates = if (occurrence.selector.catalogKey != null) {
                listOfNotNull(byAddress[ResourceAddress(occurrence.selector.type, occurrence.selector.catalogKey, occurrence.selector.key)])
            } else {
                byTypeAndKey[occurrence.selector.type to occurrence.selector.key].orEmpty()
            }.map { it.address }.sortedBy { it.id }
            val resolution = when (candidates.size) {
                0 -> ReferenceResolution.MISSING
                1 -> ReferenceResolution.RESOLVED
                else -> ReferenceResolution.AMBIGUOUS
            }
            ResolvedKey(
                source = occurrence.source,
                selector = occurrence.selector,
                kind = occurrence.kind,
                semantics = occurrence.semantics,
                qualification = occurrence.qualification,
                candidates = candidates,
                resolution = resolution,
            )
        }.map { (key, grouped) ->
            val target = key.candidates.singleOrNull()
            val id = listOf(key.source.id, key.kind, key.selector.type.wireName, key.selector.catalogKey.orEmpty(), key.selector.key).joinToString("|")
            ResourceEdge(id, key.source, target, key.selector, key.candidates, key.kind, key.semantics, key.qualification, key.resolution, grouped.map { it.evidence }.distinct())
        }.sortedBy { it.id }
    }

    private fun resourceType(value: String): CatalogResourceType = CatalogResourceType.entries.single { it.wireName == value }

    private fun JsonNode.textOrNull(): String? = takeUnless { isMissingNode || isNull || !isString }?.stringValue()?.takeIf { it.isNotBlank() }
    private fun JsonNode.intOrNull(): Int? = takeUnless { isMissingNode || isNull }?.asInt()

    private data class Occurrence(
        val source: ResourceAddress,
        val selector: ReferenceSelector,
        val kind: String,
        val semantics: ReferenceSemantics,
        val qualification: ReferenceQualification,
        val evidence: ReferenceEvidence,
    )

    private data class ResolvedKey(
        val source: ResourceAddress,
        val selector: ReferenceSelector,
        val kind: String,
        val semantics: ReferenceSemantics,
        val qualification: ReferenceQualification,
        val candidates: List<ResourceAddress>,
        val resolution: ReferenceResolution,
    )
}
