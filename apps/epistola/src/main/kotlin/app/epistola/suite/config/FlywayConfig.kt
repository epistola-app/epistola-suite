package app.epistola.suite.config

import org.flywaydb.core.api.exception.FlywayValidateException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Replaces Flyway's removed `cleanOnValidationError` property (dropped in Flyway 10+).
 *
 * When migrations fail validation (checksum mismatch, missing/renamed migrations) and
 * cleaning is allowed (`spring.flyway.clean-disabled=false`), the database is cleaned
 * and migrations re-run. In production (`clean-disabled=true`), validation failures
 * propagate as-is.
 */
@Configuration
class FlywayConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun flywayMigrationStrategy(
        @Value("\${spring.flyway.clean-disabled:true}") cleanDisabled: Boolean,
    ): FlywayMigrationStrategy = FlywayMigrationStrategy { flyway ->
        try {
            flyway.migrate()
        } catch (e: FlywayValidateException) {
            if (cleanDisabled) throw e
            logger.warn("Migration validation failed — cleaning database and re-migrating: {}", e.message)
            flyway.clean()
            flyway.migrate()
        }
    }
}
