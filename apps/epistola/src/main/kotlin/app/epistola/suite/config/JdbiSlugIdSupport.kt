package app.epistola.suite.config

import app.epistola.suite.common.ids.SlugId
import app.epistola.suite.common.ids.TenantId
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
 * JDBI ColumnMapper for TenantId.
 * Maps VARCHAR database columns to TenantId value class.
 */
class TenantIdColumnMapper : ColumnMapper<TenantId> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): TenantId? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else TenantId.of(value)
    }
}
