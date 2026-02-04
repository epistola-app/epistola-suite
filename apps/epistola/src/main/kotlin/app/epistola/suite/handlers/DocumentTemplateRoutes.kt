package app.epistola.suite.templates

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class DocumentTemplateRoutes(
    private val handler: DocumentTemplateHandler,
    private val variantHandler: VariantRouteHandler,
    private val versionHandler: VersionRouteHandler,
    private val previewHandler: TemplatePreviewHandler,
) {
    @Bean
    fun templateRoutes(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/templates".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            POST("", handler::create)

            // Template detail and actions
            GET("/{id}", handler::detail)
            POST("/{id}/delete", handler::delete)
            PATCH("/{id}", handler::update)
            PATCH("/{id}/theme", handler::updateTheme)
            GET("/{id}/api", handler::get)
            POST("/{id}/validate-schema", handler::validateSchema)

            // Data example routes
            PATCH("/{id}/data-examples/{exampleId}", handler::updateDataExample)
            DELETE("/{id}/data-examples/{exampleId}", handler::deleteDataExample)

            // Variant routes (delegated to VariantRouteHandler)
            POST("/{id}/variants", variantHandler::createVariant)
            GET("/{id}/variants/{variantId}", handler::variantDetail)
            DELETE("/{id}/variants/{variantId}", variantHandler::deleteVariant)
            POST("/{id}/variants/{variantId}/delete", variantHandler::deleteVariant)

            // Editor route (with variant)
            GET("/{id}/variants/{variantId}/editor", handler::editor)

            // PDF preview (delegated to TemplatePreviewHandler)
            POST("/{id}/variants/{variantId}/preview", previewHandler::preview)

            // Draft creation and updates (delegated to VersionRouteHandler)
            POST("/{id}/variants/{variantId}/draft", versionHandler::createDraft)
            PUT("/{id}/variants/{variantId}/draft", versionHandler::updateDraft)

            // Version lifecycle (delegated to VersionRouteHandler)
            POST("/{id}/variants/{variantId}/versions/{versionId}/publish", versionHandler::publishVersion)
            POST("/{id}/variants/{variantId}/versions/{versionId}/archive", versionHandler::archiveVersion)
        }
    }
}
