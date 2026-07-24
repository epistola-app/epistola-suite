// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.suite.attributes.codelists.model.CodeListSource
import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import org.springframework.context.annotation.Configuration
import java.sql.ResultSet

@Configuration
class CodeListJdbiConfig(private val jdbi: Jdbi) {

    @PostConstruct
    fun registerCodeListMappers() {
        jdbi.registerColumnMapper(
            CodeListSource::class.java,
            ColumnMapper { r: ResultSet, columnNumber: Int, _: StatementContext ->
                val value = r.getString(columnNumber)
                if (r.wasNull()) null else CodeListSource.valueOf(value)
            },
        )
    }
}
