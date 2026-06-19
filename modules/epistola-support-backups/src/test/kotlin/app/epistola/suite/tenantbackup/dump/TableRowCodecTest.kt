package app.epistola.suite.tenantbackup.dump

import app.epistola.suite.tenantbackup.schema.ColumnMeta
import app.epistola.suite.tenantbackup.schema.TableSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The dynamic SQL [TableRowCodec] builds is driven by `information_schema` metadata. Identifiers are
 * quote-escaped and type names are validated before interpolation, so restore safety doesn't rest
 * solely on the column-set check ordering — a defense-in-depth backstop.
 */
class TableRowCodecTest {
    private val codec = TableRowCodec()

    @Test
    fun `quotes identifiers and doubles an embedded double-quote`() {
        val spec = TableSpec(
            table = "weird\"name",
            columns = listOf(ColumnMeta("id", "integer", "int4", nullable = false)),
            primaryKey = listOf("id"),
        )

        assertThat(codec.buildUpsert(spec)).contains("INSERT INTO \"weird\"\"name\"")
    }

    @Test
    fun `builds safe casts for normal complex and array types`() {
        val spec = TableSpec(
            table = "t",
            columns = listOf(
                ColumnMeta("id", "integer", "int4", nullable = false),
                ColumnMeta("data", "jsonb", "jsonb", nullable = true),
                ColumnMeta("tags", "ARRAY", "_int4", nullable = true),
            ),
            primaryKey = listOf("id"),
        )

        val sql = codec.buildUpsert(spec)
        assertThat(sql).contains("::jsonb").contains("::int4[]")
    }

    @Test
    fun `rejects an unsafe type name in a cast`() {
        val spec = TableSpec(
            table = "t",
            columns = listOf(
                ColumnMeta("id", "integer", "int4", nullable = false),
                ColumnMeta("tags", "ARRAY", "_evil\"; DROP TABLE x;--", nullable = true),
            ),
            primaryKey = listOf("id"),
        )

        assertThatThrownBy { codec.buildUpsert(spec) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsafe SQL type name")
    }
}
