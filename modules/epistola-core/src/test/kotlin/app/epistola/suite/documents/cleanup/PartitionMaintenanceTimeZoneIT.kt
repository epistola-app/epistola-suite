// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.cleanup

import app.epistola.suite.partitions.PartitionedTable
import app.epistola.suite.partitions.PartitionedTableContributor
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.time.EpistolaClock
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.DriverManager
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * Monthly partitions span UTC midnights whatever time zone a node runs in. The bounds are `date`
 * literals that Postgres reads against the `timestamptz` key in the session's zone, and pgjdbc takes
 * that zone from the JVM; this runs partition maintenance through connections set to Asia/Tokyo and
 * reads the bounds back in UTC.
 */
class PartitionMaintenanceTimeZoneIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun `a node outside UTC still creates partitions bounded at UTC midnights`() {
        val parent = "test_part_" + UUID.randomUUID().toString().replace("-", "").take(12)
        // Infrastructure only: a scratch RANGE-partitioned table, no command creates one.
        jdbi.useHandle<Exception> { it.execute("CREATE TABLE $parent (ts timestamptz NOT NULL) PARTITION BY RANGE (ts)") }
        try {
            val hikari = dataSource.unwrap(HikariDataSource::class.java)
            // Unpooled connections in Tokyo, so the shared pool's sessions are never touched.
            val tokyo = Jdbi.create {
                DriverManager.getConnection(hikari.jdbcUrl, hikari.username, hikari.password).also { connection ->
                    connection.createStatement().use { it.execute("SET TIME ZONE 'Asia/Tokyo'") }
                }
            }
            val contributor = object : PartitionedTableContributor {
                override fun partitionedTables() = listOf(PartitionedTable(parent, retentionMonths = null))
            }
            PartitionMaintenanceScheduler(tokyo, SimpleMeterRegistry(), listOf(contributor), "0 0 2 * * ?")
                .maintainPartitions()

            val month = YearMonth.from(EpistolaClock.instant().atOffset(ZoneOffset.UTC))
            val bounds = jdbi.withHandle<String, Exception> { handle ->
                handle.execute("SET TIME ZONE 'UTC'")
                handle.createQuery(
                    """
                    SELECT pg_get_expr(c.relpartbound, c.oid)
                    FROM pg_class c
                    WHERE c.relname = :child
                    """,
                ).bind("child", "${parent}_${month.toString().replace('-', '_')}")
                    .mapTo(String::class.java)
                    .one()
                    .also { handle.execute("RESET TIME ZONE") }
            }
            assertThat(bounds).isEqualTo(
                "FOR VALUES FROM ('${month.atDay(1)} 00:00:00+00') TO ('${month.plusMonths(1).atDay(1)} 00:00:00+00')",
            )
        } finally {
            jdbi.useHandle<Exception> { it.execute("DROP TABLE IF EXISTS $parent CASCADE") }
        }
    }
}
