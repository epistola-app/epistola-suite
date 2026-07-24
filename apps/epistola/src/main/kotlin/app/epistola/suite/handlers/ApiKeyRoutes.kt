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
class ApiKeyRoutes(private val handler: ApiKeyHandler) {
    @Bean
    fun apiKeyRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/api-keys".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::create)
            POST("/{apiKeyId}/delete", handler::delete)
        }
    }
}
