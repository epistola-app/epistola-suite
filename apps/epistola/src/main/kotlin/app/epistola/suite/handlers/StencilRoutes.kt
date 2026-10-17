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
class StencilRoutes(private val handler: StencilHandler) {
    @Bean
    fun stencilRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/stencils".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)

            "/{catalogId}/{stencilId}".nest {
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
