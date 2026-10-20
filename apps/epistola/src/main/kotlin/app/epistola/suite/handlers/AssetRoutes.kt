// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AssetRoutes(private val handler: AssetHandler) {
    @Bean
    fun assetRouterFunction(): RouterFunction<ServerResponse> = router {
        // A list belongs to a catalog; there is none across the tenant.
        GET("/tenants/{tenantId}/catalogs/{catalogId}/images", handler::list)
        "/tenants/{tenantId}/images".nest {
            GET("/search", handler::search)
            GET("/catalogs", handler::catalogs)
        }
        // A resource's pages and actions are its catalog's, like its list.
        "/tenants/{tenantId}/catalogs/{catalogId}/images".nest {
            GET("/new", handler::newForm)
            POST("", handler::upload)
            GET("/{assetId}/content", handler::content)
            POST("/{assetId}/delete", handler::delete)
        }
    }
}
