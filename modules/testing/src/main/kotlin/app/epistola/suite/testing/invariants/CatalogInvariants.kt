// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.invariants

import org.jdbi.v3.core.Jdbi

/** One row that breaks a catalog invariant: which rule, in which tenant, and what it found. */
data class InvariantViolation(val invariant: String, val tenantKey: String, val detail: String) {
    override fun toString(): String = "[$invariant] $tenantKey: $detail"
}

/**
 * What must hold of a tenant's catalogs after every committed command, whatever sequence of commands
 * led there, written as SQL that returns the rows breaking it. The database enforces what one row or
 * one table can (foreign keys, checks, the next-draft and variant triggers); these are the rules that
 * span tables and that only the code keeps: a reference resolving through the declared dependency, a
 * release pinning releases only, catalogs never using each other. A rule the database already
 * enforces is left out (`CatalogInvariantsTest` tried each corruption: a catalog without its next
 * draft and a version holding one identity twice are refused by triggers).
 *
 * Every rule is one `SELECT` of a single `UNION ALL`, so a check is one round trip, and it is scoped to
 * the tenants whose key matches a pattern, so it reads only what one test class created.
 *
 * The release rules hold for a release however it arrived, since a release is a release (ADR 0036)
 * and the install core requires its dependencies. A release received from elsewhere with a pin the
 * tenant lacks would break them; no test receives one, and those simulating it opt out.
 */
object CatalogInvariants {
    /** The violations in the tenants whose key matches the `LIKE` pattern [tenants]. */
    fun check(jdbi: Jdbi, tenants: String): List<InvariantViolation> = jdbi.withHandle<List<InvariantViolation>, Exception> { handle ->
        handle.createQuery(SQL)
            .bind("tenants", tenants)
            .map { rs, _ -> InvariantViolation(rs.getString("invariant"), rs.getString("tenant_key"), rs.getString("detail")) }
            .list()
    }

    // A catalog and a version are named in a message as namespace/key and draft "name" or release 1.2.0.
    private const val SQL = """
WITH RECURSIVE scope AS (SELECT id AS tenant_key FROM tenants WHERE id LIKE :tenants),
cat AS (
    SELECT c.tenant_key, c.catalog_id, c.next_version_id, c.namespace || '/' || c.catalog_key AS label
    FROM catalogs c WHERE c.tenant_key IN (SELECT tenant_key FROM scope)
),
ver AS (
    SELECT v.tenant_key, v.version_id, v.catalog_id, v.state, v.version, v.base_version_id,
           cat.label || ' ' || CASE WHEN v.state = 'released' THEN v.version ELSE 'draft ' || COALESCE('"' || v.name || '"', v.version_key) END AS label
    FROM catalog_versions v JOIN cat ON cat.tenant_key = v.tenant_key AND cat.catalog_id = v.catalog_id
),
dep AS (
    SELECT d.*, ver.state, ver.label, epistola_catalog_id(d.tenant_key, d.namespace, d.dependency_key) AS named_catalog_id
    FROM catalog_version_dependencies d JOIN ver ON ver.tenant_key = d.tenant_key AND ver.version_id = d.version_id
),
ref AS (
    SELECT r.tenant_key, r.catalog_id, r.version_id, ver.state, ver.label, r.resource_type AS src_type, r.resource_key AS src_key,
           x.alias, x.resource_type, x.resource_key,
           resolve_catalog_alias(r.tenant_key, r.catalog_id, r.version_id, NULL, x.alias) AS target_catalog_id
    FROM release_resources r
    JOIN ver ON ver.tenant_key = r.tenant_key AND ver.version_id = r.version_id AND ver.state = 'released'
    JOIN resource_references x ON x.tenant_key = r.tenant_key AND x.content_hash = r.content_hash
),
ref_target AS (
    SELECT ref.*,
           CASE WHEN ref.target_catalog_id = ref.catalog_id THEN ref.version_id
                ELSE (SELECT COALESCE(
                          d.target_version_id,
                          (SELECT tv.version_id FROM catalog_versions tv
                           WHERE tv.tenant_key = d.tenant_key AND tv.catalog_id = ref.target_catalog_id
                             AND tv.state = 'released' AND tv.version = d.release_version),
                          CASE WHEN d.release_version IS NULL THEN
                              (SELECT c.next_version_id FROM catalogs c WHERE c.tenant_key = d.tenant_key AND c.catalog_id = ref.target_catalog_id)
                          END)
                      FROM catalog_version_dependencies d
                      WHERE d.tenant_key = ref.tenant_key AND d.version_id = ref.version_id AND d.alias = ref.alias)
           END AS target_version_id
    FROM ref
),
edge AS (
    SELECT DISTINCT tenant_key, catalog_id AS src, named_catalog_id AS dst FROM dep WHERE named_catalog_id IS NOT NULL
),
walk AS (
    SELECT tenant_key, src AS start, dst AS cur, ARRAY[src] AS path FROM edge
    UNION ALL
    SELECT w.tenant_key, w.start, e.dst, w.path || w.cur
    FROM walk w JOIN edge e ON e.tenant_key = w.tenant_key AND e.src = w.cur
    WHERE w.cur <> w.start AND NOT w.cur = ANY (w.path[2:])
)

SELECT 'base-is-release' AS invariant, v.tenant_key,
       v.label || ' is based on ' || b.label || ', which is not a release of the same catalog' AS detail
FROM ver v JOIN ver b ON b.tenant_key = v.tenant_key AND b.version_id = v.base_version_id
WHERE b.state <> 'released' OR b.catalog_id <> v.catalog_id

UNION ALL
SELECT 'release-pins-releases', tenant_key,
       label || ' declares ' || namespace || '/' || dependency_key || ' without pinning a release'
FROM dep
WHERE state = 'released'
  AND (release_version IS NULL
       OR EXISTS (SELECT 1 FROM catalog_versions t WHERE t.tenant_key = dep.tenant_key AND t.version_id = dep.target_version_id AND t.state = 'draft'))

UNION ALL
SELECT 'dependency-present', tenant_key,
       label || ' declares ' || namespace || '/' || dependency_key || ', which the tenant does not have'
FROM dep WHERE named_catalog_id IS NULL

UNION ALL
SELECT 'pinned-release-present', dep.tenant_key,
       dep.label || ' pins ' || dep.namespace || '/' || dep.dependency_key || ' ' || dep.release_version ||
       CASE WHEN p.version_id IS NULL THEN ', which is not here' ELSE ' with fingerprint ' || dep.release_fingerprint || ', which that release does not have' END
FROM dep
LEFT JOIN catalog_versions p ON p.tenant_key = dep.tenant_key AND p.catalog_id = dep.named_catalog_id
     AND p.state = 'released' AND p.version = dep.release_version
-- A release only: deleting a release is refused while a release pins it, not a draft, and
-- releasing such a draft is refused (ReleaseReferenceIntegrityTest).
WHERE dep.state = 'released' AND dep.named_catalog_id IS NOT NULL AND dep.release_version IS NOT NULL
  AND (p.version_id IS NULL OR dep.release_fingerprint NOT IN (p.fingerprint, COALESCE(p.content_hash, p.fingerprint)))

UNION ALL
SELECT 'dependency-target-in-named-catalog', dep.tenant_key,
       dep.label || ' targets a version of another catalog than ' || dep.namespace || '/' || dep.dependency_key
FROM dep JOIN catalog_versions t ON t.tenant_key = dep.tenant_key AND t.version_id = dep.target_version_id
WHERE t.catalog_id IS DISTINCT FROM dep.named_catalog_id

UNION ALL
SELECT DISTINCT ON (w.tenant_key, w.start) 'catalogs-never-use-each-other', w.tenant_key,
       'catalog ' || c.label || ' uses itself through ' ||
       (SELECT string_agg(pc.label, ' -> ' ORDER BY o) FROM unnest(w.path || w.cur) WITH ORDINALITY u(id, o)
        JOIN cat pc ON pc.tenant_key = w.tenant_key AND pc.catalog_id = u.id)
FROM walk w JOIN cat c ON c.tenant_key = w.tenant_key AND c.catalog_id = w.start
WHERE w.cur = w.start

UNION ALL
SELECT 'draft-identity-registered', r.tenant_key,
       ver.label || ': ' || r.resource_type || ' ' || r.resource_key || ' has identity ' || r.resource_id ||
       COALESCE(', registered as ' || cr.resource_type || ' ' || cr.resource_key || ' of another address', ', registered nowhere')
FROM release_resources r
JOIN ver ON ver.tenant_key = r.tenant_key AND ver.version_id = r.version_id AND ver.state = 'draft'
LEFT JOIN catalog_resources cr ON cr.tenant_key = r.tenant_key AND cr.resource_id = r.resource_id
WHERE r.parent_id IS NULL
  AND (cr.resource_id IS NULL OR cr.catalog_id <> r.catalog_id OR cr.resource_type <> r.resource_type OR cr.resource_key <> r.resource_key)

UNION ALL
SELECT 'released-entry-fingerprinted', r.tenant_key,
       ver.label || ': ' || r.resource_type || ' ' || r.resource_key || ' has no fingerprint'
FROM release_resources r JOIN ver ON ver.tenant_key = r.tenant_key AND ver.version_id = r.version_id AND ver.state = 'released'
WHERE r.parent_id IS NULL AND r.fingerprint IS NULL

UNION ALL
SELECT 'image-bytes-present', r.tenant_key,
       ver.label || ': image ' || r.resource_key || ' names bytes ' || h.hash || ' that are not stored'
FROM release_resources r
JOIN ver ON ver.tenant_key = r.tenant_key AND ver.version_id = r.version_id
JOIN resource_contents rc ON rc.tenant_key = r.tenant_key AND rc.content_hash = r.content_hash
CROSS JOIN LATERAL (SELECT COALESCE(rc.payload #>> '{resource,contentHash}', rc.payload ->> 'contentHash') AS hash) h
WHERE r.resource_type = 'image' AND h.hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM asset_content ac WHERE ac.content_hash = h.hash)

-- A release only: a draft may hold a reference nothing resolves (a forced delete, a declaration
-- removed while in use), which the graph reports as missing; what is released must render.
UNION ALL
SELECT 'release-reference-resolves', t.tenant_key,
       t.label || ': ' || t.src_type || ' ' || t.src_key || ' uses ' || t.resource_type || ' ' ||
       COALESCE(t.alias || ':', '') || t.resource_key ||
       CASE WHEN t.target_catalog_id IS NULL THEN ', an alias it does not declare'
            WHEN t.target_version_id IS NULL THEN ', whose catalog it pins no version of here'
            ELSE ', which ' || COALESCE((SELECT label FROM ver WHERE ver.tenant_key = t.tenant_key AND ver.version_id = t.target_version_id), '?') || ' does not hold'
       END
FROM ref_target t
WHERE t.state = 'released' AND (t.target_catalog_id IS NULL OR t.target_version_id IS NULL
   OR (NOT EXISTS (
           SELECT 1 FROM release_resources h
           WHERE h.tenant_key = t.tenant_key AND h.version_id = t.target_version_id AND h.parent_id IS NULL
             AND h.resource_type = t.resource_type AND h.resource_key = t.resource_key)
       -- An image or a variant attribute named without a catalog is looked up tenant-wide
       -- (ResourceReferenceSites.IMAGE_ASSET, docs/resource-reference-graph.md).
       AND NOT (t.resource_type IN ('image', 'attribute') AND t.alias IS NULL AND EXISTS (
           SELECT 1 FROM release_resources h JOIN cat ON cat.tenant_key = h.tenant_key AND cat.next_version_id = h.version_id
           WHERE h.tenant_key = t.tenant_key AND h.resource_type = t.resource_type AND h.resource_key = t.resource_key))))
"""
}
