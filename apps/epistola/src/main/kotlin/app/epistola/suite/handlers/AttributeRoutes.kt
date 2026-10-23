// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.htmx.CatalogScopedRoutes
import app.epistola.suite.htmx.catalogScopedRoutes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AttributeRoutes(private val handler: AttributeHandler) {
    // A list belongs to a catalog; there is none across the tenant. A resource's pages and actions are
    // its catalog's, like its list.
    @Bean
    fun attributeCatalogRoutes(): CatalogScopedRoutes = catalogScopedRoutes {
        GET("/{catalogId}/attributes", handler::list)
        "/{catalogId}/attributes".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{attributeId}/edit", handler::editForm)
            PATCH("/{attributeId}", handler::update)
            POST("/{attributeId}/delete", handler::delete)
        }
    }
}
