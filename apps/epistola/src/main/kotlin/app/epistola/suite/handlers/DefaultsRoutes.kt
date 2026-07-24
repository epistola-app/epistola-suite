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
class DefaultsRoutes(private val handler: DefaultsHandler) {
    @Bean
    fun defaultsRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/defaults".nest {
            GET("", handler::defaults)
            POST("/locale", handler::updateLocale)
        }
    }
}
