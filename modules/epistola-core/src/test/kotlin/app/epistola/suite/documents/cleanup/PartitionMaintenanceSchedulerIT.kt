package app.epistola.suite.documents.cleanup

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Smoke coverage for RANGE-partition maintenance across all three managed
 * tables. The scheduler treats every partitioned table the same way; this test
 * pins the behavior on `generation_results` because it's the new addition and
 * has its own retention setting (`generation-results-retention-months`,
 * default 1) distinct from the default `retention-months` (3) used by
 * documents/document_generation_requests.
 */
class PartitionMaintenanceSchedulerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var scheduler: PartitionMaintenanceScheduler

    @Test
    fun `bootstraps current and next month partitions for generation_results`() {
        scheduler.maintainPartitions()

        val now = YearMonth.now()
        val curSuffix = now.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val nextSuffix = now.plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy_MM"))

        assertThat(tableExists("generation_results_$curSuffix"))
            .`as`("current-month partition")
            .isTrue
        assertThat(tableExists("generation_results_$nextSuffix"))
            .`as`("next-month partition")
            .isTrue
    }

    @Test
    fun `is idempotent — re-running maintenance does not throw or create duplicates`() {
        scheduler.maintainPartitions()
        scheduler.maintainPartitions()

        val now = YearMonth.now()
        val curSuffix = now.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        // Exactly one current-month partition for each managed table.
        assertThat(countTablesLike("generation_results_$curSuffix")).isEqualTo(1)
        assertThat(countTablesLike("documents_$curSuffix")).isEqualTo(1)
        assertThat(countTablesLike("document_generation_requests_$curSuffix")).isEqualTo(1)
    }

    @Test
    fun `drops a manually-created old partition past the retention window`() {
        // generation_results uses the 1-month default; create a 2-year-old partition
        // so it's well past the cutoff and must be swept.
        val ancient = YearMonth.now().minusYears(2)
        val ancientSuffix = ancient.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val name = "generation_results_$ancientSuffix"
        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE TABLE IF NOT EXISTS $name PARTITION OF generation_results
                FOR VALUES FROM ('${ancient.atDay(1)}') TO ('${ancient.plusMonths(1).atDay(1)}')
                """,
            )
        }
        assertThat(tableExists(name)).isTrue

        scheduler.maintainPartitions()

        assertThat(tableExists(name))
            .`as`("ancient partition %s should have been dropped", name)
            .isFalse
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
