// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.relocation.CatalogResourceMoveBlockedException
import app.epistola.suite.catalog.relocation.CatalogResourceMovePreview
import app.epistola.suite.catalog.relocation.ListRelocatableResources
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.ResourceRelocation
import app.epistola.suite.catalog.relocation.StaleCatalogResourceMovePlanException
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.queries.GetTenant
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Browsing catalogs in order to reorganise them.
 *
 * Relocation lived in the resource graph while it was an alpha, because the graph already had the
 * reference data a preview needs. That made it cheap to build rather than easy to find: the graph
 * is a read-only diagnostic tool, an author reorganising catalogs would not think to open it, and
 * it could only ever act on the single node in focus.
 *
 * This is the operation's own page, under Catalogs where catalogs are managed. It is a browser that
 * happens to allow moving — pick resources across catalogs, choose where they go, see what it does,
 * apply it as one batch. It is deep-linkable, so anything that notices a misplaced resource (the
 * graph included) can hand off here with it already selected.
 */
@Component
class CatalogOrganiseHandler {
    fun page(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val tenantKey = request.tenantKey()
        return ServerResponse.ok().render(
            "layout/shell",
            mapOf(
                "contentView" to "catalogs/organise",
                "pageTitle" to "Organise catalogs - Epistola",
                "tenantId" to tenantKey.value,
                "tenant" to GetTenant(tenantKey).query(),
                "activeNavSection" to "catalog-organise",
                // Deep link: ?resource=stencil:letters:header, repeatable. Rendered into the page so
                // the component starts with a selection instead of fetching then reconciling.
                "preselected" to request.params()["resource"].orEmpty().joinToString(","),
            ),
        )
    }

    fun resources(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val tenantKey = request.tenantKey()
        val resources = ListRelocatableResources(tenantKey, request.param("q").orElse(null)).query()
        val catalogs = ListCatalogs(tenantKey).query().map {
            mapOf("key" to it.id.value, "name" to it.name, "type" to it.type.name.lowercase())
        }
        return json(
            mapOf(
                "catalogs" to catalogs,
                "resources" to resources.map {
                    mapOf(
                        "id" to it.address.id,
                        "type" to it.address.type.wireName,
                        "catalogKey" to it.address.catalogKey,
                        "key" to it.address.key,
                        "name" to it.name,
                        "catalogName" to it.catalogName,
                        "catalogType" to it.catalogType,
                    )
                },
            ),
        )
    }

    fun preview(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val body = request.body(RelocationBatchRequest::class.java)
        return json(previewDto(PreviewCatalogResourceMove(request.tenantKey(), body.toRelocations()).query()))
    }

    fun execute(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val body = request.body(RelocationBatchRequest::class.java)
        return try {
            json(previewDto(MoveCatalogResources(request.tenantKey(), body.toRelocations(), body.planFingerprint.orEmpty()).execute()))
        } catch (_: StaleCatalogResourceMovePlanException) {
            conflict(mapOf("code" to "stale-plan", "message" to "Something changed since you previewed; preview again"))
        } catch (exception: CatalogResourceMoveBlockedException) {
            conflict(mapOf("code" to "move-blocked", "blockers" to exception.blockers))
        }
    }

    /** [CatalogResourceMovePreview.relocations] carries the internal identity; it stays server-side. */
    private fun previewDto(preview: CatalogResourceMovePreview) = mapOf(
        "relocations" to preview.relocations.map {
            mapOf(
                "source" to it.source,
                "target" to it.target,
                "mutableRewriteCount" to it.mutableRewriteCount,
                "immutableReferenceCount" to it.immutableReferenceCount,
            )
        },
        "mutableRewriteCount" to preview.mutableRewriteCount,
        "immutableReferenceCount" to preview.immutableReferenceCount,
        "blockers" to preview.blockers,
        "planFingerprint" to preview.planFingerprint,
        "executable" to preview.executable,
    )

    private fun ServerRequest.tenantKey() = TenantKey.of(pathVariable("tenantId"))

    /** Only the relocation toggle: this page no longer depends on the graph being enabled. */
    private fun ServerRequest.relocationEnabled() = ResolveFeatureToggles(tenantKey()).query()[KnownFeatures.RESOURCE_RELOCATION] == true

    private fun json(body: Any) = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body)

    private fun conflict(body: Any) = ServerResponse.status(409).contentType(MediaType.APPLICATION_JSON).body(body)
}

private data class RelocationBatchRequest(
    val relocations: List<RelocationRequest>,
    val planFingerprint: String? = null,
) {
    fun toRelocations() = relocations.map { it.toRelocation() }
}

private data class RelocationRequest(
    val type: String,
    val catalog: String,
    val key: String,
    val targetCatalog: String,
    /** Omitted keeps the current key: a plain move rather than a move-and-rename. */
    val targetKey: String? = null,
) {
    fun toRelocation(): ResourceRelocation {
        val resourceType = CatalogResourceType.entries.single { it.wireName == type }
        return ResourceRelocation(
            source = ResourceAddress(resourceType, catalog, key),
            target = ResourceAddress(resourceType, targetCatalog, targetKey?.takeIf { it.isNotBlank() } ?: key),
        )
    }
}
