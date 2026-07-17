-- document_content: ephemeral blob storage for generated documents (issue #738).
--
-- Split out of the single shared `content_store` so that document blobs have a
-- lifecycle that matches the `documents` metadata they belong to. RANGE-partitioned
-- by `created_at` on the SAME retention window as `documents`, so the existing
-- PartitionMaintenanceScheduler drops `document_content_YYYY_MM` in lockstep with
-- `documents_YYYY_MM`. A partition DROP reclaims the blobs in O(1) (no per-row
-- DELETE, no BYTEA bloat, no VACUUM) — fixing the orphaned-blob leak where a
-- retention partition-drop on `documents` never reclaimed the matching blobs.
--
-- Only the PostgreSQL document-content backend writes here; the S3 / filesystem
-- backends reclaim their own way (bucket lifecycle rule / age sweep) and never
-- touch this table. It is created for all backends for schema consistency, same
-- as `content_store`.
--
-- The blob's `created_at` is the OWNING document's `created_at` (passed by the
-- store), so the blob always lands in the same monthly partition as its metadata
-- and ages out with it.

CREATE TABLE document_content (
    key          VARCHAR(512) NOT NULL,
    content      BYTEA        NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    -- The partition key must be part of the PRIMARY KEY. `key` alone is unique in
    -- practice (it embeds the document id); the composite PK satisfies the
    -- partitioning constraint without changing lookup semantics (gets are by key
    -- within the live retention window).
    PRIMARY KEY (key, created_at)
) PARTITION BY RANGE (created_at);

-- No initial partitions created - PartitionMaintenanceScheduler creates the
-- current + next month at startup and daily, and drops months past retention.

COMMENT ON TABLE document_content IS 'Ephemeral generated-document blobs (PostgreSQL backend). RANGE-partitioned by created_at; dropped in lockstep with the documents table by retention. See issue #738.';
