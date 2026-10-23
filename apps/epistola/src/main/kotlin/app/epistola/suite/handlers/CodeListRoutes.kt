// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.htmx.CatalogScopedRoutes
import app.epistola.suite.htmx.catalogScopedRoutes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class CodeListRoutes(private val handler: CodeListHandler) {
    // A list belongs to a catalog; there is none across the tenant. A resource's pages and actions are
    // its catalog's, like its list.
    @Bean
    fun codeListCatalogRoutes(): CatalogScopedRoutes = catalogScopedRoutes {
        GET("/{catalogId}/code-lists", handler::list)
        "/{catalogId}/code-lists".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{codeListId}", handler::detail)
            POST("/{codeListId}/refresh", handler::refresh)
            POST("/{codeListId}/delete", handler::delete)
            PATCH("/{codeListId}/entries/{code}/hidden", handler::toggleEntryHidden)
            POST("/{codeListId}/entries/{code}/hidden", handler::toggleEntryHidden)
        }
    }
}
