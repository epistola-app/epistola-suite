// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.api.locations.LocationParser
import org.junit.jupiter.api.Test

class RollbackCompatibilityTest {

    @Test
    fun `reads true and false from the leading comment block`() {
        assertThat(RollbackCompatibility.parseHeader("-- rollback-compatible: true\nCREATE TABLE t (id INT);")).isTrue()
        assertThat(RollbackCompatibility.parseHeader("-- rollback-compatible: false\nCREATE TABLE t (id INT);")).isFalse()
    }

    @Test
    fun `finds the directive among other header lines`() {
        val sql = """
            -- backup-restore-compatibility: backward=true forward=true
            -- reason: Adds a table.
            -- rollback-compatible: true
            -- reason: Adds a table older code never reads.
            -- SPDX-FileCopyrightText: Epistola Nederland B.V.

            CREATE TABLE t (id INT);
        """.trimIndent()
        assertThat(RollbackCompatibility.parseHeader(sql)).isTrue()
    }

    @Test
    fun `a file without the directive declares nothing`() {
        assertThat(RollbackCompatibility.parseHeader("-- reason: none\nCREATE TABLE t (id INT);")).isNull()
    }

    @Test
    fun `a directive after the first statement is not a header`() {
        val sql = "CREATE TABLE t (id INT);\n-- rollback-compatible: true\n"
        assertThat(RollbackCompatibility.parseHeader(sql)).isNull()
    }

    @Test
    fun `prose that mentions the directive mid-line is not a directive`() {
        val sql = "-- Each migration declares `-- rollback-compatible: true|false`.\nCREATE TABLE t (id INT);"
        assertThat(RollbackCompatibility.parseHeader(sql)).isNull()
    }

    @Test
    fun `prose wrapped onto a line starting with the word is not a directive`() {
        val sql = "-- rollback-compatible: true\n-- recorded here as\n-- rollback-compatible, or the build refuses.\nSELECT 1;"
        assertThat(RollbackCompatibility.parseHeader(sql)).isTrue()
    }

    @Test
    fun `a malformed directive fails instead of counting as not compatible`() {
        assertThatThrownBy { RollbackCompatibility.parseHeader("-- rollback-compatible: yes\nSELECT 1;") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expected `-- rollback-compatible: <true|false>`")
        assertThatThrownBy { RollbackCompatibility.parseHeader("-- rollback-compatible = true\nSELECT 1;") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a directive declared twice fails`() {
        assertThatThrownBy {
            RollbackCompatibility.parseHeader("-- rollback-compatible: true\n-- rollback-compatible: false\nSELECT 1;")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2 times")
    }

    @Test
    fun `the version comes from the filename`() {
        assertThat(RollbackCompatibility.versionOf("V20260622102813__core_audit_log.sql")).isEqualTo("20260622102813")
        assertThat(RollbackCompatibility.versionOf("R__repeatable.sql")).isNull()
    }

    @Test
    fun `a build that ships every applied migration may start whatever they declare`() {
        val verdict = RollbackCompatibility.judge(
            applied = listOf("1", "2"),
            shipped = setOf("1", "2", "3"),
            recorded = mapOf("2" to RecordedDeclaration(rollbackCompatible = false, recordedBy = "1.6.0")),
        )
        assertThat(verdict).isEqualTo(RollbackVerdict.Shipped)
    }

    @Test
    fun `a build behind compatible migrations may start`() {
        val verdict = RollbackCompatibility.judge(
            applied = listOf("1", "2", "3"),
            shipped = setOf("1"),
            recorded = mapOf(
                "2" to RecordedDeclaration(rollbackCompatible = true, recordedBy = "1.6.0"),
                "3" to RecordedDeclaration(rollbackCompatible = true, recordedBy = "1.7.0"),
            ),
        )
        assertThat(verdict).isEqualTo(RollbackVerdict.AheadCompatible(listOf("2", "3")))
    }

    @Test
    fun `a build behind a breaking migration may not start`() {
        val breaking = RecordedDeclaration(rollbackCompatible = false, recordedBy = "1.7.0")
        val verdict = RollbackCompatibility.judge(
            applied = listOf("1", "2", "3"),
            shipped = setOf("1"),
            recorded = mapOf(
                "2" to RecordedDeclaration(rollbackCompatible = true, recordedBy = "1.6.0"),
                "3" to breaking,
            ),
        )
        assertThat(verdict).isEqualTo(
            RollbackVerdict.AheadIncompatible(listOf("2", "3"), listOf(BlockingMigration("3", breaking))),
        )
    }

    @Test
    fun `an unshipped migration with nothing recorded counts as breaking`() {
        val verdict = RollbackCompatibility.judge(applied = listOf("1", "2"), shipped = setOf("1"), recorded = emptyMap())
        assertThat(verdict).isEqualTo(
            RollbackVerdict.AheadIncompatible(listOf("2"), listOf(BlockingMigration("2", null))),
        )
    }

    @Test
    fun `scanning a Flyway location maps each migration to its declaration`() {
        val classLoader = javaClass.classLoader
        assertThat(RollbackCompatibility.scan(arrayOf(LocationParser.parseLocation("classpath:rollback-probe/breaking")), classLoader))
            .isEqualTo(mapOf("29990101000000" to false))
        assertThat(RollbackCompatibility.scan(arrayOf(LocationParser.parseLocation("classpath:rollback-probe/compatible")), classLoader))
            .isEqualTo(mapOf("29990101000000" to true))
        assertThat(RollbackCompatibility.scan(arrayOf(LocationParser.parseLocation("classpath:rollback-probe/undeclared")), classLoader))
            .isEqualTo(mapOf("29990101000000" to null))
    }
}
