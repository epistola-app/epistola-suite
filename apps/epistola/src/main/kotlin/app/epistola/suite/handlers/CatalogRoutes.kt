// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class CatalogRoutes(
    private val handler: CatalogHandler,
    private val exchange: ExchangeCatalogHandler,
    private val organise: CatalogOrganiseHandler,
    private val nextRelease: CatalogReleaseHandler,
    private val drafts: CatalogDraftHandler,
    private val themeSettings: CatalogThemeSettingsHandler,
    private val merge: CatalogMergeHandler,
    private val registry: CatalogRegistryHandler,
) {
    @Bean
    fun catalogRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/catalogs".nest {
            GET("", handler::list)
            // Registered before the {catalogId} routes below. `exchange` is a legal catalog slug,
            // and while every per-catalog route carries a verb suffix that keeps these unambiguous
            // today, relying on that is one route away from an ambush.
            GET("/graph", handler::graph)
            GET("/exchange", exchange::browse)
            GET("/exchange/search", exchange::search)
            GET("/exchange/{namespace}/{catalogKey}", exchange::detail)
            POST("/exchange/{namespace}/{catalogKey}/install", exchange::install)
            // Getting a release from a container registry (alpha). Before the {catalogId} routes,
            // like Exchange: `registry` is a legal catalog slug, and `/{catalogId}/install` exists.
            GET("/registry", registry::getDialog)
            GET("/registry/releases", registry::releases)
            POST("/registry/install", registry::install)
            GET("/new", handler::newForm)
            GET("/subscribe", handler::registerForm)
            POST("/subscribe", handler::register)
            POST("/create", handler::createCatalog)
            GET("/import", handler::importForm)
            POST("/import", handler::importZip)
            POST("/{catalogId}/delete", handler::deleteCatalog)
            GET("/{catalogId}/release", handler::releaseDialog)
            POST("/{catalogId}/release", handler::release)
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
            // Merging a published version the draft lacks into it (ADR 0032, alpha).
            GET("/{catalogId}/merge", merge::mergeDialog)
            POST("/{catalogId}/merge", merge::merge)
            POST("/{catalogId}/publish-current", handler::publishCurrentRelease)
            POST("/{catalogId}/publications/{publicationId}/cancel", handler::cancelPublication)
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
            GET("/{catalogId}/releases/{version}", handler::releaseDetail)
            POST("/{catalogId}/releases/{version}/delete", handler::deleteRelease)
            POST("/{catalogId}/deployments", handler::deploy)
            POST("/{catalogId}/deployments/{environmentId}/undeploy", handler::undeploy)
            POST("/{catalogId}/deployments/{environmentId}/unfollow", handler::unfollow)
            // Reorganising is its own page: a browser across catalogs that allows moving. Deep
            // linkable via ?resource=<type>:<catalog>/<key>, repeatable.
            GET("/organise", organise::page)
            GET("/organise/move", organise::move)
            GET("/organise/resources", organise::resources)
            POST("/organise/preview", organise::preview)
            POST("/organise/execute", organise::execute)
            GET("/{catalogId}/metadata", handler::metadataForm)
            POST("/{catalogId}/metadata", handler::updateMetadata)
            GET("/{catalogId}/usages", handler::resourceUsages)
            GET("/{catalogId}/export-check", handler::exportCheck)
            GET("/{catalogId}/export", handler::export)
            GET("/{catalogId}/registry-publish", registry::publishDialog)
            POST("/{catalogId}/registry-publish", registry::publish)
            GET("/{catalogId}/install-preview", handler::installPreview)
            POST("/{catalogId}/install", handler::install)
            GET("/{catalogId}/upgrade-check", handler::upgradeCheck)
            GET("/{catalogId}/upgrade-preview", handler::upgradePreview)
            POST("/{catalogId}/upgrade", handler::upgrade)
            GET("/{catalogId}/exchange-upgrade", exchange::upgradeDialog)
            POST("/{catalogId}/exchange-upgrade", exchange::upgrade)
        }
    }
}
