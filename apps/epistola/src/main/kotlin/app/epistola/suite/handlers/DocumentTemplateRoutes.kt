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
    private val deploymentMatrixHandler: DeploymentMatrixHandler,
    private val versionComparisonHandler: VersionComparisonHandler,
) {
    @Bean
    fun templateRoutes(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/templates".nest {
            // List and create (across all catalogs)
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)

            // Detail and actions (catalog-scoped: /templates/{catalogId}/{id})
            GET("/{catalogId}/{id}", handler::detail)
            POST("/{catalogId}/{id}/delete", handler::delete)
            PATCH("/{catalogId}/{id}", handler::update)
            PATCH("/{catalogId}/{id}/theme", handler::updateTheme)
            GET("/{catalogId}/{id}/api", handler::get)
            POST("/{catalogId}/{id}/validate-schema", handler::validateSchema)

            // Data example routes
            PATCH("/{catalogId}/{id}/data-examples/{exampleId}", handler::updateDataExample)
            DELETE("/{catalogId}/{id}/data-examples/{exampleId}", handler::deleteDataExample)

            // Deployment matrix routes
            GET("/{catalogId}/{id}/deployments", deploymentMatrixHandler::deploymentMatrix)
            POST("/{catalogId}/{id}/deployments", deploymentMatrixHandler::updateDeployment)

            // Variant routes
            POST("/{catalogId}/{id}/variants", variantHandler::createVariant)
            GET("/{catalogId}/{id}/variants/{variantId}/edit", variantHandler::editVariantForm)
            PATCH("/{catalogId}/{id}/variants/{variantId}", variantHandler::updateVariant)
            POST("/{catalogId}/{id}/variants/{variantId}/delete", variantHandler::deleteVariant)
            POST("/{catalogId}/{id}/variants/{variantId}/set-default", variantHandler::setDefaultVariant)

            // Version comparison
            GET("/{catalogId}/{id}/variants/{variantId}/compare", versionComparisonHandler::compareDialog)

            // Variant version history
            GET("/{catalogId}/{id}/variants/{variantId}/versions", versionHandler::listVersions)

            // Editor route
            GET("/{catalogId}/{id}/variants/{variantId}/editor", handler::editor)

            // PDF preview
            POST("/{catalogId}/{id}/variants/{variantId}/preview", previewHandler::preview)

            // Draft creation and updates
            POST("/{catalogId}/{id}/variants/{variantId}/draft", versionHandler::createDraft)
            PUT("/{catalogId}/{id}/variants/{variantId}/draft", versionHandler::updateDraft)

            // Version lifecycle
            POST("/{catalogId}/{id}/variants/{variantId}/versions/{versionId}/archive", versionHandler::archiveVersion)
        }
    }
}
