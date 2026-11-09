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
class ApplicationRoutes(private val handler: ApplicationHandler) {
    @Bean
    fun applicationRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/applications".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            POST("", handler::create)
            GET("/{applicationId}", handler::detail)
            GET("/{applicationId}/edit", handler::editForm)
            POST("/{applicationId}/edit", handler::edit)
            POST("/{applicationId}/documents", handler::updateDocuments)
            POST("/{applicationId}/unattributed-documents", handler::updateUnattributedDocuments)
            POST("/{applicationId}/delete", handler::delete)
            GET("/{applicationId}/keys/new", handler::newKeyForm)
            POST("/{applicationId}/keys", handler::createKey)
            POST("/{applicationId}/keys/{apiKeyId}/delete", handler::revokeKey)
        }
        // Bookmarks and links from before applications: keys are managed on their application's page.
        GET("/tenants/{tenantId}/api-keys", handler::legacyApiKeys)
        GET("/tenants/{tenantId}/api-keys/new", handler::legacyApiKeys)
    }
}
