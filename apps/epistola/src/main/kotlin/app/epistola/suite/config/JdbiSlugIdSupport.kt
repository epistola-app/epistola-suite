package app.epistola.suite.config

import app.epistola.suite.common.ids.SlugId
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import java.sql.ResultSet
import java.sql.Types

/**
 * JDBI ArgumentFactory for all SlugId implementations.
 * Converts SlugId values to VARCHAR when binding to SQL statements.
 */
class SlugIdArgumentFactory : AbstractArgumentFactory<SlugId<*>>(Types.VARCHAR) {
    override fun build(value: SlugId<*>, config: ConfigRegistry): Argument = Argument { position, statement, _ -> statement.setString(position, value.value) }
}

/**
 * Generic JDBI ColumnMapper for SlugId types.
 * Maps VARCHAR database columns to any SlugId value class using a factory function.
 *
 * @param T The concrete SlugId type (e.g., TenantId, ThemeId)
 * @param factory Function that constructs T from a String value
 */
class SlugIdColumnMapper<T : SlugId<T>>(
    private val factory: (String) -> T,
) : ColumnMapper<T> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): T? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else factory(value)
    }
}
