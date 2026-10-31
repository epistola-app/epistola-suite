// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.commands.CatalogPublishOutcome
import app.epistola.suite.catalog.commands.PublishCatalogs
import app.epistola.suite.catalog.queries.ListCatalogEditability
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.Environment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.requirePermission
import org.slf4j.LoggerFactory
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
 * What was published may then be deployed to an environment, from the outcome: the reader sees what was
 * published, and what was refused, before choosing where it goes. Each release is deployed with the
 * releases it needs ([DeployCatalogRelease] with `includeDependencies`). Publishing stays in catalog and
 * deploying in environments; this page is where the two meet.
 */
@Component
class CatalogPublishSeveralHandler {
    private val logger = LoggerFactory.getLogger(CatalogPublishSeveralHandler::class.java)

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
        val result = PublishCatalogs(tenantId.key, catalogs, markReady = auth.has(Permission.TEMPLATE_EDIT)).execute()
        return render(tenantId, outcomes = result.outcomes)
    }

    /**
     * What was just published (`release`, as `address@version`, in the order it was published), deployed
     * to `environment`, each with the releases it needs. A refusal is that release's alone: the others are
     * still deployed, and the page says why it was not.
     */
    fun deploy(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.ENVIRONMENT_DEPLOY)
        val environment = request.param("environment").orElse("")
            .let { key -> ListEnvironments(tenantId).query().firstOrNull { it.id.value == key } }
            ?: return render(tenantId, outcomes = null)
        val catalogs = ListCatalogs(tenantId.key).query().associateBy { it.catalogId.address }
        val deployments = request.params()["release"].orEmpty().mapNotNull { release ->
            val catalog = catalogs[release.substringBeforeLast('@')] ?: return@mapNotNull null
            val version = release.substringAfterLast('@')
            val refusal = try {
                DeployCatalogRelease(EnvironmentId(environment.id, tenantId), catalog.catalogId, version, includeDependencies = true).execute()
                null
            } catch (e: RuntimeException) {
                logger.info("Deploying {} {} to {} after publishing several was refused: {}", catalog.catalogId.address, version, environment.id.value, e.message)
                e.message ?: "It was refused."
            }
            DeploymentView(catalog.name, version, refusal)
        }
        return render(tenantId, outcomes = null, deployed = Deployed(environment.name, deployments))
    }

    private fun render(tenantId: TenantId, outcomes: List<CatalogPublishOutcome>?, deployed: Deployed? = null): ServerResponse {
        val rows = rows(tenantId)
        val names = ListCatalogs(tenantId.key).query().associate { it.catalogId.address to it.name }
        val auth = AuthContext.from(SecurityContext.current(), tenantId.key)
        val views = outcomes?.map { OutcomeView(names[it.catalog.address] ?: it.catalog.key.value, it) }
        return ServerResponse.ok().page("catalogs/publish") {
            "pageTitle" to "Publish several catalogs - Epistola"
            "activeNavSection" to "catalogs"
            "tenantId" to tenantId.key
            "rows" to rows
            "outcomes" to views
            "deployOffer" to views?.let { DeployOffer.of(it, auth.has(Permission.ENVIRONMENT_DEPLOY), ListEnvironments(tenantId).query()) }
            "deployed" to deployed
        }
    }

    /** An outcome with its catalog's name, for the page. */
    data class OutcomeView(val name: String, val outcome: CatalogPublishOutcome) {
        val published: Boolean get() = outcome.kind == CatalogPublishOutcome.Kind.PUBLISHED

        /** How the deploy form names this release: its catalog's address and its version. */
        val release: String get() = "${outcome.catalog.address}@${outcome.version}"
    }

    /**
     * Deploying what was just published: [offered] when something was published, the reader may deploy
     * and there is an environment to deploy to. Otherwise [withheld] says which of those is missing, so a
     * missing permission does not read like a missing environment.
     */
    data class DeployOffer(val releases: List<String>, val environments: List<Environment>, val withheld: Withheld?) {
        val offered: Boolean get() = withheld == null

        enum class Withheld { NOTHING_PUBLISHED, NOT_PERMITTED, NO_ENVIRONMENT }

        companion object {
            fun of(outcomes: List<OutcomeView>, mayDeploy: Boolean, environments: List<Environment>): DeployOffer {
                val releases = outcomes.filter { it.published }.map { it.release }
                val withheld = when {
                    releases.isEmpty() -> Withheld.NOTHING_PUBLISHED
                    !mayDeploy -> Withheld.NOT_PERMITTED
                    environments.isEmpty() -> Withheld.NO_ENVIRONMENT
                    else -> null
                }
                return DeployOffer(releases, environments, withheld)
            }
        }
    }

    /** What deploying to [environment] did, release by release. */
    data class Deployed(val environment: String, val deployments: List<DeploymentView>)

    /** One release deployed, or why it was not ([refusal]). */
    data class DeploymentView(val name: String, val version: String, val refusal: String?)

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
