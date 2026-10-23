// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogScopedRoutes
import app.epistola.suite.htmx.catalogScopedRoutes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class StencilRoutes(private val handler: StencilHandler) {
    @Bean
    fun stencilRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/stencils".nest {
            GET("/search", handler::search)
        }
    }

    // A list belongs to a catalog; there is none across the tenant. A resource's pages and actions are
    // its catalog's, like its list.
    @Bean
    fun stencilCatalogRoutes(): CatalogScopedRoutes = catalogScopedRoutes {
        GET("/{catalogId}/stencils", handler::list)
        "/{catalogId}/stencils".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
            "/{stencilId}".nest {
                GET("", handler::detail)
                PATCH("", handler::update)
                POST("/delete", handler::delete)
                GET("/usage", handler::usageDetails)
                POST("/settings", handler::updateSettings)
                POST("/ready", handler::markReady)
                // The working copy, read and saved by the template editor (JSON)
                GET("/content", handler::getContent)
                PUT("/content", handler::updateContent)
            }
        }
    }
}
