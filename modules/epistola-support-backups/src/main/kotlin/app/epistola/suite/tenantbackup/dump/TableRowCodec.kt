// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenantbackup.dump

import app.epistola.suite.tenantbackup.schema.ColumnMeta
import app.epistola.suite.tenantbackup.schema.TableSpec
import org.jdbi.v3.core.statement.Update
import org.springframework.stereotype.Component

/**
 * Translates between live Postgres rows and a faithful, type-stable JSON representation, and builds
 * the dynamic SQL the dump (`SELECT`) and restore (`INSERT … ON CONFLICT`) need — driven entirely
 * by per-column type metadata ([ColumnMeta.udtName]) so it adapts to whatever columns a table has.
 *
 * The strategy keeps the JSON simple and byte-stable: "complex" columns (jsonb, uuid, timestamps,
 * arrays, numeric, bytea) are projected to a canonical text form on dump and bound back with the
 * matching cast on restore. Simple columns (int/bool/text and text-based domains) pass through
 * natively. Credential columns (`enc:v1:` envelopes) are plain text and ride through verbatim — the
 * dump deliberately uses raw column reads, so no decryption happens.
 */
@Component
class TableRowCodec {
    /** A `SELECT` that returns every column of [spec] in canonical text-stable form, for one tenant. */
    fun buildSelect(
        spec: TableSpec,
        whereColumn: String,
    ): String {
        val projection = spec.columns.joinToString(", ") { selectExpression(it) }
        val orderBy = spec.primaryKey.joinToString(", ") { q(it) }
        return "SELECT $projection FROM ${q(spec.table)} WHERE ${q(whereColumn)} = :tk ORDER BY $orderBy"
    }

    /** A `SELECT` of just the primary-key columns for one tenant — used to compute the delete set. */
    fun buildSelectPrimaryKeys(
        spec: TableSpec,
        whereColumn: String,
    ): String {
        val cols = spec.primaryKey.joinToString(", ") { "${q(it)}::text AS ${q(it)}" }
        return "SELECT $cols FROM ${q(spec.table)} WHERE ${q(whereColumn)} = :tk"
    }

    /**
     * An `INSERT … ON CONFLICT (pk) DO UPDATE` that restores one row verbatim. Existing PKs are
     * updated, never deleted, so cascade children (e.g. documents pinned to a `template_versions`
     * row) are untouched. Falls back to `DO NOTHING` for all-primary-key tables.
     */
    fun buildUpsert(spec: TableSpec): String {
        val cols = spec.columns
        val columnList = cols.joinToString(", ") { q(it.name) }
        val valueList = cols.joinToString(", ") { valueExpression(it) }
        val nonPk = cols.filterNot { spec.primaryKey.contains(it.name) }
        val conflict = spec.primaryKey.joinToString(", ") { q(it) }
        val action =
            if (nonPk.isEmpty()) {
                "DO NOTHING"
            } else {
                "DO UPDATE SET " + nonPk.joinToString(", ") { "${q(it.name)} = EXCLUDED.${q(it.name)}" }
            }
        return "INSERT INTO ${q(spec.table)} ($columnList) VALUES ($valueList) ON CONFLICT ($conflict) $action"
    }

    /**
     * A `DELETE` of one row by tenant and primary key, used to remove live rows that are absent from
     * the backup. PK params are bound by their text form (cast back to int/uuid as needed) under the
     * bind-name convention `k_<column>`.
     */
    fun buildDeleteByPrimaryKey(spec: TableSpec): String {
        val conditions =
            spec.primaryKey.joinToString(" AND ") { name ->
                val col = spec.columns.first { it.name == name }
                "${q(name)} = ${primaryKeyBind(col)}"
            }
        return "DELETE FROM ${q(spec.table)} WHERE ${q("tenant_key")} = :tk AND $conditions"
    }

    /** The placeholder (with cast) for a primary-key column in a delete predicate; values bind as text. */
    private fun primaryKeyBind(col: ColumnMeta): String {
        val p = ":k_${col.name}"
        return when (col.udtName) {
            "int2", "int4", "int8" -> "$p::${col.udtName}"
            "uuid" -> "$p::uuid"
            else -> p
        }
    }

    /** Binds every column of [row] to [update] using the bind-name convention `p_<column>`. */
    fun bindRow(
        update: Update,
        spec: TableSpec,
        row: Map<String, Any?>,
    ): Update {
        spec.columns.forEach { col ->
            update.bind(bindName(col.name), row[col.name])
        }
        return update
    }

    /** The canonical-text projection for a column on dump. */
    private fun selectExpression(col: ColumnMeta): String = when {
        isBytea(col) -> "encode(${q(col.name)}, 'base64') AS ${q(col.name)}"
        isArray(col) || isTextCast(col) -> "${q(col.name)}::text AS ${q(col.name)}"
        else -> q(col.name)
    }

    /** The value placeholder (with cast) for a column on restore. */
    private fun valueExpression(col: ColumnMeta): String {
        val p = ":" + bindName(col.name)
        return when {
            isBytea(col) -> "decode($p, 'base64')"
            isArray(col) -> "$p::${safeType(elementType(col))}[]"
            isTextCast(col) -> "$p::${safeType(col.udtName)}"
            else -> p
        }
    }

    private fun isBytea(col: ColumnMeta): Boolean = col.udtName == "bytea"

    private fun isArray(col: ColumnMeta): Boolean = col.dataType == "ARRAY" || col.udtName.startsWith("_")

    private fun isTextCast(col: ColumnMeta): Boolean = TEXT_CAST_UDTS.contains(col.udtName)

    /** The element type of an array column, derived from its `_`-prefixed udt name (`_int4` → `int4`). */
    private fun elementType(col: ColumnMeta): String = col.udtName.removePrefix("_")

    private fun bindName(column: String): String = "p_$column"

    /** Quotes a SQL identifier, doubling any embedded `"` per the SQL standard (defense-in-depth). */
    private fun q(identifier: String): String = "\"" + identifier.replace("\"", "\"\"") + "\""

    /**
     * Validates a Postgres type name before it is interpolated into a `::cast`. Type names always
     * match this shape; anything else (an injected schema) fails closed rather than reaching the SQL.
     */
    private fun safeType(udt: String): String {
        require(SAFE_TYPE_NAME.matches(udt)) { "Unsafe SQL type name in backup schema: '$udt'" }
        return udt
    }

    private companion object {
        /** Lowercase Postgres type-name shape (`int4`, `timestamptz`, `_int4` element → `int4`). */
        val SAFE_TYPE_NAME = Regex("^[a-z_][a-z0-9_]*$")

        /**
         * udt names projected to text on dump and bound back with `::<udt>` on restore. Spans the
         * complex scalar types whose default JSON/JDBC mapping is lossy or order-unstable.
         */
        val TEXT_CAST_UDTS =
            setOf(
                "jsonb",
                "json",
                "uuid",
                "timestamptz",
                "timestamp",
                "date",
                "time",
                "timetz",
                "numeric",
                "interval",
            )
    }
}
