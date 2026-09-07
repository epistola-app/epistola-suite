-- Finding what a template generated, by identity rather than by address.
--
-- idx_documents_template_key already covers the address lookup (tenant_key, template_key). This is
-- its identity counterpart, and it is the one that keeps working after a relocation: the address
-- columns stay pinned to where the template lived at generation time, so a lookup by the template's
-- current address misses everything produced before it moved.
--
-- Partial on NOT NULL deliberately. V20260905090400 backfills the column, so a NULL means the
-- generating template has since been deleted -- a row no lookup by identity can ever want.
-- Indexing those would cost space for entries the index can never serve, and the predicate is
-- implied by any equality lookup on the column, so the planner matches it without the query having
-- to mention it.
--
-- created_at DESC is part of the key, not decoration. These tables are RANGE-partitioned on
-- created_at, so "newest first, paginated" can walk partitions in order and stop early -- but only
-- if each partition's index yields rows already ordered. Without the third column the planner falls
-- back to the created_at index and applies the identity as a filter, which measured ~3x more
-- expensive on the paginated shape (Append cost 561 vs 176, Limit 111 vs 35 on a selective
-- lookup). Since (tenant_key, template_resource_id) is a prefix of this index, one index serves
-- both the plain lookup and the paginated one.
--
-- Partitioning: each partition gets its own child index and Append combines them, so a lookup that
-- cannot prune probes every partition. That is bounded -- partitions are monthly and retention is
-- configured in months -- and partitions holding no match cost nothing.
--
-- Note on cost: CREATE INDEX on a partitioned table recurses into every partition, and since
-- V20260905090400 backfills the column the build is over real rows rather than an empty index.
-- Flyway runs a migration in one transaction, so CONCURRENTLY is not available here; an operator
-- whose generation history makes this disruptive can build it out of band instead --
-- CREATE INDEX ON ONLY, then per-partition CONCURRENTLY builds attached afterwards.
CREATE INDEX idx_documents_template_resource_id
    ON documents (tenant_key, template_resource_id, created_at DESC)
    WHERE template_resource_id IS NOT NULL;

CREATE INDEX idx_generation_requests_template_resource_id
    ON document_generation_requests (tenant_key, template_resource_id, created_at DESC)
    WHERE template_resource_id IS NOT NULL;
