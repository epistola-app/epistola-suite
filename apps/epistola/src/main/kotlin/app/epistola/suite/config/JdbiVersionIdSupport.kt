package app.epistola.suite.config

import app.epistola.suite.common.ids.VersionId
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import java.sql.ResultSet
import java.sql.Types

/**
 * JDBI ArgumentFactory for VersionId.
 * Converts VersionId values to INTEGER when binding to SQL statements.
 */
class VersionIdArgumentFactory : AbstractArgumentFactory<VersionId>(Types.INTEGER) {
    override fun build(value: VersionId, config: ConfigRegistry): Argument = Argument { position, statement, _ -> statement.setInt(position, value.value) }
}

/**
 * JDBI ColumnMapper for VersionId.
 * Maps INTEGER database columns to VersionId value class.
 */
class VersionIdColumnMapper : ColumnMapper<VersionId> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): VersionId? {
        val value = r.getInt(columnNumber)
        return if (r.wasNull()) null else VersionId.of(value)
    }
}
