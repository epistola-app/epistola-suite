// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class AttributeRoutes(private val handler: AttributeHandler) {
    @Bean
    fun attributeRouterFunction(): RouterFunction<ServerResponse> = router {
        // A list belongs to a catalog; there is none across the tenant.
        GET("/tenants/{tenantId}/catalogs/{catalogId}/attributes", handler::list)
        "/tenants/{tenantId}/attributes".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
        }
        // A resource's pages and actions are its catalog's, like its list.
        "/tenants/{tenantId}/catalogs/{catalogId}/attributes".nest {
            GET("/{attributeId}/edit", handler::editForm)
            PATCH("/{attributeId}", handler::update)
            POST("/{attributeId}/delete", handler::delete)
        }
    }
}
