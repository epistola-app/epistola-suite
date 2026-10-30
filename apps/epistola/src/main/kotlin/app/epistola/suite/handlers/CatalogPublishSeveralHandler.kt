// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogPublishOutcome
import app.epistola.suite.catalog.commands.PublishCatalogs
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Publishing several catalogs at once (`/catalogs/publish`): every catalog that can be changed here
 * and has something to publish, with the number it would get, what it publishes along and what stops
 * it, and one Publish for the ones ticked, dependencies first ([PublishCatalogs]). For a tenant whose
 * catalogs build on each other, as one upgraded from 1.x, that is one action instead of one per catalog.
 *
 * Each row reads the catalog as its own publish dialog does ([CatalogReleaseReadiness]), so the two
 * cannot disagree on what may be published.
 *
 * An environment may be chosen to deploy what is published to, each catalog with the releases it needs,
 * as the publish dialog's "Deploy to" does for one catalog. It is offered to someone who may deploy,
 * when the tenant has an environment; without one, the page says where to make one.
 */
@Component
class CatalogPublishSeveralHandler {

    /** One catalog with something to publish: what it becomes, and what stops it, in words; null when nothing does. */
    data class Row(
        val address: String,
        val name: String,
        val href: String,
        val version: String?,
        val changeCount: Int,
        val publishedAlong: List<String>,
        val blocked: String?,
    ) {
        val offered: Boolean get() = blocked == null
    }

    fun page(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        return render(tenantId, outcomes = null)
    }

    fun publish(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.CATALOG_RELEASE)
        val chosen = request.params()["catalog"].orEmpty().toSet()
        val catalogs = ListCatalogs(tenantId.key).query().filter { it.catalogId.address in chosen }.map { it.catalogId }
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        // Only an environment of this tenant, and only for someone who may deploy; anything else deploys nowhere.
        val deployTo = request.param("deployTo").orElse("").takeIf { it.isNotBlank() && auth.has(Permission.ENVIRONMENT_DEPLOY) }
            ?.let { key -> ListEnvironments(tenantId).query().firstOrNull { it.id.value == key }?.id }
        val result = PublishCatalogs(tenantId.key, catalogs, markReady = auth.has(Permission.TEMPLATE_EDIT), deployTo = deployTo).execute()
        return render(tenantId, outcomes = result.outcomes)
    }

    private fun render(tenantId: TenantId, outcomes: List<CatalogPublishOutcome>?): ServerResponse {
        val rows = rows(tenantId)
        val names = ListCatalogs(tenantId.key).query().associate { it.catalogId.address to it.name }
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        return ServerResponse.ok().page("catalogs/publish") {
            "pageTitle" to "Publish several catalogs - Epistola"
            "activeNavSection" to "catalogs"
            "tenantId" to tenantId.key
            "rows" to rows
            "outcomes" to outcomes?.map { OutcomeView(names[it.catalog.address] ?: it.catalog.key.value, it) }
            "mayDeploy" to auth.has(Permission.ENVIRONMENT_DEPLOY)
            "environments" to ListEnvironments(tenantId).query()
        }
    }

    /** An outcome with its catalog's name, for the page. */
    data class OutcomeView(val name: String, val outcome: CatalogPublishOutcome) {
        /** The environment it was deployed to, as text: a template cannot read a value class's value. */
        val deployedTo: String? get() = outcome.deployedTo?.value
    }

    private fun rows(tenantId: TenantId): List<Row> {
        val editable = ListCatalogEditability(tenantId.key).query().editableCatalogs
        return ListCatalogs(tenantId.key).query()
            .filter { it.catalogId in editable }
            .mapNotNull { catalog ->
                val segment = CatalogSegment(catalog.id, namespace = catalog.namespace)
                val bar = CatalogDraftBar.of(tenantId.key, segment) { CatalogPages.browse(tenantId, it) }
                if (!bar.editable || bar.chosen == null) return@mapNotNull null
                val readiness = CatalogReleaseReadiness.of(bar)
                if (!readiness.publishable || readiness.nothingToPublish) return@mapNotNull null
                // An empty catalog never published has a first version, but nothing in it to publish.
                if (readiness.changeCount == 0 && readiness.publishedAlong.isEmpty()) return@mapNotNull null
                Row(
                    address = catalog.catalogId.address,
                    name = catalog.name,
                    href = CatalogPages.browse(tenantId, segment),
                    version = readiness.publishVersion,
                    changeCount = readiness.changeCount,
                    publishedAlong = readiness.publishedAlong.map { it.name },
                    blocked = blockedReason(readiness),
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Why [readiness] cannot be published from here, as its own page says; null when it can. */
    private fun blockedReason(readiness: CatalogReleaseReadiness): String? = when {
        readiness.offerPublish -> null

        readiness.namespaceRefusal != null -> readiness.namespaceRefusal

        readiness.unresolvedDependencies.isNotEmpty() ->
            "It uses ${readiness.unresolvedDependencies.joinToString { it.catalogKey }}, which has nothing published or in progress to use."

        readiness.blockingBorrowed.isNotEmpty() ->
            "It uses ${readiness.blockingBorrowed.joinToString { it.name }}, which is not among its dependencies."

        readiness.notReady.isNotEmpty() && !readiness.mayMarkReady -> "Some changes are not marked ready, and you may not mark them."

        else -> "It cannot be published yet; its page says why."
    }
}
