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
    private val settingsTabHandler: SettingsTabHandler,
    private val dataContractTabHandler: DataContractTabHandler,
    private val versionComparisonHandler: VersionComparisonHandler,
    private val contractVersionHandler: ContractVersionHandler,
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
            GET("/{catalogId}/{id}/deployments", deploymentMatrixHandler::deploymentMatrix)
            GET("/{catalogId}/{id}/data-contract", dataContractTabHandler::dataContract)
            GET("/{catalogId}/{id}/settings", settingsTabHandler::settings)

            // Template actions
            POST("/{catalogId}/{id}/delete", handler::delete)
            PATCH("/{catalogId}/{id}", handler::update)
            PATCH("/{catalogId}/{id}/theme", handler::updateTheme)
            GET("/{catalogId}/{id}/api", handler::get)
            POST("/{catalogId}/{id}/validate-schema", handler::validateSchema)

            // Contract version routes
            POST("/{catalogId}/{id}/contract/draft", contractVersionHandler::createDraft)
            PATCH("/{catalogId}/{id}/contract/draft", contractVersionHandler::updateDraft)
            POST("/{catalogId}/{id}/contract/publish", contractVersionHandler::publish)
            GET("/{catalogId}/{id}/contract/versions", contractVersionHandler::listVersions)

            // Data example routes
            PATCH("/{catalogId}/{id}/data-examples/{exampleId}", handler::updateDataExample)
            DELETE("/{catalogId}/{id}/data-examples/{exampleId}", handler::deleteDataExample)

            // Deployment matrix actions (POST only — GET is handled by deploymentMatrixHandler above)
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
