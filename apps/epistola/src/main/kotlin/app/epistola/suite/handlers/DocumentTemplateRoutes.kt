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
        // A list belongs to a catalog; there is none across the tenant.
        GET("/tenants/{tenantId}/catalogs/{catalogId}/templates", handler::list)
        "/tenants/{tenantId}/templates".nest {
            // Search (the editors' pickers) and create; the pages are the catalog's, below
            GET("/search", handler::search)
        }
        // A resource's pages and actions are its catalog's, like its list.
        "/tenants/{tenantId}/catalogs/{catalogId}/templates".nest {
            GET("/new", handler::newForm)
            POST("", handler::create)
            // Detail page — each tab has its own handler
            GET("/{id}", handler::detail)
            GET("/{id}/data-contract", dataContractTabHandler::dataContract)
            GET("/{id}/settings", settingsTabHandler::settings)

            // Template actions
            POST("/{id}/delete", handler::delete)
            PATCH("/{id}/name", handler::updateName)
            PATCH("/{id}/pdfa", handler::updatePdfa)
            PATCH("/{id}/description", handler::updateDescription)
            PATCH("/{id}/theme", handler::updateTheme)
            GET("/{id}/api", handler::get)
            POST("/{id}/validate-schema", handler::validateSchema)
            POST("/{id}/ready", variantHandler::markTemplateReady)

            // Data contract: one schema and its examples, edited in place
            PATCH("/{id}/contract", dataContractTabHandler::update)
            PATCH("/{id}/data-examples/{exampleId}", handler::updateDataExample)
            DELETE("/{id}/data-examples/{exampleId}", handler::deleteDataExample)

            // Variants
            POST("/{id}/variants", variantHandler::createVariant)
            GET("/{id}/variants/{variantId}/edit", variantHandler::editVariantForm)
            PATCH("/{id}/variants/{variantId}", variantHandler::updateVariant)
            POST("/{id}/variants/{variantId}/delete", variantHandler::deleteVariant)
            POST("/{id}/variants/{variantId}/set-default", variantHandler::setDefaultVariant)
            POST("/{id}/variants/{variantId}/ready", variantHandler::markVariantReady)

            // The editor, its PDF preview, and the variant's working model it saves
            GET("/{id}/variants/{variantId}/editor", handler::editor)
            POST("/{id}/variants/{variantId}/preview", previewHandler::preview)
            PUT("/{id}/variants/{variantId}/content", contentHandler::updateContent)
        }
    }
}
