package app.epistola.suite.testing

import org.springframework.boot.ApplicationRunner
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

/**
 * Converts all tables to UNLOGGED after Flyway migrations complete.
 * This eliminates WAL (Write-Ahead Log) writes for all test operations,
 * significantly improving write performance for ephemeral test data.
 *
 * Tables are converted in multiple passes to handle FK dependency ordering:
 * a table can only become UNLOGGED if all tables it references are already UNLOGGED.
 */
@TestConfiguration(proxyBeanMethods = false)
class UnloggedTablesTestConfiguration {
    @Bean
    fun convertTablesToUnlogged(dataSource: DataSource): ApplicationRunner = ApplicationRunner {
        dataSource.connection.use { conn ->
            // Retry loop handles FK ordering: tables referencing logged tables can't
            // become unlogged until the referenced table is converted first.
            repeat(10) {
                val remaining = mutableListOf<String>()
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """
                        SELECT tablename FROM pg_tables
                        WHERE schemaname = 'public'
                        AND tablename NOT IN ('flyway_schema_history')
                        AND tablename NOT IN (
                            SELECT c.relname FROM pg_class c WHERE c.relpersistence = 'u'
                        )
                        """,
                    ).use { rs ->
                        while (rs.next()) {
                            remaining.add(rs.getString(1))
                        }
                    }
                }

                if (remaining.isEmpty()) return@repeat

                for (table in remaining) {
                    runCatching {
                        conn.createStatement().use { stmt ->
                            stmt.execute("ALTER TABLE \"$table\" SET UNLOGGED")
                        }
                    }
                }
            }
        }
    }
}
