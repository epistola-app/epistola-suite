// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.ui

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

/**
 * UI routes for the quality report. Under `/tenants/{tenantId}` like every other UI surface, and
 * deliberately **not** under `/api` — the REST API is for external systems only, and this is the
 * server-rendered UI (see `UiRestApiSeparationTest`).
 */
@Configuration
class QualityRoutes(private val handler: QualityHandler) {
    @Bean
    fun qualityRouterFunction(): RouterFunction<ServerResponse> = router {
        // The pages are a catalog's (like its lists); the partials and actions they call stay here.
        "/tenants/{tenantId}/catalogs/{catalogId}/quality".nest {
            GET("", handler::list)
            GET("/{findingId}", handler::detail)
        }
        "/tenants/{tenantId}/quality".nest {
            GET("/search", handler::search)
            GET("/manual-form", handler::manualForm)
            GET("/variants", handler::variantOptions)
            POST("", handler::createManual)
            POST("/{findingId}/ignore", handler::ignore)
            POST("/{findingId}/unignore", handler::unignore)
            POST("/{findingId}/resolve", handler::resolve)
            POST("/{findingId}/comments", handler::addComment)
        }
        "/tenants/{tenantId}/templates/{catalogId}/{id}/variants/{variantId}/quality".nest {
            GET("", handler::editorFindings)
            POST("/check", handler::runEditorChecks)
        }
    }
}
