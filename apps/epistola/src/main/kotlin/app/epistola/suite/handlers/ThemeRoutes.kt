// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class ThemeRoutes(private val handler: ThemeHandler) {
    @Bean
    fun themeRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}".nest {
            POST("/default-theme", handler::setDefault)

            // A list belongs to a catalog; there is none across the tenant.
            GET("/catalogs/{catalogId}/themes", handler::list)
            "/themes".nest {
                GET("/search", handler::search)
                GET("/new", handler::newForm)
                POST("", handler::create)
            }
            // A resource's pages and actions are its catalog's, like its list.
            "/catalogs/{catalogId}/themes".nest {
                GET("/{themeId}", handler::detail)
                GET("/{themeId}/usage", handler::usage)
                PATCH("/{themeId}", handler::update)
                POST("/{themeId}/delete", handler::delete)
            }
        }
    }
}
