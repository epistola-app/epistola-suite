package app.epistola.suite.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
@Profile("local")
class EditorDevConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // In local profile, serve editor files from filesystem for live updates (browser refresh)
        // headless-editor and vendor are served from their built dist/ folders (tsc --watch required)
        val projectRoot = findProjectRoot()

        registry.addResourceHandler("/editor/**")
            .addResourceLocations("file:$projectRoot/apps/epistola/src/main/resources/static/editor/")
            .setCacheControl(CacheControl.noCache())

        registry.addResourceHandler("/headless-editor/**")
            .addResourceLocations("file:$projectRoot/modules/headless-editor/dist/")
            .setCacheControl(CacheControl.noCache())

        registry.addResourceHandler("/vendor/**")
            .addResourceLocations("file:$projectRoot/modules/vendor/dist/")
            .setCacheControl(CacheControl.noCache())
    }

    private fun findProjectRoot(): String {
        // Try multiple strategies to find project root
        // Works with both Gradle bootRun (working dir = apps/epistola) and IntelliJ (working dir = project root)
        val candidates = listOf(
            Paths.get("../../").toAbsolutePath().normalize(),
            Paths.get(".").toAbsolutePath().normalize(),
        )

        return candidates
            .firstOrNull { it.resolve("settings.gradle.kts").toFile().exists() }
            ?.toString()
            ?: throw IllegalStateException("Could not find project root. Working directory: ${Paths.get(".").toAbsolutePath()}")
    }
}
