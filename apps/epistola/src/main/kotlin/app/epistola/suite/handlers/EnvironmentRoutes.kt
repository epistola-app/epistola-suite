// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class EnvironmentRoutes(private val handler: EnvironmentHandler) {
    @Bean
    fun environmentRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/environments".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{environmentId}", handler::detail)
            POST("/{environmentId}/deployments", handler::deploy)
            POST("/{environmentId}/deployments/{catalogId}/undeploy", handler::undeploy)
            POST("/{environmentId}/delete", handler::delete)
        }
    }
}
