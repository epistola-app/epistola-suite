// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.context.annotation.Configuration
import java.sql.ResultSet

@Configuration
class FontJdbiConfig(private val jdbi: Jdbi) {

    @PostConstruct
    fun registerFontMappers() {
        // FontKind persists its lowercase wire form, not the enum constant
        // name, so map via `fromWire`. `weight` / `italic` / `is_variable`
        // are plain scalar columns mapped by the Kotlin reflection plugin.
        jdbi.registerColumnMapper(
            FontKind::class.java,
            ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                val value = r.getString(columnNumber)
                if (r.wasNull()) null else FontKind.fromWire(value)
            },
        )
        jdbi.registerColumnMapper(
            FontVariantSource::class.java,
            ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                val value = r.getString(columnNumber)
                if (r.wasNull()) null else FontVariantSource.valueOf(value)
            },
        )
    }
}
