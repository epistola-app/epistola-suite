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
import app.epistola.suite.htmx.htmx
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
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
                // Deep link: ?resource=stencil:letters/header, repeatable. Rendered into the page so
                // the component starts with a selection instead of fetching then reconciling.
                "preselected" to request.params()["resource"].orEmpty().joinToString(","),
                "canApply" to canApply(tenantKey),
            ),
        )
    }

    /**
     * The focused move surface for one resource, deep-linked as
     * `?resource=<type>:<catalog>/<key>`.
     *
     * One endpoint serves both shapes: HTMX gets the dialog, so a resource page can open it in
     * place, and a pasted link gets the full page. Splitting them would mean a link that only works
     * when clicked from the right page.
     */
    fun move(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val tenantKey = request.tenantKey()
        val resource = request.param("resource").orElse("")
        if (resource.isBlank()) return ServerResponse.badRequest().build()

        val canApply = canApply(tenantKey)
        // A boosted link or a history restore also sends HX-Request, but swaps the whole page: the
        // DSL sends those to the page branch, where a bare dialog would have nothing to open it.
        return request.htmx {
            fragment("catalogs/organise-move", "dialog") {
                "tenantId" to tenantKey.value
                "resource" to resource
                "canApply" to canApply
            }
            onFullPage {
                page("catalogs/organise-move") {
                    "pageTitle" to "Move resource - Epistola"
                    "tenant" to GetTenant(tenantKey).query()
                    "activeNavSection" to "catalog-organise"
                    "tenantId" to tenantKey.value
                    "resource" to resource
                    "canApply" to canApply
                }
            }
        }
    }

    /**
     * Whether the reader may apply a move: previewing needs catalog view, applying catalog
     * management. The component offers Apply only when this holds, and otherwise says why.
     */
    private fun canApply(tenantKey: TenantKey) = SecurityContext.current().hasPermission(tenantKey, Permission.CATALOG_MANAGE)

    fun resources(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val tenantKey = request.tenantKey()
        // One more than a page, to know whether the page was cut off.
        val page = ListRelocatableResources(tenantKey, request.param("q").orElse(null), limit = PAGE_SIZE + 1).query()
        // Resources a deep link or the single-move dialog names are offered wherever they fall.
        val named = request.params()["resource"].orEmpty().mapNotNull(::parseAddress).toSet()
            .takeIf { it.isNotEmpty() }
            ?.let { ListRelocatableResources(tenantKey, limit = it.size, addresses = it).query() }
            .orEmpty()
        val resources = (named + page.take(PAGE_SIZE)).distinctBy { it.address }
        val catalogs = ListCatalogs(tenantKey).query().map {
            mapOf("key" to it.id.value, "name" to it.name, "type" to it.type.name.lowercase())
        }
        return json(
            mapOf(
                "catalogs" to catalogs,
                "truncated" to (page.size > PAGE_SIZE),
                "resources" to resources.map {
                    mapOf(
                        "id" to it.address.id,
                        "type" to it.address.type.wireName,
                        "catalogKey" to it.address.catalogKey,
                        "key" to it.address.key,
                        "name" to it.name,
                        "catalogName" to it.catalogName,
                        "note" to it.note,
                    )
                },
            ),
        )
    }

    fun preview(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val body = request.body(RelocationBatchRequest::class.java)
        val relocations = body.toRelocations() ?: return unknownType()
        return json(previewDto(PreviewCatalogResourceMove(request.tenantKey(), relocations).query()))
    }

    fun execute(request: ServerRequest): ServerResponse {
        if (!request.relocationEnabled()) return ServerResponse.notFound().build()
        val body = request.body(RelocationBatchRequest::class.java)
        val relocations = body.toRelocations() ?: return unknownType()
        return try {
            json(previewDto(MoveCatalogResources(request.tenantKey(), relocations, body.planFingerprint.orEmpty()).execute()))
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
        // The address a blocker or warning concerns, in the same wire form the listing uses for a
        // resource's id -- `source` itself carries the enum name, which the browser cannot match.
        "blockers" to preview.blockers.map { mapOf("code" to it.code, "message" to it.message, "source" to it.source, "sourceId" to it.source?.id) },
        "warnings" to preview.warnings.map { mapOf("code" to it.code, "message" to it.message, "source" to it.source, "sourceId" to it.source?.id) },
        "planFingerprint" to preview.planFingerprint,
        "executable" to preview.executable,
    )

    private fun ServerRequest.tenantKey() = TenantKey.of(pathVariable("tenantId"))

    /** Only the relocation toggle: this page no longer depends on the graph being enabled. */
    private fun ServerRequest.relocationEnabled() = ResolveFeatureToggles(tenantKey()).query()[KnownFeatures.RESOURCE_RELOCATION] == true

    private fun json(body: Any) = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(body)

    private fun conflict(body: Any) = ServerResponse.status(409).contentType(MediaType.APPLICATION_JSON).body(body)

    private fun unknownType() = ServerResponse.badRequest().contentType(MediaType.APPLICATION_JSON)
        .body(mapOf("code" to "unknown-type", "message" to "A resource type in the request is not one that can be moved"))

    /** `type:catalog/key`, as [ResourceAddress.id] writes it; null for anything else. */
    private fun parseAddress(id: String): ResourceAddress? {
        val type = CatalogResourceType.entries.singleOrNull { id.startsWith(it.wireName + ":") } ?: return null
        val path = id.removePrefix(type.wireName + ":")
        val catalog = path.substringBefore('/', "").takeIf { it.isNotEmpty() } ?: return null
        val key = path.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return null
        return ResourceAddress(type, catalog, key)
    }

    private companion object {
        /** Resources the browser loads before a search narrows them. */
        const val PAGE_SIZE = 50
    }
}

private data class RelocationBatchRequest(
    val relocations: List<RelocationRequest>,
    val planFingerprint: String? = null,
) {
    /** The relocations, or null when one names a type that cannot be moved. */
    fun toRelocations(): List<ResourceRelocation>? = relocations.map { it.toRelocation() ?: return null }
}

private data class RelocationRequest(
    val type: String,
    val catalog: String,
    val key: String,
    val targetCatalog: String,
    /** Omitted keeps the current key: a plain move rather than a move-and-rename. */
    val targetKey: String? = null,
) {
    fun toRelocation(): ResourceRelocation? {
        val resourceType = CatalogResourceType.entries.singleOrNull { it.wireName == type } ?: return null
        return ResourceRelocation(
            source = ResourceAddress(resourceType, catalog, key),
            target = ResourceAddress(resourceType, targetCatalog, targetKey?.takeIf { it.isNotBlank() } ?: key),
        )
    }
}
