package app.epistola.suite.config

import app.epistola.suite.common.ids.SlugId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
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

/**
 * JDBI ColumnMapper for ThemeId.
 * Maps VARCHAR database columns to ThemeId value class.
 */
class ThemeIdColumnMapper : ColumnMapper<ThemeId> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): ThemeId? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else ThemeId.of(value)
    }
}

/**
 * JDBI ColumnMapper for TemplateId.
 * Maps VARCHAR database columns to TemplateId value class.
 */
class TemplateIdColumnMapper : ColumnMapper<TemplateId> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): TemplateId? {
        val value = r.getString(columnNumber)
        return if (r.wasNull()) null else TemplateId.of(value)
    }
}
