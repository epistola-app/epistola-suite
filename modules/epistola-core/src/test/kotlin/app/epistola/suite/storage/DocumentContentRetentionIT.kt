package app.epistola.suite.storage

import app.epistola.suite.documents.cleanup.PartitionMaintenanceScheduler
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * The #738 fix: retention now reclaims document blobs. `document_content` is
 * partitioned on the SAME window as `documents`, so when partition maintenance drops
 * an old month it drops the matching `document_content` month too — reclaiming the
 * blobs in lockstep. The old bug was that the blobs lived in an unpartitioned
 * `content_store` that the partition DROP could never reach, leaking forever.
 */
@Isolated("Drops and recreates shared partition tables")
class DocumentContentRetentionIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var scheduler: PartitionMaintenanceScheduler

    @Test
    fun `dropping an old month reclaims its document blobs in lockstep`() {
        val ancient = YearMonth.now(testClock).minusYears(2)
        val suffix = ancient.format(DateTimeFormatter.ofPattern("yyyy_MM"))
        val partition = "document_content_$suffix"

        // Plant a historical blob directly in an ancient partition (raw SQL: no command
        // writes a 2-year-old blob, and document_content has no FKs to satisfy).
        jdbi.useHandle<Exception> { handle ->
            handle.execute(
                """
                CREATE TABLE IF NOT EXISTS $partition PARTITION OF document_content
                FOR VALUES FROM ('${ancient.atDay(1)}') TO ('${ancient.plusMonths(1).atDay(1)}')
                """,
            )
            handle.createUpdate(
                """
                INSERT INTO document_content (key, content, content_type, size_bytes, created_at)
                VALUES ('documents/t/ancient', :bytes, 'application/pdf', 3, :createdAt)
                """,
            )
                .bind("bytes", byteArrayOf(1, 2, 3))
                .bind("createdAt", ancient.atDay(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .execute()
        }
        assertThat(tableExists(partition)).isTrue
        assertThat(blobCount("documents/t/ancient")).isEqualTo(1)

        scheduler.maintainPartitions()

        // The ancient month is dropped, and with it the blob — reclaimed, not orphaned.
        assertThat(tableExists(partition))
            .`as`("ancient document_content partition %s should be dropped by retention", partition)
            .isFalse
        assertThat(blobCount("documents/t/ancient"))
            .`as`("blob should be reclaimed with the dropped partition")
            .isEqualTo(0)
    }

    @Test
    fun `document_content current and next month partitions are bootstrapped`() {
        scheduler.maintainPartitions()
        val now = YearMonth.now(testClock)
        assertThat(tableExists("document_content_${now.format(SUFFIX)}")).isTrue
        assertThat(tableExists("document_content_${now.plusMonths(1).format(SUFFIX)}")).isTrue
    }

    private fun blobCount(key: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM document_content WHERE key = :k")
            .bind("k", key)
            .mapTo(Int::class.java)
            .one()
    }

    private fun tableExists(name: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tablename = :name)")
            .bind("name", name)
            .mapTo(Boolean::class.java)
            .one()
    }

    private companion object {
        val SUFFIX: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy_MM")
    }
}
