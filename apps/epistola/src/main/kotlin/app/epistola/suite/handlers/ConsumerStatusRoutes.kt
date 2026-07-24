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
class ConsumerStatusRoutes(private val handler: ConsumerStatusHandler) {
    @Bean
    fun consumerStatusRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/consumers".nest {
            GET("", handler::dashboard)
            GET("/refresh", handler::refresh)
        }
    }
}
