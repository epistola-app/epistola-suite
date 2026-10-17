// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.handlers.VariantContentHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class DocumentTemplateRoutes(
    private val handler: DocumentTemplateHandler,
    private val variantHandler: VariantRouteHandler,
    private val contentHandler: VariantContentHandler,
    private val previewHandler: TemplatePreviewHandler,
    private val settingsTabHandler: SettingsTabHandler,
    private val dataContractTabHandler: DataContractTabHandler,
) {
    @Bean
    fun templateRoutes(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/templates".nest {
            // List and create (across all catalogs)
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)

            // Detail page — each tab has its own handler
            GET("/{catalogId}/{id}", handler::detail)
            GET("/{catalogId}/{id}/data-contract", dataContractTabHandler::dataContract)
            GET("/{catalogId}/{id}/settings", settingsTabHandler::settings)

            // Template actions
            POST("/{catalogId}/{id}/delete", handler::delete)
            PATCH("/{catalogId}/{id}/name", handler::updateName)
            PATCH("/{catalogId}/{id}/pdfa", handler::updatePdfa)
            PATCH("/{catalogId}/{id}/theme", handler::updateTheme)
            GET("/{catalogId}/{id}/api", handler::get)
            POST("/{catalogId}/{id}/validate-schema", handler::validateSchema)

            POST("/{catalogId}/{id}/ready", variantHandler::markTemplateReady)

            // Data contract: one schema and its examples, edited in place
            PATCH("/{catalogId}/{id}/contract", dataContractTabHandler::update)

            // Data example routes
            PATCH("/{catalogId}/{id}/data-examples/{exampleId}", handler::updateDataExample)
            DELETE("/{catalogId}/{id}/data-examples/{exampleId}", handler::deleteDataExample)

            // Variant routes
            POST("/{catalogId}/{id}/variants", variantHandler::createVariant)
            GET("/{catalogId}/{id}/variants/{variantId}/edit", variantHandler::editVariantForm)
            PATCH("/{catalogId}/{id}/variants/{variantId}", variantHandler::updateVariant)
            POST("/{catalogId}/{id}/variants/{variantId}/delete", variantHandler::deleteVariant)
            POST("/{catalogId}/{id}/variants/{variantId}/set-default", variantHandler::setDefaultVariant)

            POST("/{catalogId}/{id}/variants/{variantId}/ready", variantHandler::markVariantReady)

            // Editor route
            GET("/{catalogId}/{id}/variants/{variantId}/editor", handler::editor)

            // PDF preview
            POST("/{catalogId}/{id}/variants/{variantId}/preview", previewHandler::preview)

            // The variant's working model, saved by the editor
            PUT("/{catalogId}/{id}/variants/{variantId}/content", contentHandler::updateContent)
        }
    }
}
