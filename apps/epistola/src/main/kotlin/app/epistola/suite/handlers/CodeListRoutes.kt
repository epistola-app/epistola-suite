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
class CodeListRoutes(private val handler: CodeListHandler) {
    @Bean
    fun codeListRouterFunction(): RouterFunction<ServerResponse> = router {
        // A list belongs to a catalog; there is none across the tenant.
        GET("/tenants/{tenantId}/catalogs/{catalogId}/code-lists", handler::list)
        "/tenants/{tenantId}/code-lists".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{catalogId}/{codeListId}", handler::detail)
            POST("/{catalogId}/{codeListId}/refresh", handler::refresh)
            POST("/{catalogId}/{codeListId}/delete", handler::delete)
            PATCH("/{catalogId}/{codeListId}/entries/{code}/hidden", handler::toggleEntryHidden)
            POST("/{catalogId}/{codeListId}/entries/{code}/hidden", handler::toggleEntryHidden)
        }
    }
}
