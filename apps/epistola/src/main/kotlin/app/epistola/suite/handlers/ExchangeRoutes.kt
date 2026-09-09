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
class ExchangeRoutes(private val handler: ExchangeHandler) {
    @Bean
    fun exchangeRouterFunction(): RouterFunction<ServerResponse> = router {
        GET("/oauth/exchange/callback", handler::callback)
        "/tenants/{tenantId}/exchange".nest {
            GET("", handler::settings)
            POST("/connect", handler::connect)
            POST("/disconnect", handler::disconnect)
            POST("/namespace", handler::setNamespace)
        }
    }
}
