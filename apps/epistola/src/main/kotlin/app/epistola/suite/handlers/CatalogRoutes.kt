package app.epistola.suite.handlers

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class CatalogRoutes(private val handler: CatalogHandler) {
    @Bean
    fun catalogRouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/catalogs".nest {
            GET("", handler::list)
            GET("/new", handler::newForm)
            GET("/register", handler::registerForm)
            POST("/register", handler::register)
            POST("/create", handler::createCatalog)
            POST("/import", handler::importZip)
            POST("/{catalogId}/delete", handler::unregister)
            GET("/{catalogId}/release", handler::releaseDialog)
            POST("/{catalogId}/release", handler::release)
            GET("/{catalogId}/browse", handler::browse)
            GET("/{catalogId}/usages", handler::resourceUsages)
            GET("/{catalogId}/export-check", handler::exportCheck)
            GET("/{catalogId}/export", handler::export)
            GET("/{catalogId}/install-preview", handler::installPreview)
            POST("/{catalogId}/install", handler::install)
            GET("/{catalogId}/upgrade-check", handler::upgradeCheck)
            GET("/{catalogId}/upgrade-preview", handler::upgradePreview)
            POST("/{catalogId}/upgrade", handler::upgrade)
        }
    }
}
