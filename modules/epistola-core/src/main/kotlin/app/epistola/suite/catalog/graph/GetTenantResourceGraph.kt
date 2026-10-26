// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.ResourceIdentity
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.currentTemplateViewJoin
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
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
    private val builder: TenantResourceGraphBuilder,
) : QueryHandler<GetTenantResourceGraph, TenantResourceGraph> {
    override fun handle(query: GetTenantResourceGraph): TenantResourceGraph = jdbi.inTransaction<TenantResourceGraph, Exception>(TransactionIsolationLevel.REPEATABLE_READ) { handle ->
        builder.buildOn(handle, query.tenantKey, query.includeHistory)
    }
}

/**
 * Builds the tenant resource graph on a caller-supplied handle.
 *
 * Separate from the query handler because relocation needs the graph too — to decide whether a move
 * would leave the catalog dependency graph cyclic — and must see exactly the rows its own
 * transaction sees, including the ones it is about to rewrite. Dispatching the query through the
 * mediator would open a nested transaction with its own isolation level for a decision that has to
 * be part of the move, and importing the handler directly is what `DomainBoundaryTest` forbids.
 */
@Component
class TenantResourceGraphBuilder(
    private val objectMapper: ObjectMapper,
) {

    fun buildOn(handle: Handle, tenantKey: TenantKey, includeHistory: Boolean): TenantResourceGraph {
        val nodes = loadNodes(handle, tenantKey)
        val occurrences = buildList {
            addAll(loadRelationalReferences(handle, tenantKey))
            addAll(loadThemeReferences(handle, tenantKey))
            addAll(loadTemplateReferences(handle, tenantKey))
            addAll(loadStencilReferences(handle, tenantKey, includeHistory))
        }
        return TenantResourceGraph(nodes, resolveAndAggregate(nodes, occurrences, loadAliases(handle, tenantKey)))
    }

    private fun loadNodes(handle: Handle, tenantKey: TenantKey): List<ResourceNode> = handle.createQuery(
        """
            SELECT resource_id, resource_type, namespace, catalog_key, resource_key, resource_name, catalog_name
            FROM (
                SELECT a.resource_id, 'image' resource_type, c.namespace, c.catalog_key::text AS catalog_key, a.id::text resource_key, a.name resource_name, c.name catalog_name FROM current_images a JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id WHERE a.tenant_key = :tenantKey
                UNION ALL SELECT l.resource_id, 'codeList', c.namespace, c.catalog_key::text AS catalog_key, l.id, l.display_name, c.name FROM current_code_lists l JOIN catalogs c ON c.tenant_key = l.tenant_key AND c.catalog_id = l.catalog_id WHERE l.tenant_key = :tenantKey
                UNION ALL SELECT f.resource_id, 'font', c.namespace, c.catalog_key::text AS catalog_key, f.slug::text, f.name, c.name FROM current_fonts f JOIN catalogs c ON c.tenant_key = f.tenant_key AND c.catalog_id = f.catalog_id WHERE f.tenant_key = :tenantKey
                UNION ALL SELECT a.resource_id, 'attribute', c.namespace, c.catalog_key::text AS catalog_key, a.id, a.display_name, c.name FROM current_attributes a JOIN catalogs c ON c.tenant_key = a.tenant_key AND c.catalog_id = a.catalog_id WHERE a.tenant_key = :tenantKey
                UNION ALL SELECT t.resource_id, 'theme', c.namespace, c.catalog_key::text AS catalog_key, t.id::text, t.name, c.name FROM current_themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id WHERE t.tenant_key = :tenantKey
                UNION ALL SELECT s.resource_id, 'stencil', c.namespace, c.catalog_key::text AS catalog_key, s.id, s.name, c.name FROM current_stencils s JOIN catalogs c ON c.tenant_key = s.tenant_key AND c.catalog_id = s.catalog_id WHERE s.tenant_key = :tenantKey
                UNION ALL SELECT t.resource_id, 'template', c.namespace, c.catalog_key::text AS catalog_key, t.id::text, t.name, c.name FROM current_templates t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id WHERE t.tenant_key = :tenantKey
            ) resources
            ORDER BY catalog_name, resource_type, resource_name
        """,
    )
        .bind("tenantKey", tenantKey)
        .map { rs, _ ->
            ResourceNode(
                resourceId = ResourceIdentity.of(rs.getString("resource_id")),
                address = ResourceAddress(resourceType(rs.getString("resource_type")), rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("resource_key")),
                name = rs.getString("resource_name"),
                catalogName = rs.getString("catalog_name"),
            )
        }.list()

    /**
     * What each alias means in each catalog's current version, by the catalog's dependency rows:
     * `(from address, alias)` to the target's address. The graph resolves an aliased reference with
     * it, as [app.epistola.suite.catalog.CatalogReferences] does (ADR 0034, section 9).
     */
    fun loadAliases(handle: Handle, tenantKey: TenantKey): Map<Pair<String, String>, String> = handle.createQuery(
        """
            SELECT c.namespace, c.catalog_key::text AS catalog_key, d.alias, d.namespace AS dependency_namespace, d.dependency_key
            FROM catalog_dependencies d
            JOIN catalog_current_versions cv ON cv.tenant_key = d.tenant_key AND cv.version_id = d.version_id
            JOIN catalogs c ON c.tenant_key = cv.tenant_key AND c.catalog_id = cv.catalog_id
            WHERE d.tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        ("${rs.getString("namespace")}/${rs.getString("catalog_key")}" to rs.getString("alias")) to "${rs.getString("dependency_namespace")}/${rs.getString("dependency_key")}"
    }.list().toMap()

    private fun loadRelationalReferences(handle: Handle, tenantKey: TenantKey): List<Occurrence> {
        val result = mutableListOf<Occurrence>()
        handle.createQuery(
            """
                SELECT dc.namespace AS namespace, dc.catalog_key::text AS catalog_key, dt.id::text, thc.namespace AS theme_namespace, thc.catalog_key::text AS theme_catalog_key, th.id::text AS theme_key
                FROM current_templates dt
                JOIN catalogs dc ON dc.tenant_key = dt.tenant_key AND dc.catalog_id = dt.catalog_id
                JOIN current_themes th ON th.tenant_key = dt.tenant_key AND th.resource_id = dt.theme_resource_id
                JOIN catalogs thc ON thc.tenant_key = th.tenant_key AND thc.catalog_id = th.catalog_id
                WHERE dt.tenant_key = :tenantKey
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            Occurrence(
                source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("id")),
                selector = ReferenceSelector(CatalogResourceType.THEME, rs.getString("theme_catalog_key"), rs.getString("theme_key")),
                // Joined by identity: the catalog it is in, not an alias to resolve.
                targetCatalog = rs.getString("theme_namespace") + "/" + rs.getString("theme_catalog_key"),
                kind = "template-default-theme",
                semantics = ReferenceSemantics.RUNTIME,
                qualification = ReferenceQualification.EXPLICIT,
                evidence = ReferenceEvidence("Template defaults", ReferenceLifecycle.LIVE, location = "theme"),
            )
        }.list().let(result::addAll)

        handle.createQuery(
            """
                -- Resolved through the one alias resolver (catalog_version_references, ADR 0033).
                SELECT ac.namespace AS namespace, ac.catalog_key::text AS catalog_key, a.resource_key AS id,
                       clc.namespace AS code_list_namespace, clc.catalog_key::text AS code_list_catalog_key, a.ref_key AS code_list_slug
                FROM catalog_version_references('attribute') a
                JOIN catalogs ac ON ac.tenant_key = a.tenant_key AND ac.catalog_id = a.catalog_id
                JOIN catalogs clc ON clc.tenant_key = a.tenant_key AND clc.catalog_id = a.ref_catalog_id
                WHERE a.tenant_key = :tenantKey AND a.ref_type = 'codeList'
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            Occurrence(
                source = ResourceAddress(CatalogResourceType.ATTRIBUTE, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("id")),
                selector = ReferenceSelector(CatalogResourceType.CODE_LIST, rs.getString("code_list_catalog_key"), rs.getString("code_list_slug")),
                // Joined by identity: the catalog it is in, not an alias to resolve.
                targetCatalog = rs.getString("code_list_namespace") + "/" + rs.getString("code_list_catalog_key"),
                kind = "attribute-code-list",
                semantics = ReferenceSemantics.AUTHORING,
                qualification = ReferenceQualification.EXPLICIT,
                evidence = ReferenceEvidence("Attribute definition", ReferenceLifecycle.LIVE, location = "codeList"),
            )
        }.list().let(result::addAll)

        // A font face's file is a binary of its font, not an image (ADR 0033): no edge.

        handle.createQuery(
            """
                SELECT template_catalog.namespace AS namespace, template_catalog.catalog_key::text AS catalog_key, template.id::text AS template_key,
                       tv.id::text variant_key, attribute.key attribute_key
                FROM current_template_variants tv
                ${currentTemplateViewJoin("tv")}
                JOIN catalogs template_catalog
                  ON template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id
                CROSS JOIN LATERAL jsonb_object_keys(tv.attributes) attribute(key)
                WHERE tv.tenant_key = :tenantKey
            """,
        ).bind("tenantKey", tenantKey).map { rs, _ ->
            val rawKey = rs.getString("attribute_key")
            val parts = rawKey.split('.', limit = 2)
            val explicit = parts.size == 2
            Occurrence(
                source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("template_key")),
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
            SELECT tc.namespace AS namespace, tc.catalog_key::text AS catalog_key, th.id::text, th.document_styles::text, COALESCE(th.block_style_presets, '{}'::jsonb)::text presets
            FROM current_themes th
            JOIN catalogs tc ON tc.tenant_key = th.tenant_key AND tc.catalog_id = th.catalog_id
            WHERE th.tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        val source = ResourceAddress(CatalogResourceType.THEME, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("id"))
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("document_styles")), "documentStyles", ReferenceLifecycle.LIVE, null, null, this)
            scanJson(source, objectMapper.readTree(rs.getString("presets")), "blockStylePresets", ReferenceLifecycle.LIVE, null, null, this)
        }
    }.list().flatten()

    /** Each variant's working model; what releases hold is their own history, not this graph's. */
    private fun loadTemplateReferences(handle: Handle, tenantKey: TenantKey): List<Occurrence> = handle.createQuery(
        """
            SELECT template_catalog.namespace AS namespace, template_catalog.catalog_key::text AS catalog_key, template.id::text AS template_key,
                   variant.id::text AS variant_key, variant.template_model::text
            FROM current_template_variants variant
            ${currentTemplateViewJoin("variant")}
            JOIN catalogs template_catalog
              ON template_catalog.tenant_key = template.tenant_key AND template_catalog.catalog_id = template.catalog_id
            WHERE variant.tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        val source = ResourceAddress(CatalogResourceType.TEMPLATE, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("template_key"))
        val owner = "Variant ${rs.getString("variant_key")}"
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("template_model")), "templateModel", ReferenceLifecycle.LIVE, null, null, this, owner)
        }
    }.list().flatten()

    private fun loadStencilReferences(handle: Handle, tenantKey: TenantKey, includeHistory: Boolean): List<Occurrence> = handle.createQuery(
        """
            SELECT stencil_catalog.namespace AS namespace, stencil_catalog.catalog_key::text AS catalog_key, stencil.id AS stencil_key, stencil.content::text
            FROM current_stencils stencil
            JOIN catalogs stencil_catalog
              ON stencil_catalog.tenant_key = stencil.tenant_key AND stencil_catalog.catalog_id = stencil.catalog_id
            WHERE stencil.tenant_key = :tenantKey
        """,
    ).bind("tenantKey", tenantKey).map { rs, _ ->
        // A stencil has one working copy, so its references are live; there is no history to include.
        val source = ResourceAddress(CatalogResourceType.STENCIL, rs.getString("namespace"), rs.getString("catalog_key"), rs.getString("stencil_key"))
        buildList {
            scanJson(source, objectMapper.readTree(rs.getString("content")), "content", ReferenceLifecycle.LIVE, null, null, this, "Stencil")
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
        ResourceReferenceSites.scan(node, path).forEach { site ->
            destination += jsonOccurrence(source, site, lifecycle, status, version, owner)
        }
    }

    private fun jsonOccurrence(
        source: ResourceAddress,
        site: ReferenceSite,
        lifecycle: ReferenceLifecycle,
        status: String?,
        version: Int?,
        owner: String,
    ): Occurrence {
        val catalog = site.catalogKey
        val qualification = when {
            catalog != null -> ReferenceQualification.EXPLICIT
            site.kind.relativeWhenUnqualified -> ReferenceQualification.RELATIVE
            else -> ReferenceQualification.TENANT_GLOBAL
        }
        val effectiveCatalog = if (qualification == ReferenceQualification.RELATIVE) source.catalogKey else catalog
        return Occurrence(
            source,
            ReferenceSelector(site.kind.type, effectiveCatalog, site.key),
            site.kind.wireKind,
            site.kind.semantics,
            qualification,
            ReferenceEvidence(
                owner,
                lifecycle,
                status,
                version,
                site.location,
                site.pinnedVersion.takeIf { site.kind == ReferenceSiteKind.STENCIL_INSERTION },
            ),
        )
    }

    private fun resolveAndAggregate(
        nodes: List<ResourceNode>,
        occurrences: List<Occurrence>,
        aliases: Map<Pair<String, String>, String>,
    ): List<ResourceEdge> {
        val byAddress = nodes.associateBy { it.address }
        val byTypeAndKey = nodes.groupBy { it.address.type to it.address.key }
        return occurrences.groupBy { occurrence ->
            val alias = occurrence.selector.catalogKey
            // The catalog the reference names: by identity, else its alias from the source's catalog
            // (its own key is itself), else, undeclared, every catalog with that key.
            val target = occurrence.targetCatalog
                ?: alias?.let { if (it == occurrence.source.catalogKey) occurrence.source.catalogAddress else aliases[occurrence.source.catalogAddress to it] }
            val candidates = when {
                target != null -> byAddress[
                    ResourceAddress(occurrence.selector.type, target.substringBefore('/'), target.substringAfter('/'), occurrence.selector.key),
                ]?.let(::listOf).orEmpty()

                alias != null -> byTypeAndKey[occurrence.selector.type to occurrence.selector.key].orEmpty().filter { it.address.catalogKey == alias }

                else -> byTypeAndKey[occurrence.selector.type to occurrence.selector.key].orEmpty()
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
            ResourceEdge(
                id,
                key.source,
                target,
                key.selector,
                key.candidates,
                key.kind,
                key.semantics,
                key.qualification,
                key.resolution,
                grouped.map { it.evidence }.distinct(),
            )
        }.sortedBy { it.id }
    }

    private fun resourceType(value: String): CatalogResourceType = CatalogResourceType.entries.single { it.wireName == value }

    private data class Occurrence(
        val source: ResourceAddress,
        val selector: ReferenceSelector,
        val kind: String,
        val semantics: ReferenceSemantics,
        val qualification: ReferenceQualification,
        val evidence: ReferenceEvidence,
        /** The target's catalog address, when the reference joins it by identity. */
        val targetCatalog: String? = null,
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
