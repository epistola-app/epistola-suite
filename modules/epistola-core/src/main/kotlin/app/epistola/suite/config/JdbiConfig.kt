// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.crypto.Secret
import app.epistola.suite.database.DatabasePressureMonitor
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.jackson3.Jackson3Config
import org.jdbi.v3.jackson3.Jackson3Plugin
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.spring.SpringConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

@Configuration
class JdbiConfig {
    @Bean
    fun jdbi(
        dataSource: DataSource,
        mapper: ObjectMapper,
        credentialCipher: CredentialCipher,
        databasePressureMonitor: DatabasePressureMonitor,
    ): Jdbi = Jdbi.create(SpringConnectionFactory(dataSource))
        .installPlugin(KotlinPlugin())
        .installPlugin(PostgresPlugin())
        .installPlugin(Jackson3Plugin())
        .apply {
            // Join Spring-managed transactions (the mediator's per-command transaction)
            // instead of committing them mid-flight from nested jdbi.inTransaction calls.
            setTransactionHandler(SpringAwareTransactionHandler())
            setSqlLogger(object : SqlLogger {
                override fun logBeforeExecution(context: StatementContext) {
                    context.define(DATABASE_START_NANOS, System.nanoTime())
                }

                override fun logAfterExecution(context: StatementContext) {
                    duration(context)?.let(databasePressureMonitor::recordSuccess)
                }

                override fun logException(context: StatementContext, ex: SQLException) {
                    databasePressureMonitor.recordFailure(duration(context) ?: Duration.ZERO, ex)
                }

                private fun duration(context: StatementContext): Duration? = (context.getAttribute(DATABASE_START_NANOS) as? Long)?.let { startedAt ->
                    Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0))
                }
            })
            // fix: use the spring boot mapper as this is preconfigured with kotlin support
            getConfig(Jackson3Config::class.java).mapper = mapper

            // Register SlugId argument factory for binding slug-based IDs to SQL statements
            registerArgument(SlugIdArgumentFactory())

            // Register column mappers for all slug-based ID types
            registerColumnMapper(TenantKey::class.java, SlugIdColumnMapper(TenantKey::of))
            registerColumnMapper(ThemeKey::class.java, SlugIdColumnMapper(ThemeKey::of))
            registerColumnMapper(TemplateKey::class.java, SlugIdColumnMapper(TemplateKey::of))
            registerColumnMapper(VariantKey::class.java, SlugIdColumnMapper(VariantKey::of))
            registerColumnMapper(EnvironmentKey::class.java, SlugIdColumnMapper(EnvironmentKey::of))
            registerColumnMapper(FeatureKey::class.java, SlugIdColumnMapper(FeatureKey::of))
            registerColumnMapper(CodeListKey::class.java, SlugIdColumnMapper(CodeListKey::of))

            // Register VersionId argument factory and column mapper for integer-based version IDs
            registerArgument(VersionIdArgumentFactory())
            registerColumnMapper(VersionKey::class.java, IntIdColumnMapper(VersionKey::of))

            // Register AssetMediaType column mapper (varchar mime type → enum)
            registerColumnMapper(
                AssetMediaType::class.java,
                ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                    val value = r.getString(columnNumber)
                    if (r.wasNull()) null else AssetMediaType.fromMimeType(value)
                },
            )

            // SQL arrays as a List, so a row model can hold an idiomatic list rather than an
            // Array (whose identity-based equals silently breaks data classes). Binding the other
            // way already works: JDBI turns a Kotlin Array into a SQL array.
            //
            // Registered on the erased List type because that is what Kotlin reflection hands JDBI
            // for a `List<T>` constructor parameter — the element type is not available here. The
            // elements are therefore passed through as the driver produced them rather than being
            // coerced to String: a `uuid[]` maps to UUIDs and an `int[]` to Ints, instead of
            // producing a list whose contents do not match its declared type.
            registerColumnMapper(
                List::class.java,
                ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                    val array = r.getArray(columnNumber)
                    if (r.wasNull() || array == null) null else (array.array as Array<*>).toList()
                },
            )

            // Transparent credential encryption-at-rest: Secret values are encrypted
            // on bind and decrypted on read by the credential cipher.
            registerArgument(SecretArgumentFactory(credentialCipher))
            registerColumnMapper(Secret::class.java, SecretColumnMapper(credentialCipher))
        }

    private companion object {
        const val DATABASE_START_NANOS = "epistola.database.start-nanos"
    }
}
