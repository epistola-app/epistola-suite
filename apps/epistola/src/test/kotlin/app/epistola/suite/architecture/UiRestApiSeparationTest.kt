package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Enforces architectural separation between UI handlers and REST API endpoints.
 *
 * UI code (Thymeleaf templates, static JavaScript) must NEVER call REST API endpoints.
 * REST API endpoints for external system integration use /api/v1 or /v1 path prefix.
 * UI needs should be handled by dedicated UI handler endpoints without /api or /v1 prefix.
 */
class UiRestApiSeparationTest {

    @Test
    fun `UI templates must not call REST API endpoints`() {
        val templateDir = Paths.get("src/main/resources/templates")
        val staticDir = Paths.get("src/main/resources/static")
        val violations = mutableListOf<String>()

        // Check Thymeleaf templates
        if (Files.exists(templateDir)) {
            Files.walk(templateDir)
                .filter { it.toString().endsWith(".html") }
                .forEach { path ->
                    val content = Files.readString(path)
                    val relativePath = templateDir.relativize(path)

                    // Check for /api/v1 or /v1/tenants patterns
                    if (content.contains(Regex("""['"/](api/)?v1/"""))) {
                        violations.add("Template $relativePath contains REST API call (pattern: /api/v1 or /v1)")
                    }

                    // Check for REST API content-type
                    if (content.contains("application/vnd.epistola.v1+json")) {
                        violations.add("Template $relativePath uses REST API content-type")
                    }
                }
        }

        // Check static JavaScript files
        if (Files.exists(staticDir)) {
            Files.walk(staticDir)
                .filter { it.toString().endsWith(".js") }
                .forEach { path ->
                    val content = Files.readString(path)
                    val relativePath = staticDir.relativize(path)

                    if (content.contains(Regex("""['"/](api/)?v1/"""))) {
                        violations.add("JavaScript $relativePath contains REST API call")
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Found REST API calls in UI code:\n${violations.joinToString("\n")}",
        )
    }
}
