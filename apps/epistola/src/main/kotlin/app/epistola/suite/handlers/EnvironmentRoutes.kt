// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.htmx.CATALOG_NAMESPACE_VARIABLE
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
            GET("/{environmentId}/try", handler::tryDocument)
            POST("/{environmentId}/deployments", handler::deploy)
            POST("/{environmentId}/deployments/{catalogId}/undeploy", handler::undeploy)
            POST("/{environmentId}/drafts/{catalogId}/undeploy", handler::undeployDraft)
            POST("/{environmentId}/production", handler::setProduction)
            POST("/{environmentId}/delete", handler::delete)
        }
        // A catalog by its address, namespace and key (ADR 0034, section 9).
        "/tenants/{tenantId}/environments/{environmentId}".nest {
            POST("/deployments/{$CATALOG_NAMESPACE_VARIABLE}/{catalogId}/undeploy", handler::undeploy)
            POST("/drafts/{$CATALOG_NAMESPACE_VARIABLE}/{catalogId}/undeploy", handler::undeployDraft)
        }
    }
}
