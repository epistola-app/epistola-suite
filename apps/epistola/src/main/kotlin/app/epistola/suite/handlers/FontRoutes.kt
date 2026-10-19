// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class FontRoutes(private val handler: FontHandler) {
    @Bean
    fun fontRouterFunction(): RouterFunction<ServerResponse> = router {
        // A list belongs to a catalog; there is none across the tenant.
        GET("/tenants/{tenantId}/catalogs/{catalogId}/fonts", handler::list)
        "/tenants/{tenantId}/fonts".nest {
            GET("/new", handler::newForm)
            POST("", handler::upload)
            GET("/search", handler::search)
        }
        // A resource's pages and actions are its catalog's, like its list.
        "/tenants/{tenantId}/catalogs/{catalogId}/fonts".nest {
            GET("/{slug}/{weight}/{italic}/content", handler::content)
            POST("/{slug}/delete", handler::delete)
        }
    }
}
