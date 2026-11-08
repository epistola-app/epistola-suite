// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class SupportRequestRoutes(private val handler: SupportRequestHandler) {
    @Bean
    fun supportRequestRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/support/requests".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/submit-form", handler::submitForm)
            POST("", handler::create)
            GET("/{requestId}", handler::detail)
            GET("/{requestId}/assets/{assetId}/content", handler::assetContent)
            POST("/{requestId}/status", handler::updateStatus)
            POST("/{requestId}/comments", handler::addComment)
        }
    }
}
