package app.epistola.suite.config

import app.epistola.suite.common.ids.EntityId
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
 * Generic JDBI ColumnMapper for integer-based ID types.
 * Maps INTEGER database columns to any EntityId<T, Int> value class using a factory function.
 *
 * @param T The concrete ID type (e.g., VersionId)
 * @param factory Function that constructs T from an Int value
 */
class IntIdColumnMapper<T : EntityId<T, Int>>(
    private val factory: (Int) -> T,
) : ColumnMapper<T> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): T? {
        val value = r.getInt(columnNumber)
        return if (r.wasNull()) null else factory(value)
    }
}
