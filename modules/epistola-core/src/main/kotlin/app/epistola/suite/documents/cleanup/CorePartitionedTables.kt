package app.epistola.suite.documents.cleanup

import app.epistola.suite.partitions.PartitionedTable
import app.epistola.suite.partitions.PartitionedTableContributor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The partitioned tables owned by epistola-core. Retention is per-table because the
 * data has different value over time: `documents` and `document_generation_requests`
 * get the longer `epistola.partitions.retention-months` (default 3);
 * `generation_results` is consumer-driven ephemera that gets the shorter
 * `epistola.partitions.generation-results-retention-months` (default 1); `event_log`
 * is a PII-bearing command record aged out on its own compliance window
 * `epistola.partitions.event-log-retention-months` (default 6).
 */
@Component
class CorePartitionedTables(
    @param:Value("\${epistola.partitions.retention-months:3}")
    private val retentionMonths: Int,
    @param:Value("\${epistola.partitions.generation-results-retention-months:1}")
    private val generationResultsRetentionMonths: Int,
    @param:Value("\${epistola.partitions.event-log-retention-months:6}")
    private val eventLogRetentionMonths: Int,
) : PartitionedTableContributor {
    override fun partitionedTables(): List<PartitionedTable> = listOf(
        PartitionedTable("documents", retentionMonths),
        // document_content holds the ephemeral document blobs (PostgreSQL backend);
        // it MUST share the exact retention of `documents` so its monthly partitions
        // are dropped in lockstep and blobs are reclaimed with their metadata (#738).
        PartitionedTable("document_content", retentionMonths),
        PartitionedTable("document_generation_requests", retentionMonths),
        PartitionedTable("generation_results", generationResultsRetentionMonths),
        PartitionedTable("event_log", eventLogRetentionMonths),
    )
}
