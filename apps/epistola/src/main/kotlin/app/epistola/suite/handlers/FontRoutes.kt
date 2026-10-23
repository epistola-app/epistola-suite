// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.htmx.CatalogScopedRoutes
import app.epistola.suite.htmx.catalogScopedRoutes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class FontRoutes(private val handler: FontHandler) {
    @Bean
    fun fontRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/fonts".nest {
            GET("/search", handler::search)
        }
    }

    // A list belongs to a catalog; there is none across the tenant. A resource's pages and actions are
    // its catalog's, like its list.
    @Bean
    fun fontCatalogRoutes(): CatalogScopedRoutes = catalogScopedRoutes {
        GET("/{catalogId}/fonts", handler::list)
        "/{catalogId}/fonts".nest {
            GET("/new", handler::newForm)
            POST("", handler::upload)
            GET("/{slug}/{weight}/{italic}/content", handler::content)
            POST("/{slug}/delete", handler::delete)
        }
    }
}
