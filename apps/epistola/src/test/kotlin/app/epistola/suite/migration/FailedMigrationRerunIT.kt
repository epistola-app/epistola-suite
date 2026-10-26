// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration

import app.epistola.suite.testing.TestRuntimeLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the large code migrations rely on (ADR 0035): a non-transactional migration that is interrupted
 * halfway is run again by the next `migrate()`, with no `flyway repair` (Flyway records no history row
 * for it), and an idempotent one then completes. Its committed batches are kept, and the history of the migrations before it is untouched.
 * Should a Flyway upgrade change that, this fails, and the migration step has to repair before it
 * migrates.
 */
@Tag("integration")
class FailedMigrationRerunIT {

    @Test
    fun `an interrupted non-transactional migration is run again by the next migrate and completes`() {
        val url = database()
        FAIL_AFTER_FIRST_BATCH.set(true)
        assertThatThrownBy { flyway(url).migrate() }
        assertThat(rows(url)).isEqualTo(5)
        // Flyway records no row for it, so nothing blocks the next run.
        assertThat(history(url, "2")).isNull()
        val base = history(url, "1")

        FAIL_AFTER_FIRST_BATCH.set(false)
        val result = flyway(url).migrate()

        assertThat(result.migrationsExecuted).isEqualTo(1)
        assertThat(rows(url)).isEqualTo(10)
        assertThat(history(url, "2")).endsWith("|true")
        assertThat(history(url, "1")).isEqualTo(base)
    }

    private fun flyway(url: String): Flyway = Flyway.configure()
        .dataSource(url, postgres.username, postgres.password)
        .locations("classpath:db/failed-migration-rerun-it")
        .javaMigrations(V1__base(), V2__batches())
        .load()

    private fun database(): String {
        val name = "failed_migration_rerun"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { admin ->
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS \"$name\"")
                it.execute("CREATE DATABASE \"$name\"")
            }
        }
        return postgres.jdbcUrl.replaceFirst(Regex("/[^/?]+(\\?|$)"), "/$name$1")
    }

    private fun rows(url: String): Int = query(url, "SELECT count(*) FROM items")!!.toInt()

    private fun history(url: String, version: String): String? = query(
        url,
        "SELECT installed_rank || '|' || installed_on || '|' || success FROM flyway_schema_history WHERE version = '$version' ORDER BY installed_rank DESC LIMIT 1",
    )

    private fun query(url: String, sql: String): String? = DriverManager.getConnection(url, postgres.username, postgres.password).use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private val postgres get() = TestRuntimeLifecycle.postgres()

    @Suppress("ClassName")
    class V1__base : BaseJavaMigration() {
        override fun migrate(context: Context) {
            context.connection.createStatement().use { it.execute("CREATE TABLE items (id INT PRIMARY KEY)") }
        }
    }

    /** Ten rows in two committed batches, idempotently, as the large code migrations work; fails after the first when told to. */
    @Suppress("ClassName")
    class V2__batches : BaseJavaMigration() {
        override fun canExecuteInTransaction(): Boolean = false

        override fun migrate(context: Context) {
            val connection = context.connection
            connection.autoCommit = false
            for (batch in 0..1) {
                connection.createStatement().use { it.execute("INSERT INTO items SELECT g FROM generate_series(${batch * 5 + 1}, ${batch * 5 + 5}) g ON CONFLICT DO NOTHING") }
                connection.commit()
                if (batch == 0 && FAIL_AFTER_FIRST_BATCH.get()) error("interrupted after the first batch")
            }
            connection.autoCommit = true
        }
    }

    companion object {
        val FAIL_AFTER_FIRST_BATCH = AtomicBoolean(false)
    }
}
