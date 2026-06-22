package app.epistola.suite.tenantbackup.schema

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Fast unit cover for the migration-header parsing that drives restore compatibility — the version
 * extraction and the `backup-restore-compatibility` directive, including the loud-fail on a malformed
 * header (so a typo can't silently default a migration to "incompatible" and block restores).
 */
class RestoreCompatibilityHeaderTest {

    @Test
    fun `version is taken from the migration filename`() {
        assertThat(RestoreCompatibility.versionOf("V20260622102813__audit_audit_log.sql")).isEqualTo("20260622102813")
    }

    @Test
    fun `non-versioned migrations have no version`() {
        assertThat(RestoreCompatibility.versionOf("R__refresh_views.sql")).isNull()
        assertThat(RestoreCompatibility.versionOf("not_a_migration.sql")).isNull()
    }

    @Test
    fun `a valid header is parsed, including past preceding comments and blank lines`() {
        val sql =
            """
            -- Some preamble describing the migration.
            --
            -- backup-restore-compatibility: backward=true forward=false
            -- reason: additive
            CREATE TABLE t (id int);
            """.trimIndent()
        val flags = RestoreCompatibility.parseCompatibilityHeader(sql)
        assertThat(flags).isNotNull
        assertThat(flags!!.backward).isTrue()
        assertThat(flags.forward).isFalse()
    }

    @Test
    fun `no header yields null (default deny)`() {
        assertThat(RestoreCompatibility.parseCompatibilityHeader("-- just a comment\nCREATE TABLE t (id int);")).isNull()
    }

    @Test
    fun `the directive is only read from the leading comment block, not the SQL body`() {
        val sql = "CREATE TABLE t (id int); -- backup-restore-compatibility: backward=true forward=true"
        assertThat(RestoreCompatibility.parseCompatibilityHeader(sql)).isNull()
    }

    @Test
    fun `a present-but-malformed header fails loudly`() {
        // Spaces around '=' — a realistic typo that must not be silently treated as "no header".
        val sql = "-- backup-restore-compatibility: backward = true forward = true\nCREATE TABLE t (id int);"
        assertThatThrownBy { RestoreCompatibility.parseCompatibilityHeader(sql) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("backup-restore-compatibility")
    }

    @Test
    fun `a header missing a direction fails loudly`() {
        val sql = "-- backup-restore-compatibility: backward=true\nCREATE TABLE t (id int);"
        assertThatThrownBy { RestoreCompatibility.parseCompatibilityHeader(sql) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
