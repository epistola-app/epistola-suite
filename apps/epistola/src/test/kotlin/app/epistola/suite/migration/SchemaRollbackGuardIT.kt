// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * A build started against a database that a newer build has migrated: it must refuse when the newer
 * build applied a migration that is not rollback-compatible, and start when every such migration is.
 *
 * "The newer build" is the production migration context with a probe location added, so it ships one
 * migration more than this build does. "The older build" is the same context without it. Each test
 * migrates its own throwaway database in the shared container, because the probe is applied for real
 * and would make every later context in the shared database look rolled back.
 */
@Tag("integration")
class SchemaRollbackGuardIT {

    private val postgres = TestRuntimeLifecycle.postgres()

    @Test
    fun `a build older than a breaking migration refuses to start in every mode`() {
        val database = freshDatabase("rollback_guard_breaking")
        assertThat(migrate(database, "classpath:rollback-probe/breaking")).isZero()

        for (mode in listOf("validate", "embedded")) {
            assertThatThrownBy { boot(database, mode) }
                .describedAs("older build in $mode mode")
                .hasStackTraceContaining("SchemaAheadException")
                .hasStackTraceContaining("29990101000000 (not rollback-compatible")
        }
        // The dedicated migration step is the deploy gate: it must exit non-zero, not migrate.
        assertThat(migrate(database)).isEqualTo(1)
    }

    @Test
    fun `a migration without a declaration blocks older builds`() {
        val database = freshDatabase("rollback_guard_undeclared")
        assertThat(migrate(database, "classpath:rollback-probe/undeclared")).isZero()

        assertThat(recorded(database)[PROBE]).isFalse()
        assertThatThrownBy { boot(database, "validate") }
            .hasStackTraceContaining("29990101000000 (not rollback-compatible")
    }

    @Test
    fun `a build older than only compatible migrations starts in every mode`() {
        val database = freshDatabase("rollback_guard_compatible")
        assertThat(migrate(database, "classpath:rollback-probe/compatible")).isZero()

        boot(database, "validate")
        boot(database, "embedded")
        assertThat(migrate(database)).isZero()
    }

    @Test
    fun `the migration step records every applied migration's declaration`() {
        val database = freshDatabase("rollback_guard_recorded")
        assertThat(migrate(database, "classpath:rollback-probe/breaking")).isZero()

        val recorded = recorded(database)
        assertThat(recorded.keys).isEqualTo(appliedVersions(database))
        assertThat(recorded[PROBE]).isFalse()
        assertThat(recorded[RollbackCompatibilityDeclarationTest.FIRST_DECLARING]).isTrue()
        // Before the header existed: counted as not compatible, which every build with the check ships anyway.
        assertThat(recorded[LAST_UNDECLARED]).isFalse()
    }

    @Test
    fun `an applied migration whose declaration was never recorded blocks older builds`() {
        val database = freshDatabase("rollback_guard_unrecorded")
        assertThat(migrate(database, "classpath:rollback-probe/compatible")).isZero()
        // Raw SQL on purpose: an infrastructure table with no command. Stands in for a migration
        // step that was killed between applying the migration and recording it.
        connect(database).use { it.createStatement().execute("DELETE FROM schema_rollback_compatibility WHERE version = '$PROBE'") }

        assertThatThrownBy { boot(database, "validate") }
            .hasStackTraceContaining("29990101000000 (no declaration recorded)")
    }

    @Test
    fun `a migration step that fails partway records what it applied, so the previous build can return`() {
        val database = freshDatabase("rollback_guard_partial")
        assertThat(migrate(database, "classpath:rollback-probe/compatible", "classpath:rollback-probe/failing"))
            .isEqualTo(1)

        assertThat(appliedVersions(database)).contains(PROBE).doesNotContain(FAILING_PROBE)
        assertThat(recorded(database)[PROBE]).isTrue()
        boot(database, "validate")
    }

    /** Runs the production migration step; [extraLocations] make it a newer build. Returns the exit code. */
    private fun migrate(
        database: String,
        vararg extraLocations: String,
    ): Int = MigrationLauncher.runMigration(datasourceArgs(database) + locationsArg(extraLocations))

    /** Boots this build's migration context in [mode] and closes it again; throws when startup fails. */
    private fun boot(
        database: String,
        mode: String,
    ) {
        MigrationLauncher.migrationApplication()
            .run(*datasourceArgs(database), "--epistola.migration.mode=$mode", "--spring.flyway.clean-disabled=true")
            .close()
    }

    private fun datasourceArgs(database: String) = arrayOf(
        "--spring.datasource.url=$database",
        "--spring.datasource.username=${postgres.username}",
        "--spring.datasource.password=${postgres.password}",
    )

    private fun locationsArg(extraLocations: Array<out String>): String = "--spring.flyway.locations=" + (listOf("classpath:db/migration") + extraLocations).joinToString(",")

    private fun recorded(database: String): Map<String, Boolean> = connect(database).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version, rollback_compatible FROM schema_rollback_compatibility").use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getBoolean(2)) }
            }
        }
    }

    private fun appliedVersions(database: String): Set<String> = connect(database).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun freshDatabase(name: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$name\"")
                it.execute("CREATE DATABASE \"$name\"")
            }
        }
        val queryParams = postgres.jdbcUrl.substringAfter('?', "")
        val base = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$name"
        return if (queryParams.isEmpty()) base else "$base?$queryParams"
    }

    private fun connect(database: String): Connection = DriverManager.getConnection(database, postgres.username, postgres.password)

    companion object {
        /** The probe migrations' version: later than any real migration, so it is always "the newer build's". */
        const val PROBE = "29990101000000"

        const val FAILING_PROBE = "29990102000000"

        /** The migration just before [RollbackCompatibilityDeclarationTest.FIRST_DECLARING]. */
        const val LAST_UNDECLARED = "20260923150918"
    }
}
