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
) {
    @Bean
    fun catalogRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/catalogs".nest {
            GET("", handler::list)
            // Registered before the {catalogId} routes below. `exchange` is a legal catalog slug,
            // and while every per-catalog route carries a verb suffix that keeps these unambiguous
            // today, relying on that is one route away from an ambush.
            GET("/exchange", exchange::browse)
            GET("/exchange/search", exchange::search)
            GET("/exchange/{namespace}/{catalogKey}", exchange::detail)
            POST("/exchange/{namespace}/{catalogKey}/install", exchange::install)
            GET("/new", handler::newForm)
            GET("/subscribe", handler::registerForm)
            POST("/subscribe", handler::register)
            POST("/create", handler::createCatalog)
            GET("/import", handler::importForm)
            POST("/import", handler::importZip)
            POST("/{catalogId}/delete", handler::unregister)
            GET("/{catalogId}/release", handler::releaseDialog)
            POST("/{catalogId}/release", handler::release)
            POST("/{catalogId}/publish-current", handler::publishCurrentRelease)
            POST("/{catalogId}/publications/{publicationId}/cancel", handler::cancelPublication)
            GET("/{catalogId}/browse", handler::browse)
            GET("/{catalogId}/metadata", handler::metadataForm)
            POST("/{catalogId}/metadata", handler::updateMetadata)
            GET("/{catalogId}/usages", handler::resourceUsages)
            GET("/{catalogId}/export-check", handler::exportCheck)
            GET("/{catalogId}/export", handler::export)
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
