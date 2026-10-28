// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.assets.Asset
import app.epistola.suite.catalog.dependencies.GetCatalogBorrowing
import app.epistola.suite.catalog.queries.CatalogReleaseSummary
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.handlers.CatalogViews.catalogGraph
import app.epistola.suite.handlers.CatalogViews.catalogPage
import app.epistola.suite.handlers.CatalogViews.deployments
import app.epistola.suite.handlers.CatalogViews.draftBar
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.catalog
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.wantsFragmentResponse
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.net.URI

@Component
class CatalogHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** `GET /catalogs/graph`: which catalogs of the tenant use which, as a drawing. */
    fun graph(request: ServerRequest): ServerResponse {
        val tenantKey = request.tenantId().key
        requirePermission(tenantKey, Permission.CATALOG_VIEW)
        return ServerResponse.ok().page("catalogs/graph") {
            "pageTitle" to "Catalog dependencies - Epistola"
            "activeNavSection" to "catalogs"
            "tenantId" to tenantKey
            "graph" to catalogGraph(tenantKey, focus = null)
        }
    }

    /**
     * `GET /{catalogId}/browse`: the catalog page — the next version, every version, where each is
     * live (docs/catalog-workspace-navigation.md). It is about the catalog, not one version, so a
     * segment naming a version goes to that version's own page.
     */
    fun browse(request: ServerRequest): ServerResponse {
        val segment = request.catalogSegment()
        if (segment.draft != null) {
            return redirect(CatalogPages.draft(request.tenantId(), segment))
        }
        return browse(request, error = null)
    }

    /**
     * `GET /{catalogId}/draft`: a draft's own page — what is not published yet, its contents and its
     * details. A plain segment is the next version; `key@<draft id>` parallel work. A catalog that
     * cannot be changed here shows no drafts, and a catalog with nothing open goes to its catalog page, which offers to
     * start.
     */
    fun draftPage(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = draftBar(tenantId, segment)
        if (bar.chosen == null) return redirect(CatalogPages.browse(tenantId, segment))
        return catalogPage(request, "catalogs/draft", segment, bar, error = null, unusedAfterMerge = unusedAfterMerge(request, tenantId, segment, bar))
    }

    /**
     * After a merge (`?merged=<release>`), the catalogs this draft declares and no longer uses, which
     * the page offers to remove; null otherwise, and where the draft cannot be changed here.
     */
    private fun unusedAfterMerge(request: ServerRequest, tenantId: TenantId, segment: CatalogSegment, bar: CatalogDraftBar): UnusedAfterMerge? {
        val merged = request.param("merged").orElse(null)?.takeIf { it.isNotBlank() } ?: return null
        if (!bar.editable) return null
        val unused = GetCatalogBorrowing(segment.catalogIn(tenantId.key), segment.ref(tenantId.key)).query().unused
        return UnusedAfterMerge(merged, unused).takeIf { unused.isNotEmpty() }
    }

    /** `GET /{catalogId}/releases`: every release and draft of the catalog, each to its own page. */
    fun releasesPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/releases", catalogWide = true)

    /**
     * `GET /{catalogId}/versions`: where Releases was before releases were called releases. A
     * permanent redirect there, its query kept; an HTMX request is sent there by `HX-Location`, the
     * boosted navigation a link takes, since a redirect it follows would leave the old address shown.
     */
    fun formerVersionsPage(request: ServerRequest): ServerResponse {
        val query = request.uri().rawQuery?.let { "?$it" }.orEmpty()
        val target = CatalogPages.releases(request.catalog(request.tenantId())) + query
        if (request.wantsFragmentResponse) return request.htmx { dialogLocation(target) }
        return ServerResponse.status(HttpStatus.PERMANENT_REDIRECT).location(URI.create(target)).build()
    }

    /** `GET /{catalogId}/deployments`: what each environment serves of the catalog, and deploying another version. */
    fun deploymentsPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/deployments", catalogWide = true)

    /** `GET /{catalogId}/dependencies`: the catalogs this version depends on, how it stands with each, and the catalogs that depend on it. */
    fun dependenciesPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/dependencies", catalogWide = false)

    /** `GET /{catalogId}/settings`: what this version goes out with (name, description, presentation), its key, and Exchange. */
    fun settingsPage(request: ServerRequest): ServerResponse = catalogSection(request, "catalogs/settings", catalogWide = false)

    /**
     * One of a catalog's own pages (docs/workspace-shell.md). A [catalogWide] page is the same for
     * every version, so it is shown at the catalog; the others at the version the segment names.
     */
    private fun catalogSection(request: ServerRequest, template: String, catalogWide: Boolean): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().let { if (catalogWide) it.at(null) else it }
        val bar = draftBar(tenantId, segment)
        return catalogPage(request, template, segment, bar, error = null)
    }

    /**
     * `GET /{catalogId}/bar-status`: the context bar's status for the version the segment names —
     * what is not published yet, and Publish — loaded after the page, because working it out builds
     * the catalog.
     */
    fun barStatus(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = draftBar(tenantId, segment)
        val readiness = if (bar.editable && bar.chosen != null) CatalogReleaseReadiness.of(bar) else null
        return request.htmx {
            fragment("catalogs/workspace", "bar-status") {
                "tenantId" to tenantId.key
                "bar" to bar
                "readiness" to readiness
            }
            onFullPage { redirect(CatalogPages.browse(tenantId, segment)) }
        }
    }

    /**
     * The page a draft action refused on, with [message] shown: the draft's own page when [segment]
     * names an open draft, such as discarding one that alone holds the tenant's default theme, else
     * the catalog page.
     */
    fun browseWithError(request: ServerRequest, segment: CatalogSegment, message: String): ServerResponse {
        val tenantId = request.tenantId()
        val bar = draftBar(tenantId, segment)
        return if (bar.chosen != null) {
            catalogPage(request, "catalogs/draft", segment, bar, error = null, draftError = message)
        } else {
            catalogPage(request, "catalogs/browse", segment.at(null), draftBar(tenantId, segment.at(null)), error = null, draftError = message)
        }
    }

    private fun browse(
        request: ServerRequest,
        error: String?,
        deployRefusal: DeployRefusalView? = null,
    ): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment().at(null)
        return catalogPage(request, "catalogs/browse", segment, draftBar(tenantId, segment), error, deployRefusal)
    }

    data class CatalogMetadataAttributeField(val descriptor: AttributeDescriptor, val value: String?)

    data class CatalogPresentationAssetView(val slug: String, val asset: Asset?) {
        val available: Boolean get() = asset != null
    }

    /** An environment, and the releases of the catalog on screen it serves, highest first (empty when none). */
    data class DeploymentRowView(
        val environmentKey: String,
        val environmentName: String,
        val production: Boolean = false,
        val served: List<ServedReleaseView>,
        val implicit: List<ImplicitUseView> = emptyList(),
        /** What it uses instead of what is deployed (a followed draft, a pinned version); null when nothing. */
        val uses: String? = null,
        /** The draft it follows, when [uses] is one. */
        val followsDraft: String? = null,
    ) {
        val servedVersions: Set<String> get() = served.mapTo(HashSet()) { it.version }

        /** The version an application gets there unless it asks for another: the highest it serves. */
        val inUse: String? get() = served.firstOrNull()?.version

        /** The environments following the draft [draftId]. */
        fun follows(draftId: String?): Boolean = draftId != null && followsDraft == draftId
    }

    /** A version of this catalog an environment uses because [neededBy] (catalog names) depend on it. */
    data class ImplicitUseView(val version: String, val neededBy: List<String>)

    /** One release an environment serves, and since when. */
    data class ServedReleaseView(
        val version: String,
        val deployedAt: java.time.OffsetDateTime,
        /** Generation requests through the environment accepted for it since it was deployed. */
        val documentsRequested: Long = 0,
        /** When the last was; null when none was. */
        val lastUsedAt: java.time.OffsetDateTime? = null,
    ) {
        /** How much it is used, as people read it. */
        val usage: String get() = DeploymentUsageText.of(documentsRequested, lastUsedAt)
    }

    /** One release as the catalog page shows it: the release, and what may be done with it. */
    data class CatalogReleaseView(
        val release: CatalogReleaseSummary,
        /** Already sent to Exchange, so the row says so instead of offering to send it again. */
        val published: Boolean,
        /** Publishing this release would be accepted — the command decides the same way. */
        val publishable: Boolean,
    )
}

/** The catalogs a draft no longer uses after merging [release], which its page asks whether to remove. */
data class UnusedAfterMerge(val release: String, val catalogs: List<app.epistola.suite.catalog.dependencies.BorrowedCatalog>)
