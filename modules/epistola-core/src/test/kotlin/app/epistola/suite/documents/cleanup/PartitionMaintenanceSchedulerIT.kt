package app.epistola.suite.documents.cleanup

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskRegistry
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduler
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.OffsetDateTime
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
@Isolated("Drops and recreates shared partition tables while asserting scheduler side effects")
class PartitionMaintenanceSchedulerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var scheduler: PartitionMaintenanceScheduler

    @Autowired
    private lateinit var clusterScheduledTaskScheduler: ClusterScheduledTaskScheduler

    @Autowired
    private lateinit var scheduledTaskRegistry: ClusterScheduledTaskRegistry

    @Test
    fun `registers a clustered scheduled task definition`() {
        val task = scheduledTaskRegistry.find(PartitionMaintenanceScheduler.TASK_KEY)

        assertThat(task).isNotNull
        assertThat(task?.routingKey).isEqualTo(PartitionMaintenanceScheduler.ROUTING_KEY)
        assertThat(task?.taskType).isEqualTo(PartitionMaintenanceScheduler.TASK_TYPE)
        assertThat(task?.cronExpression).isEqualTo("0 0 2 * * ?")
    }

    @Test
    fun `bootstraps current and next month partitions for generation_results`() {
        scheduler.maintainPartitions()

        val now = YearMonth.now(testClock)
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
    fun `afterSingletonsInstantiated bootstraps partitions in the pre-runner phase`() {
        // Regression guard for the startup ORDERING. Partition bootstrap must run BEFORE Spring's
        // ApplicationRunners, because a runner (e.g. the demo loader) can write to a partitioned table
        // at startup and the partition has to already exist. The pre-runner phase is
        // SmartInitializingSingleton.afterSingletonsInstantiated() — NOT
        // @EventListener(ApplicationReadyEvent), which fires AFTER all runners (the bug that crashed
        // demo load). Spring guarantees afterSingletonsInstantiated() runs before runners; this proves
        // the bootstrap is wired to that hook, so the two together give "partitions exist before any
        // runner". Drop a sentinel and prove the hook recreates it — if bootstrap regresses out of this
        // hook, the sentinel stays gone and this fails.
        val nextSuffix = YearMonth.now(testClock).plusMonths(1).format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val sentinel = "generation_results_$nextSuffix"
        jdbi.useHandle<Exception> { handle -> handle.execute("DROP TABLE IF EXISTS $sentinel CASCADE") }
        assertThat(tableExists(sentinel)).isFalse

        scheduler.afterSingletonsInstantiated()

        assertThat(tableExists(sentinel))
            .`as`("the pre-runner hook afterSingletonsInstantiated() must bootstrap partitions")
            .isTrue
    }

    @Test
    fun `cluster scheduled task poll runs partition maintenance handler after time advances past due`() = scenario {
        given {
            val task = scheduledTaskRegistry.find(PartitionMaintenanceScheduler.TASK_KEY)
                ?: error("Partition maintenance scheduled task was not registered")
            val nextMonth = YearMonth.from(task.nextDueAt).plusMonths(1)
            val nextSuffix = nextMonth.format(DateTimeFormatter.ofPattern("yyyy_MM"))
            val sentinelTable = "generation_results_$nextSuffix"
            jdbi.useHandle<Exception> { handle ->
                handle.execute("DROP TABLE IF EXISTS $sentinelTable CASCADE")
            }
            assertThat(tableExists(sentinelTable)).isFalse
            PartitionMaintenancePollSetup(
                sentinelTable = sentinelTable,
                nextDueAt = task.nextDueAt,
            )
        }.whenever { setup ->
            advancePast(setup.nextDueAt)
            clusterScheduledTaskScheduler.poll()
        }.then { setup, _ ->
            assertThat(tableExists(setup.sentinelTable))
                .`as`("cluster scheduled task poll should dispatch partition maintenance")
                .isTrue
        }
    }

    @Test
    fun `is idempotent — re-running maintenance does not throw or create duplicates`() {
        scheduler.maintainPartitions()
        scheduler.maintainPartitions()

        val now = YearMonth.now(testClock)
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
        val ancient = YearMonth.now(testClock).minusYears(2)
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

    @Test
    fun `skips quietly when another instance holds the advisory lock`() {
        // Simulate "another Suite instance is currently running maintenance" by
        // grabbing the same advisory lock on a held connection. The scheduler's
        // call MUST short-circuit (no exception, no work) and the call after
        // we release MUST proceed normally.
        //
        // Use the *next month* partition as the sentinel — current-month is hot
        // (other parallel ITs in this suite insert into it), so dropping it would
        // destroy their rows. Next-month exists (created by prior maintainPartitions
        // calls in other tests) but is empty.
        val nextMonth = YearMonth.now(testClock).plusMonths(1)
        val nextSuffix = nextMonth.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val sentinelTable = "generation_results_$nextSuffix"

        // Drop the sentinel so we can detect whether maintainPartitions() actually
        // ran (it would re-create this partition).
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DROP TABLE IF EXISTS $sentinelTable CASCADE")
        }
        assertThat(tableExists(sentinelTable)).isFalse

        jdbi.open().use { holder ->
            holder.begin()
            // Same numeric key as PartitionMaintenanceScheduler.PARTITION_MAINTENANCE_LOCK_KEY.
            val acquired = holder.createQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .bind("key", 0x4570_5061_7274_4D31L)
                .mapTo(Boolean::class.java)
                .one()
            assertThat(acquired).isTrue

            scheduler.maintainPartitions()

            // Lock contention path: no work happened; sentinel still missing.
            assertThat(tableExists(sentinelTable))
                .`as`("scheduler must not have created partitions while another holder is on the lock")
                .isFalse

            holder.rollback() // releases the advisory lock
        }

        // Now that the lock is free, the scheduler should proceed.
        scheduler.maintainPartitions()
        assertThat(tableExists(sentinelTable))
            .`as`("scheduler must create partitions once the lock is available")
            .isTrue
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

    private fun advancePast(dueAt: OffsetDateTime) {
        testClock.set(dueAt.toInstant().minusSeconds(1))
        testClock.advanceBy(Duration.ofSeconds(2))
    }

    private data class PartitionMaintenancePollSetup(
        val sentinelTable: String,
        val nextDueAt: OffsetDateTime,
    )
}
