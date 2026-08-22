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
class ResourceGraphRoutes(private val handler: ResourceGraphHandler) {
    @Bean
    fun resourceGraphRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/resource-graph".nest {
            GET("", handler::page)
            GET("/nodes", handler::nodes)
            GET("/subgraph", handler::subgraph)
            GET("/evidence", handler::evidence)
            GET("/move-preview", handler::movePreview)
            POST("/move", handler::move)
        }
    }
}
