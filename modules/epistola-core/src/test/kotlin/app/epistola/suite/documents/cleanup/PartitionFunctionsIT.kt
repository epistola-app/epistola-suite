// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.cleanup

import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

/**
 * Direct coverage of the `epistola_create_partition` / `epistola_drop_partitions_before`
 * SECURITY DEFINER functions (issue #438), exercised against throwaway, uniquely-named
 * RANGE-partitioned tables so the assertions are self-contained and don't touch the
 * shared core partitioned tables. Complements the scheduler-level
 * [PartitionMaintenanceSchedulerIT], which covers the orchestration.
 *
 * The dates passed to the functions are fixed literals (not the test clock) — the
 * functions take a typed `date` and are clock-independent.
 */
class PartitionFunctionsIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `create_partition creates a month-bounded partition, normalizes a mid-month date, and is idempotent`() {
        withScratchParent { parent ->
            val created = createPartition(parent, LocalDate.of(2026, 6, 15)) // mid-month
            assertThat(created).isEqualTo("${parent}_2026_06")
            assertThat(tableExists(created)).isTrue

            // Bounds correctness: a June row has a home; a July row does not (no partition).
            insertTs(parent, "2026-06-20T12:00:00Z")
            assertThatThrownBy { insertTs(parent, "2026-07-20T12:00:00Z") }
                .hasMessageContaining("no partition")

            // Idempotent: re-creating the same month returns the same name and does not throw.
            val again = createPartition(parent, LocalDate.of(2026, 6, 1))
            assertThat(again).isEqualTo(created)
            assertThat(countChildPartitions(parent)).isEqualTo(1)
        }
    }

    @Test
    fun `drop_partitions_before drops months strictly older than the cutoff and keeps the cutoff month`() {
        withScratchParent { parent ->
            listOf("2026-04-01", "2026-05-01", "2026-06-01").forEach {
                createPartition(parent, LocalDate.parse(it))
            }

            val dropped = dropPartitionsBefore(parent, LocalDate.of(2026, 6, 1))

            assertThat(dropped).containsExactlyInAnyOrder("${parent}_2026_04", "${parent}_2026_05")
            assertThat(tableExists("${parent}_2026_06")).`as`("cutoff month is kept").isTrue
            assertThat(tableExists("${parent}_2026_04")).isFalse
            assertThat(tableExists("${parent}_2026_05")).isFalse
        }
    }

    @Test
    fun `drop_partitions_before only touches children of the given parent`() {
        withScratchParent { parentA ->
            withScratchParent { parentB ->
                createPartition(parentA, LocalDate.of(2026, 4, 1))
                createPartition(parentB, LocalDate.of(2026, 4, 1))

                dropPartitionsBefore(parentA, LocalDate.of(2026, 6, 1))

                assertThat(tableExists("${parentA}_2026_04")).isFalse
                assertThat(tableExists("${parentB}_2026_04"))
                    .`as`("another parent's partitions must be untouched")
                    .isTrue
            }
        }
    }

    @Test
    fun `drop_partitions_before ignores a same-named table that is not a real partition of the parent`() {
        // Proves the function targets catalog membership (pg_inherits), not names — so it
        // can't be tricked into dropping an arbitrary table that merely matches the naming.
        withScratchParent { parent ->
            createPartition(parent, LocalDate.of(2026, 4, 1)) // real child <parent>_2026_04
            val decoy = "${parent}_2026_03" // sorts before cutoff and matches the naming, but NOT a partition
            jdbi.useHandle<Exception> { it.execute("CREATE TABLE $decoy (x int)") }
            try {
                val dropped = dropPartitionsBefore(parent, LocalDate.of(2026, 6, 1))

                assertThat(dropped).containsExactly("${parent}_2026_04")
                assertThat(tableExists(decoy))
                    .`as`("a table that is not a real partition of the parent must never be dropped")
                    .isTrue
            } finally {
                jdbi.useHandle<Exception> { it.execute("DROP TABLE IF EXISTS $decoy CASCADE") }
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun withScratchParent(block: (String) -> Unit) {
        val name = "test_part_" + UUID.randomUUID().toString().replace("-", "").take(12)
        jdbi.useHandle<Exception> { it.execute("CREATE TABLE $name (ts timestamptz NOT NULL) PARTITION BY RANGE (ts)") }
        try {
            block(name)
        } finally {
            jdbi.useHandle<Exception> { it.execute("DROP TABLE IF EXISTS $name CASCADE") }
        }
    }

    private fun createPartition(parent: String, month: LocalDate): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT epistola_create_partition(:parent::regclass, :month)")
            .bind("parent", parent)
            .bind("month", month)
            .mapTo(String::class.java)
            .one()
    }

    private fun dropPartitionsBefore(parent: String, cutoff: LocalDate): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery("SELECT * FROM epistola_drop_partitions_before(:parent::regclass, :cutoff)")
            .bind("parent", parent)
            .bind("cutoff", cutoff)
            .mapTo(String::class.java)
            .list()
    }

    private fun insertTs(parent: String, isoInstant: String) = jdbi.useHandle<Exception> { handle ->
        handle.createUpdate("INSERT INTO $parent (ts) VALUES (:ts::timestamptz)")
            .bind("ts", isoInstant)
            .execute()
    }

    private fun tableExists(name: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = :name)")
            .bind("name", name)
            .mapTo(Boolean::class.java)
            .one()
    }

    private fun countChildPartitions(parent: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM pg_inherits WHERE inhparent = :parent::regclass")
            .bind("parent", parent)
            .mapTo(Int::class.java)
            .one()
    }
}
