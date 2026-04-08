package app.epistola.suite.config

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import org.jdbi.v3.jackson3.Jackson3Config
import org.jdbi.v3.jackson3.Jackson3Plugin
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.spring.SpringConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import javax.sql.DataSource

@Configuration
class JdbiConfig {
    @Bean
    fun jdbi(
        dataSource: DataSource,
        mapper: ObjectMapper,
    ): Jdbi = Jdbi.create(SpringConnectionFactory(dataSource))
        .installPlugin(KotlinPlugin())
        .installPlugin(PostgresPlugin())
        .installPlugin(Jackson3Plugin())
        .apply {
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
        }
}
