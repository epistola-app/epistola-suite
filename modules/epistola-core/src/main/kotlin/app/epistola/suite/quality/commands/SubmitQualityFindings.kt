package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.SubmitFindingsResult
import app.epistola.suite.quality.SubmittedFinding
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Submits a source's **full current finding set** for one subject, reconciling the ledger against it.
 *
 * This is the single entry point for every source — an in-process [app.epistola.suite.quality.QualityFindingSource]
 * run by the framework, a remote checker pushing over REST, or a person raising a review note. That
 * is deliberate: reconciliation, ignores and staleness then behave identically no matter where a
 * check ran.
 *
 * ### Reconciliation
 *
 * [findings] is a complete set, not a delta:
 *  - a fingerprint not seen before is **opened**;
 *  - a fingerprint already open is **refreshed** (display fields may change without the problem
 *    materially changing) and keeps its `first_seen_at` and its id;
 *  - a fingerprint previously resolved is **reopened** — reusing its original row, so its comments
 *    survive the resolve/resurface cycle;
 *  - anything this source previously reported for this subject and now omits is **resolved**.
 *
 * That last rule is what gives resolution-on-fix for free: an author fixes the problem, the source
 * stops reporting it, and it closes with nobody clicking anything. An **empty** submission is
 * therefore meaningful and legal — it resolves everything this source had open for the subject.
 *
 * Reconciliation is scoped by `source_id`, so a source can never resolve another's findings, nor a
 * human's ([QualitySourceId.MANUAL] is never reconciled — see [RecordManualFinding]).
 *
 * Ignores are deliberately **not** touched here: they live in their own table keyed by fingerprint,
 * so an unchanged fingerprint keeps its ignore mechanically, with no code in this handler.
 *
 * ### Authorization
 *
 * Requires [Permission.TEMPLATE_EDIT] rather than being `SystemInternal`, so that the REST ingest
 * authorizes like any other caller instead of bypassing authorization entirely. Background callers
 * bind `SystemUser.principalForTenant(tenantKey)`.
 *
 * `requireCatalogEditable` is deliberately **not** called: a finding is metadata *about* content,
 * not content. A read-only system catalog can still have problems worth reporting, and reporting one
 * does not edit the catalog.
 */
data class SubmitQualityFindings(
    val sourceId: QualitySourceId,
    val subject: QualitySubject,
    val findings: List<SubmittedFinding>,
) : Command<SubmitFindingsResult>,
    RequiresPermission {
    init {
        require(sourceId != QualitySourceId.MANUAL) {
            "manual findings are not reconcilable — use RecordManualFinding, which does not auto-resolve"
        }
        val duplicates = findings.groupBy { it.fingerprint }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "a submission must contain each fingerprint at most once (the ledger reconciles on it); duplicates: $duplicates"
        }
    }

    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = subject.tenantKey
}

@Component
class SubmitQualityFindingsHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<SubmitQualityFindings, SubmitFindingsResult> {
    override fun handle(command: SubmitQualityFindings): SubmitFindingsResult {
        val now = EpistolaClock.instant()

        // The whole reconcile runs in one transaction (SpringMediator gives commands one), so a
        // reader never sees the intermediate state where a fixed finding is still open.
        return jdbi.inTransaction<SubmitFindingsResult, Exception> { handle ->
            val inputFingerprint = currentInputFingerprint(handle, command.subject)

            // Which of the submitted fingerprints already exist, and in what state. Read before the
            // upsert so we can report opened/reopened/unchanged honestly.
            val existing: Map<String, String> = if (command.findings.isEmpty()) {
                emptyMap()
            } else {
                handle.createQuery(
                    """
                    SELECT fingerprint, status
                    FROM quality_findings
                    WHERE tenant_key = :tenantKey AND source_id = :sourceId AND subject_urn = :subjectUrn
                      AND fingerprint = ANY(:fingerprints)
                    """,
                )
                    .bind("tenantKey", command.subject.tenantKey)
                    .bind("sourceId", command.sourceId.value)
                    .bind("subjectUrn", command.subject.urn)
                    .bindArray("fingerprints", String::class.java, command.findings.map { it.fingerprint })
                    .map { rs, _ -> rs.getString("fingerprint") to rs.getString("status") }
                    .list()
                    .toMap()
            }

            for (finding in command.findings) {
                upsert(handle, command, finding, inputFingerprint, now)
            }

            val resolved = resolveAbsent(handle, command, now)

            SubmitFindingsResult(
                opened = command.findings.count { it.fingerprint !in existing },
                reopened = command.findings.count { existing[it.fingerprint] == "RESOLVED" },
                unchanged = command.findings.count { existing[it.fingerprint] == "OPEN" },
                resolved = resolved,
            )
        }
    }

    /**
     * Hashes the template model this subject currently resolves to (draft first, else newest
     * published) — the editor compares it against a finding's stored value to mark it outdated.
     *
     * Computed **here**, never taken from the source. Postgres normalizes `jsonb` key order, so
     * `md5(template_model::text)` is stable for a given stored document; a remote source hashing the
     * JSON *it* fetched would produce a different string for the very same document, and every
     * finding it submitted would read as permanently stale — silently, forever.
     *
     * Null for a subject with no template model (a contract-version subject, or a template with no
     * versions yet); staleness then simply never fires, which is the right answer for a finding that
     * isn't about a document.
     */
    private fun currentInputFingerprint(
        handle: org.jdbi.v3.core.Handle,
        subject: QualitySubject,
    ): String? {
        val variantKey = subject.variantKey ?: return null
        return handle.createQuery(
            """
            SELECT md5(template_model::text)
            FROM template_versions
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
              AND template_key = :templateKey AND variant_key = :variantKey
              AND status IN ('draft', 'published')
            ORDER BY CASE status WHEN 'draft' THEN 0 ELSE 1 END, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", subject.tenantKey)
            .bind("catalogKey", subject.catalogKey)
            .bind("templateKey", subject.templateKey)
            .bind("variantKey", variantKey)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }

    private fun upsert(
        handle: org.jdbi.v3.core.Handle,
        command: SubmitQualityFindings,
        finding: SubmittedFinding,
        inputFingerprint: String?,
        now: Instant,
    ) {
        handle.createUpdate(
            """
            INSERT INTO quality_findings (
                tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type, ignore_scope_urn,
                catalog_key, template_key, variant_key, version_key, node_id, path, message, docs_url,
                fingerprint, input_fingerprint, context, status, first_seen_at, last_seen_at, resolved_at
            ) VALUES (
                :tenantKey, :id, :sourceId, :ruleId, :severity, :subjectUrn, :subjectType, :ignoreScopeUrn,
                :catalogKey, :templateKey, :variantKey, :versionKey, :nodeId, :path, :message, :docsUrl,
                :fingerprint, :inputFingerprint, :context::jsonb, 'OPEN', :now, :now, NULL
            )
            ON CONFLICT (tenant_key, source_id, subject_urn, fingerprint) DO UPDATE SET
                -- Display fields may change without the problem materially changing (a reworded
                -- message, a raised severity), so they always take the newest submission.
                severity          = EXCLUDED.severity,
                message           = EXCLUDED.message,
                docs_url          = EXCLUDED.docs_url,
                node_id           = EXCLUDED.node_id,
                path              = EXCLUDED.path,
                context           = EXCLUDED.context,
                input_fingerprint = EXCLUDED.input_fingerprint,
                -- Reopen: a fingerprint reported again is open again, on its ORIGINAL row, so its
                -- comments survive the resolve/resurface cycle.
                status            = 'OPEN',
                resolved_at       = NULL,
                last_seen_at      = EXCLUDED.last_seen_at
                -- first_seen_at and id are deliberately never updated.
            """,
        )
            .bind("tenantKey", command.subject.tenantKey)
            .bind("id", QualityFindingKey.generate().value)
            .bind("sourceId", command.sourceId.value)
            .bind("ruleId", finding.ruleId)
            .bind("severity", finding.severity.name)
            .bind("subjectUrn", command.subject.urn)
            .bind("subjectType", command.subject.type.name)
            .bind("ignoreScopeUrn", command.subject.ignoreScopeUrn)
            .bind("catalogKey", command.subject.catalogKey)
            .bind("templateKey", command.subject.templateKey)
            .bind("variantKey", command.subject.variantKey)
            .bind("versionKey", command.subject.versionKey)
            .bind("nodeId", finding.nodeId)
            .bind("path", finding.path)
            .bind("message", finding.message)
            .bind("docsUrl", finding.docsUrl)
            .bind("fingerprint", finding.fingerprint)
            .bind("inputFingerprint", inputFingerprint)
            .bind("context", objectMapper.writeValueAsString(finding.context))
            .bind("now", now)
            .execute()
    }

    /**
     * Resolves everything this source previously reported for this subject and did not report now.
     *
     * `fingerprint <> ALL('{}')` is TRUE in Postgres, so an empty submission correctly resolves every
     * open finding for the (source, subject) with no special case — a source reporting "nothing wrong
     * any more" and a source reporting a smaller set take exactly the same path.
     */
    private fun resolveAbsent(
        handle: org.jdbi.v3.core.Handle,
        command: SubmitQualityFindings,
        now: Instant,
    ): Int = handle.createUpdate(
        """
        UPDATE quality_findings
        SET status = 'RESOLVED', resolved_at = :now
        WHERE tenant_key = :tenantKey
          AND source_id = :sourceId
          AND subject_urn = :subjectUrn
          AND status = 'OPEN'
          AND fingerprint <> ALL(:submitted)
        """,
    )
        .bind("tenantKey", command.subject.tenantKey)
        .bind("sourceId", command.sourceId.value)
        .bind("subjectUrn", command.subject.urn)
        .bindArray("submitted", String::class.java, command.findings.map { it.fingerprint })
        .bind("now", now)
        .execute()
}
