package app.epistola.suite.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@Profile("local")
class EditorDevConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // In local profile, serve editor files from filesystem for live updates
        // This takes precedence over the JAR-bundled version
        registry.addResourceHandler("/editor/**")
            .addResourceLocations("file:modules/editor/dist/")
            .setCacheControl(CacheControl.noCache())
    }
}
