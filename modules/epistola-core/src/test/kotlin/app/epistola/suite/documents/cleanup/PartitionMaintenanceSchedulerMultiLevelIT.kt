package app.epistola.suite.documents.cleanup

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Integration coverage for the multi-level partition maintenance path on
 * `generation_results` (LIST(partition) → RANGE(created_at)).
 *
 * Single-level partitioning of documents/document_generation_requests is
 * covered by the existing PartitionMaintenanceScheduler tests; we only verify
 * the new multi-level shape here.
 */
class PartitionMaintenanceSchedulerMultiLevelIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var scheduler: PartitionMaintenanceScheduler

    @Test
    fun `bootstraps current and next month sub-partitions for every LIST child of generation_results`() {
        scheduler.maintainPartitions()

        val now = YearMonth.now()
        val curSuffix = now.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val nextSuffix = now.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy_MM"))

        for (p in 0 until 64) {
            assertThat(tableExists("generation_results_p${p}_$curSuffix"))
                .`as`("current-month sub-partition for partition %d", p)
                .isTrue
            assertThat(tableExists("generation_results_p${p}_$nextSuffix"))
                .`as`("next-month sub-partition for partition %d", p)
                .isTrue
        }
    }

    @Test
    fun `is idempotent — re-running maintenance does not throw or create duplicates`() {
        scheduler.maintainPartitions()
        scheduler.maintainPartitions()

        // Re-running counts: still exactly current + next per LIST child = 128.
        // (The migration also created these initially, so the scheduler should
        //  detect "already exists" and skip cleanly.)
        val now = YearMonth.now()
        val curSuffix = now.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val count = countTablesLike("generation_results_p%_$curSuffix")
        assertThat(count).isEqualTo(64)
    }

    @Test
    fun `drops a manually-created old sub-partition past the retention window`() {
        // Create a fake old sub-partition outside the retention window for partition 0.
        val ancient = YearMonth.now().minusYears(2)
        val ancientSuffix = ancient.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val parent = "generation_results_p0"
        val name = "${parent}_$ancientSuffix"
        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE TABLE IF NOT EXISTS $name PARTITION OF $parent
                FOR VALUES FROM ('${ancient.atDay(1)}') TO ('${ancient.plusMonths(1).atDay(1)}')
                """,
            )
        }
        assertThat(tableExists(name)).isTrue

        scheduler.maintainPartitions()

        assertThat(tableExists(name))
            .`as`("ancient sub-partition %s should have been dropped", name)
            .isFalse
    }

    @Test
    fun `keeps current and next month sub-partitions intact after maintenance`() {
        scheduler.maintainPartitions()
        scheduler.maintainPartitions() // run twice to confirm drop pass doesn't sweep them away

        val now = YearMonth.now()
        val curSuffix = now.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val nextSuffix = now.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy_MM"))
        for (p in 0 until 64) {
            assertThat(tableExists("generation_results_p${p}_$curSuffix")).isTrue
            assertThat(tableExists("generation_results_p${p}_$nextSuffix")).isTrue
        }
    }

    private fun tableExists(name: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            SELECT EXISTS (
                SELECT 1 FROM pg_tables
                WHERE schemaname = 'public' AND tablename = :name
            )
            """,
        )
            .bind("name", name)
            .mapTo(Boolean::class.java)
            .one()
    }

    private fun countTablesLike(pattern: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COUNT(*) FROM pg_tables
            WHERE schemaname = 'public'
              AND tablename LIKE :pattern
            """,
        )
            .bind("pattern", pattern)
            .mapTo(Int::class.java)
            .one()
    }
}
