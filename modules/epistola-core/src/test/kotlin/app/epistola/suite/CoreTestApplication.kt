package app.epistola.suite

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Test application configuration for epistola-core module tests.
 *
 * This is a minimal Spring Boot application configuration that enables
 * component scanning and autoconfiguration for the core module's test suite.
 */
@SpringBootApplication
class CoreTestApplication {
    @Bean
    fun objectMapper() = jsonMapper {
        addModule(kotlinModule())
    }
}
