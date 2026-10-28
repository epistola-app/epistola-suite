// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogScopedRoutes
import app.epistola.suite.htmx.catalogScopedRoutes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class CatalogRoutes(
    private val handler: CatalogHandler,
    private val catalogList: CatalogListHandler,
    private val releaseActions: CatalogReleaseActionsHandler,
    private val deployments: CatalogDeploymentHandler,
    private val upgrades: CatalogUpgradeHandler,
    private val metadata: CatalogMetadataHandler,
    private val transfers: CatalogTransferHandler,
    private val exchange: ExchangeCatalogHandler,
    private val organise: CatalogOrganiseHandler,
    private val nextRelease: CatalogReleaseHandler,
    private val drafts: CatalogDraftHandler,
    private val themeSettings: CatalogThemeSettingsHandler,
    private val merge: CatalogMergeHandler,
    private val registry: CatalogRegistryHandler,
    private val importer: CatalogImportHandler,
) {
    /** Before the per-catalog routes, which CatalogScopedRouting serves at the lowest precedence but one. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE - 2)
    fun catalogRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/catalogs".nest {
            GET("", catalogList::list)
            // Served before the per-catalog routes ([catalogScopedRouterFunction], which
            // CatalogScopedRouting serves after every other router): `exchange` is a legal catalog
            // slug, and relying on verb suffixes alone is one route away from an ambush.
            GET("/graph", handler::graph)
            GET("/exchange", exchange::browse)
            GET("/exchange/search", exchange::search)
            GET("/exchange/{namespace}/{catalogKey}", exchange::detail)
            POST("/exchange/{namespace}/{catalogKey}/install", exchange::install)
            // Getting a release from a container registry (alpha). Before the per-catalog routes,
            // like Exchange: `registry` is a legal catalog slug, and `/{catalogId}/install` exists.
            GET("/registry", registry::getDialog)
            GET("/registry/releases", registry::releases)
            POST("/registry/install", registry::install)
            GET("/new", catalogList::newForm)
            GET("/subscribe", catalogList::registerForm)
            POST("/subscribe", catalogList::register)
            POST("/create", catalogList::createCatalog)
            GET("/import", importer::importForm)
            POST("/import", importer::importZip)
            // Reorganising is its own page: a browser across catalogs that allows moving. Deep
            // linkable via ?resource=<type>:<catalog>/<key>, repeatable.
            GET("/organise", organise::page)
            GET("/organise/move", organise::move)
            GET("/organise/resources", organise::resources)
            POST("/organise/preview", organise::preview)
            POST("/organise/execute", organise::execute)
        }
    }

    /** A catalog's own pages and actions, by key and by namespace and key ([CatalogScopedRoutes]). */
    @Bean
    fun catalogScopedRouterFunction(): CatalogScopedRoutes = catalogScopedRoutes {
        POST("/{catalogId}/delete", catalogList::deleteCatalog)
        GET("/{catalogId}/release", releaseActions::releaseDialog)
        POST("/{catalogId}/release", releaseActions::release)
        // A catalog's drafts (contract ADR 0002, amendment 2). `{catalogId}` may name parallel
        // work as `key@<draft id>`, on these routes and every other per-catalog and per-resource route.
        GET("/{catalogId}/drafts/new", drafts::createForm)
        POST("/{catalogId}/drafts", drafts::create)
        POST("/{catalogId}/drafts/discard", drafts::discard)
        // Another version, at the page someone was on.
        GET("/{catalogId}/switch", drafts::switchVersion)
        // A draft tried on an environment, which follows it live.
        GET("/{catalogId}/follow", drafts::followForm)
        POST("/{catalogId}/follow", drafts::follow)
        POST("/{catalogId}/unfollow", drafts::unfollow)
        // Parallel work becomes the next version when it is ready.
        // Preparing the next release: readiness, declared dependencies
        POST("/{catalogId}/ready", nextRelease::markReady)
        GET("/{catalogId}/dependencies/edit", nextRelease::dependenciesForm)
        POST("/{catalogId}/dependencies", nextRelease::updateDependencies)
        POST("/{catalogId}/dependencies/upgrade", nextRelease::upgradeDependencies)
        POST("/{catalogId}/dependencies/remove-unused", nextRelease::removeUnusedDependencies)
        // Merging a published version the draft lacks into it (ADR 0032, alpha).
        GET("/{catalogId}/merge", merge::mergeDialog)
        GET("/{catalogId}/merge/sides", merge::mergeSides)
        POST("/{catalogId}/merge", merge::merge)
        POST("/{catalogId}/publish-current", releaseActions::publishCurrentRelease)
        POST("/{catalogId}/publications/{publicationId}/cancel", releaseActions::cancelPublication)
        GET("/{catalogId}/browse", handler::browse)
        // A draft's own page: the next version at a plain segment, another draft at key@version.
        GET("/{catalogId}/draft", handler::draftPage)
        GET("/{catalogId}/releases", handler::releasesPage)
        // Releases' former address, for links and bookmarks made before releases were called releases.
        GET("/{catalogId}/versions", handler::formerVersionsPage)
        GET("/{catalogId}/deployments", handler::deploymentsPage)
        GET("/{catalogId}/dependencies", handler::dependenciesPage)
        GET("/{catalogId}/settings", handler::settingsPage)
        GET("/{catalogId}/theme-settings", themeSettings::show)
        POST("/{catalogId}/theme-settings", themeSettings::save)
        GET("/{catalogId}/bar-status", handler::barStatus)
        GET("/{catalogId}/releases/{version}", releaseActions::releaseDetail)
        POST("/{catalogId}/releases/{version}/delete", releaseActions::deleteRelease)
        POST("/{catalogId}/deployments", deployments::deploy)
        POST("/{catalogId}/deployments/{environmentId}/undeploy", deployments::undeploy)
        POST("/{catalogId}/deployments/{environmentId}/unfollow", deployments::unfollow)
        GET("/{catalogId}/metadata", metadata::metadataForm)
        POST("/{catalogId}/metadata", metadata::updateMetadata)
        GET("/{catalogId}/usages", transfers::resourceUsages)
        GET("/{catalogId}/export-check", transfers::exportCheck)
        GET("/{catalogId}/export", transfers::export)
        GET("/{catalogId}/registry-publish", registry::publishDialog)
        POST("/{catalogId}/registry-publish", registry::publish)
        GET("/{catalogId}/install-preview", transfers::installPreview)
        POST("/{catalogId}/install", transfers::install)
        GET("/{catalogId}/upgrade-check", upgrades::upgradeCheck)
        GET("/{catalogId}/upgrade-preview", upgrades::upgradePreview)
        POST("/{catalogId}/upgrade", upgrades::upgrade)
        GET("/{catalogId}/exchange-upgrade", exchange::upgradeDialog)
        POST("/{catalogId}/exchange-upgrade", exchange::upgrade)
    }
}
