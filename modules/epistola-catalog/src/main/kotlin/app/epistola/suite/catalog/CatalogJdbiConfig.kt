package app.epistola.suite.catalog

import app.epistola.suite.config.SlugIdColumnMapper
import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.context.annotation.Configuration
import java.sql.ResultSet

@Configuration
class CatalogJdbiConfig(private val jdbi: Jdbi) {

    @PostConstruct
    fun registerCatalogMappers() {
        jdbi.registerColumnMapper(CatalogKey::class.java, SlugIdColumnMapper(CatalogKey::of))
        jdbi.registerColumnMapper(
            AuthType::class.java,
            ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                val value = r.getString(columnNumber)
                if (r.wasNull()) null else AuthType.valueOf(value)
            },
        )
        jdbi.registerColumnMapper(
            CatalogType::class.java,
            ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                val value = r.getString(columnNumber)
                if (r.wasNull()) null else CatalogType.valueOf(value)
            },
        )
    }
}
