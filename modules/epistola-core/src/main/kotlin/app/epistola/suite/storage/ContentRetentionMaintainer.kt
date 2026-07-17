package app.epistola.suite.storage

/**
 * SPI for document-content backends that need **active, periodic** reclamation of
 * expired blobs, driven by the content reaper (issue #738).
 *
 * Not every backend needs this: the PostgreSQL backend reclaims via partition drops
 * (`PartitionMaintenanceScheduler`), and the S3 backend reclaims via a bucket
 * lifecycle rule ensured at startup — both contribute no maintainer. Only the
 * filesystem backend, which has no native TTL, implements this to run an age sweep.
 *
 * The reaper injects `List<ContentRetentionMaintainer>` and calls each, so it never
 * has to know which backend is active (empty list on PostgreSQL / S3).
 */
interface ContentRetentionMaintainer {
    /** Reclaim document blobs older than [retentionMonths]. */
    fun reclaim(retentionMonths: Int)
}
