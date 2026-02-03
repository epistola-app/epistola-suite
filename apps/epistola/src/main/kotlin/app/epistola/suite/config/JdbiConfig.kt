package app.epistola.suite.config

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.jackson3.Jackson3Config
import org.jdbi.v3.jackson3.Jackson3Plugin
import org.jdbi.v3.postgres.PostgresPlugin
import org.jdbi.v3.spring.SpringConnectionFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
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

            // Register TenantId column mapper for reading from result sets
            registerColumnMapper(TenantId::class.java, TenantIdColumnMapper())

            // Register ThemeId column mapper for reading from result sets
            registerColumnMapper(ThemeId::class.java, ThemeIdColumnMapper())

            // Register TemplateId column mapper for reading from result sets
            registerColumnMapper(TemplateId::class.java, TemplateIdColumnMapper())

            // Register VariantId column mapper for reading from result sets
            registerColumnMapper(VariantId::class.java, VariantIdColumnMapper())
        }
}
