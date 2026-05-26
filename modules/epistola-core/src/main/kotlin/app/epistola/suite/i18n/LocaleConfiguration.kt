package app.epistola.suite.i18n

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Registers [LocaleProperties] as a Spring bean. The production app also has
 * `@ConfigurationPropertiesScan`, which would cover this — but the module-level
 * `TestApplication` used by `epistola-core` integration tests does not, so the
 * explicit `@EnableConfigurationProperties` here is what keeps those tests
 * green. Mirrors the prevailing convention in this repo (`StorageConfiguration`,
 * `InstallationConfiguration`, …).
 */
@Configuration
@EnableConfigurationProperties(LocaleProperties::class)
class LocaleConfiguration
