package app.epistola.suite.config

import org.springframework.context.annotation.Configuration
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.standard.StandardDialect
import org.thymeleaf.standard.serializer.IStandardJavaScriptSerializer
import tools.jackson.databind.ObjectMapper
import java.io.Writer

/**
 * Configures Thymeleaf to use Spring's ObjectMapper for JavaScript serialization.
 *
 * By default, Thymeleaf creates its own internal ObjectMapper when serializing values
 * for JavaScript inlining (`th:inline="javascript"`). This bypasses Spring Boot's
 * auto-configured ObjectMapper, which means:
 * - Custom Jackson modules aren't registered
 * - Jackson 3's ObjectNode serialization doesn't work correctly
 * - Any Spring-configured serialization settings are ignored
 *
 * This configuration injects Spring's ObjectMapper into Thymeleaf's StandardDialect,
 * ensuring consistent JSON serialization throughout the application.
 *
 * @see <a href="https://github.com/thymeleaf/thymeleaf-spring/issues/311">Thymeleaf issue #311</a>
 */
@Configuration
class ThymeleafJacksonConfig(
    templateEngine: SpringTemplateEngine,
    objectMapper: ObjectMapper,
) {
    init {
        val serializer = SpringJavaScriptSerializer(objectMapper)
        templateEngine.dialects
            .filterIsInstance<StandardDialect>()
            .forEach { it.javaScriptSerializer = serializer }
    }
}

/**
 * JavaScript serializer that uses Spring's ObjectMapper for consistent JSON serialization.
 *
 * Handles proper escaping of characters that could break HTML script blocks.
 */
class SpringJavaScriptSerializer(
    private val objectMapper: ObjectMapper,
) : IStandardJavaScriptSerializer {
    override fun serializeValue(
        value: Any?,
        writer: Writer,
    ) {
        if (value == null) {
            writer.write("null")
            return
        }
        val json = objectMapper.writeValueAsString(value)
        // Escape characters that could break HTML script blocks
        val escaped =
            json
                .replace("</", "<\\/")
                .replace("<!--", "<\\!--")
        writer.write(escaped)
    }
}
