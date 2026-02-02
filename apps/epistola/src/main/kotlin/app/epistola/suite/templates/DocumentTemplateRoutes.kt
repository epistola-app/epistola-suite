package app.epistola.suite.templates

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class DocumentTemplateRoutes(private val handler: DocumentTemplateHandler) {
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

            // Variant routes
            POST("/{id}/variants", handler::createVariant)
            GET("/{id}/variants/{variantId}", handler::variantDetail)
            DELETE("/{id}/variants/{variantId}", handler::deleteVariant)
            POST("/{id}/variants/{variantId}/delete", handler::deleteVariant)

            // Editor route (with variant)
            GET("/{id}/variants/{variantId}/editor", handler::editor)

            // PDF preview (internal UI only)
            POST("/{id}/variants/{variantId}/preview", handler::preview)

            // Draft creation
            POST("/{id}/variants/{variantId}/draft", handler::createDraft)

            // Version lifecycle
            POST("/{id}/variants/{variantId}/versions/{versionId}/publish", handler::publishVersion)
            POST("/{id}/variants/{variantId}/versions/{versionId}/archive", handler::archiveVersion)
        }
    }
}
