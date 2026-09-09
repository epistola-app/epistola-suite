// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.common.ids.UuidKey
import org.jdbi.v3.core.argument.AbstractArgumentFactory
import org.jdbi.v3.core.argument.Argument
import org.jdbi.v3.core.config.ConfigRegistry
import org.jdbi.v3.core.mapper.ColumnMapper
import org.jdbi.v3.core.statement.StatementContext
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

/**
 * Binds any [UuidKey] to a `uuid` column, so a typed key can be passed to `bind` whole instead of
 * being unwrapped to its [UuidKey.value] at every call site. `Types.OTHER` is what pgjdbc uses for
 * `uuid`; the driver maps [UUID] natively from there.
 */
class UuidIdArgumentFactory : AbstractArgumentFactory<UuidKey<*>>(Types.OTHER) {
    override fun build(value: UuidKey<*>, config: ConfigRegistry): Argument = Argument { position, statement, _ -> statement.setObject(position, value.value) }
}

/**
 * Maps a `uuid` column to a [UuidKey] value class.
 *
 * @param T the concrete key type (e.g. [app.epistola.suite.common.ids.ResourceIdentity])
 * @param factory constructs T from the column's [UUID]
 */
class UuidIdColumnMapper<T : UuidKey<T>>(
    private val factory: (UUID) -> T,
) : ColumnMapper<T> {
    override fun map(r: ResultSet, columnNumber: Int, ctx: StatementContext): T? {
        val value = r.getObject(columnNumber, UUID::class.java)
        return if (r.wasNull() || value == null) null else factory(value)
    }
}
