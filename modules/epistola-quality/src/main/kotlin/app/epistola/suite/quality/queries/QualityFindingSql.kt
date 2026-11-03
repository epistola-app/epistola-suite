// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.catalog.inVersionOrNext
import app.epistola.suite.templates.TEMPLATE_ADDRESS_COLUMNS
import app.epistola.suite.templates.templateJoin

/**
 * The SQL the quality reads share, so the panel, the report and a finding's page agree on what a
 * catalog version shows and on how a decision applies (ADR 0044).
 */
internal object QualityFindingSql {
    /**
     * The version a statement built from these reads, bound with `bindVersion` (ADR 0049): that
     * version for its own catalog, every other catalog's next version; null, every catalog's next.
     */
    const val VERSION: String = "qualityVersion"

    /**
     * Every finding the catalog version bound as [VERSION] shows, with `outdated`: per variant and
     * source the findings of the check `quality_version_checks` picks (outdated when its key is not the
     * variant's key there), and every note a person raised, which belongs to its template and is never
     * outdated. [checkFilter] narrows the checks (alias `k`), [noteFilter] the notes (alias `f`).
     */
    fun shown(checkFilter: String, noteFilter: String): String =
        """
        SELECT f.*, NOT k.current AS outdated
        FROM quality_version_checks k
        JOIN quality_findings f
          ON f.tenant_key = k.tenant_key AND f.template_resource_id = k.template_resource_id AND f.variant_key = k.variant_key
         AND f.source_id = k.source_id AND f.input_key = k.input_key
        WHERE ${inVersionOrNext("k", VERSION)} AND $checkFilter
        UNION ALL
        SELECT f.*, FALSE AS outdated
        FROM quality_findings f
        WHERE f.input_key IS NULL AND $noteFilter
        """

    /**
     * Whether a finding row `f` is outdated in the version bound as [VERSION]: its check's key is not its
     * variant's key there, or that version does not hold the variant. A note never is.
     */
    val OUTDATED: String =
        """
        (f.input_key IS NOT NULL AND f.input_key IS DISTINCT FROM (
            SELECT q.input_key FROM quality_version_variants q
            WHERE q.tenant_key = f.tenant_key AND q.template_resource_id = f.template_resource_id AND q.variant_key = f.variant_key
              AND ${inVersionOrNext("q", VERSION)}))
        """

    /**
     * The columns `mapFinding` reads beyond the row of [alias]: its template's address, its effective
     * status, the reason it is ignored, whether a check reconciles it, its comment count. Needs
     * [decisions] joined.
     */
    fun columns(alias: String): String =
        """
        $TEMPLATE_ADDRESS_COLUMNS,
        CASE WHEN $alias.status = 'RESOLVED'          THEN 'RESOLVED'
             WHEN i.finding_fingerprint IS NOT NULL THEN 'IGNORED'
             ELSE 'OPEN' END AS effective_status,
        i.reason AS ignore_reason,
        ($alias.source_id <> :manualSourceId) AS reconciled,
        COALESCE(c.comment_count, 0)::int AS comment_count
        """

    /**
     * The template [alias] is about, in the version bound as [VERSION], and the decisions on the problem:
     * its live ignore and its comments. Both are keyed by the problem (scope, source, rule,
     * fingerprint), never by a finding's row, so they hold in every version and through every check
     * that reports it again. Binds `:manualSourceId` through [columns].
     */
    fun decisions(alias: String): String =
        """
        ${templateJoin(alias, VERSION)}
        LEFT JOIN quality_finding_ignores i
               ON i.tenant_key          = $alias.tenant_key
              AND i.ignore_scope_urn    = $alias.ignore_scope_urn
              AND i.source_id           = $alias.source_id
              AND i.rule_id             = $alias.rule_id
              AND i.finding_fingerprint = $alias.fingerprint
              AND i.revoked_at IS NULL
        LEFT JOIN LATERAL (
            SELECT COUNT(*) AS comment_count
            FROM quality_finding_comments qc
            WHERE qc.tenant_key = $alias.tenant_key AND qc.ignore_scope_urn = $alias.ignore_scope_urn
              AND qc.source_id = $alias.source_id AND qc.rule_id = $alias.rule_id AND qc.finding_fingerprint = $alias.fingerprint
        ) c ON TRUE
        """
}
