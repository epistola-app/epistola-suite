package app.epistola.suite.generation.collect.persistence

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.GenerationResultRow

/**
 * Append-only store for terminal generation results.
 *
 * Internal helper. Not part of the public API surface — commands and queries
 * dispatch into this; tests target those, not this. Tenant-scoped: every row
 * carries a `tenant_key` and every read filters on it (defense-in-depth even
 * though `consumer_id` is implicitly tied to one tenant via `api_keys`).
 */
interface GenerationResultRepository {

    /**
     * Insert a single result row. The `sequence` and `createdAt` fields on [row]
     * are overwritten by the database (BIGSERIAL + DEFAULT NOW()); callers may
     * pass `0L` and `OffsetDateTime.now()` as placeholders.
     *
     * Returns the inserted row with the database-assigned `sequence`.
     */
    fun append(row: GenerationResultRow): GenerationResultRow

    /**
     * Read up to `limit` result rows for [tenantKey], restricted to the given
     * [partitions] and to sequences strictly greater than the corresponding cursor:
     *
     *   WHERE tenant_key = :tenantKey
     *     AND partition IN (...)
     *     AND sequence > cursorByPartition[partition]
     *   ORDER BY sequence
     *   LIMIT limit
     *
     * If a partition is in [partitions] but not in [cursorByPartition], its cursor
     * defaults to 0 (never acked anything from that partition yet).
     */
    fun findFor(
        tenantKey: TenantKey,
        partitions: Set<Int>,
        cursorByPartition: Map<Int, Long>,
        limit: Int,
    ): List<GenerationResultRow>
}
