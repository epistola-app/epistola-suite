package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.HexFormat

/**
 * Raises a finding by hand — a reviewer telling a colleague "this needs to change", against the same
 * subject and in the same list as the automated ones.
 *
 * The ledger does not care whether a finding came from a checker or a person, so a review note gets
 * the ignore flow, the comments, the report filters and the editor's node highlight for free.
 *
 * ### The one asymmetry: manual findings do not reconcile
 *
 * Every automated source submits a *full set*, which is what lets its findings auto-resolve when the
 * problem goes away. A person submits one note and walks away — there is no later submission that
 * would omit it, so there is nothing to auto-resolve against, and reconciliation deliberately skips
 * [QualitySourceId.MANUAL] entirely.
 *
 * That means manual findings need [ResolveManualFinding] and a human to close them. It is a genuine
 * second lifecycle, and rather than hide it the read model exposes
 * [app.epistola.suite.quality.QualityFinding.reconciled] so the UI can offer a Resolve action for
 * exactly these and callers cannot mistake a manual finding for one that will clear itself.
 *
 * `requireCatalogEditable` is deliberately not called: recording a finding is a remark *about*
 * content, not an edit to it. Reviewing a read-only system catalog is a legitimate thing to do.
 */
data class RecordManualFinding(
    val subject: QualitySubject,
    val message: String,
    val severity: QualitySeverity = QualitySeverity.WARNING,
    val ruleId: String = DEFAULT_RULE_ID,
    /**
     * The elements the reviewer is pointing at — plural for the same reason automated findings are:
     * "these two paragraphs say different things" is one remark about two blocks, not two remarks.
     */
    val nodeIds: List<String> = emptyList(),
    val path: String? = null,
) : Command<QualityFindingKey>,
    RequiresPermission {
    init {
        validate("message", message.isNotBlank()) { "A message is required" }
        validate("ruleId", ruleId.length <= MAX_NAME_LENGTH) { "Rule must be $MAX_NAME_LENGTH characters or less" }
        validate("nodeIds", nodeIds.none { it.isBlank() }) { "Node references must not be blank" }
        validate("nodeIds", nodeIds.size == nodeIds.distinct().size) { "Node references must not repeat" }
    }

    override val permission: Permission get() = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = subject.tenantKey

    companion object {
        /** Default rule for an unclassified human remark. */
        const val DEFAULT_RULE_ID = "manual.review"
    }
}

@Component
class RecordManualFindingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordManualFinding, QualityFindingKey> {
    override fun handle(command: RecordManualFinding): QualityFindingKey {
        val key = QualityFindingKey.generate()
        val now = EpistolaClock.instant()

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO quality_findings (
                    tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type, ignore_scope_urn,
                    catalog_key, template_key, variant_key, version_key, node_ids, path, message, message_code, docs_url,
                    fingerprint, input_fingerprint, context, metadata, status, first_seen_at, last_seen_at, resolved_at
                ) VALUES (
                    :tenantKey, :id, :sourceId, :ruleId, :severity, :subjectUrn, :subjectType, :ignoreScopeUrn,
                    :catalogKey, :templateKey, :variantKey, :versionKey, :nodeIds, :path, :message, NULL, NULL,
                    -- message_code NULL: a person's note is prose, not a translatable template.
                    -- metadata '{}': no checker produced it, so there is no operational data to carry.
                    :fingerprint, NULL, '{}'::jsonb, '{}'::jsonb, 'OPEN', :now, :now, NULL
                )
                """,
            )
                .bind("tenantKey", command.subject.tenantKey)
                .bind("id", key.value)
                .bind("sourceId", QualitySourceId.MANUAL.value)
                .bind("ruleId", command.ruleId)
                .bind("severity", command.severity.name)
                .bind("subjectUrn", command.subject.urn)
                .bind("subjectType", command.subject.type.name)
                .bind("ignoreScopeUrn", command.subject.ignoreScopeUrn)
                .bind("catalogKey", command.subject.catalogKey)
                .bind("templateKey", command.subject.templateKey)
                .bind("variantKey", command.subject.variantKey)
                .bind("versionKey", command.subject.versionKey)
                .bindArray("nodeIds", String::class.java, command.nodeIds)
                .bind("path", command.path)
                .bind("message", command.message)
                // Random, not derived from the content: two reviewers raising the same concern are
                // two notes, not one. Derived fingerprints exist so a checker can recognise its own
                // finding on the next run — a person never re-submits, so there is nothing to
                // recognise, and collapsing two people's remarks into one row would lose one of them.
                .bind("fingerprint", randomFingerprint())
                // input_fingerprint stays null: a human's remark is not "computed against" a
                // document revision, so it must never be marked outdated when the draft changes.
                .bind("now", now)
                .execute()
        }

        return key
    }

    private fun randomFingerprint(): String = HexFormat.of().formatHex(ByteArray(16).also { RANDOM.nextBytes(it) })

    private companion object {
        private val RANDOM = SecureRandom()
    }
}

/**
 * Closes a manual finding. The automated half of the ledger never needs this — a checker's finding
 * closes when the checker stops reporting it — so this is guarded to [QualitySourceId.MANUAL] rather
 * than offering a way to hand-resolve something a source will simply reopen on its next run.
 */
data class ResolveManualFinding(
    override val tenantKey: TenantKey,
    val findingKey: QualityFindingKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_EDIT
}

@Component
class ResolveManualFindingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ResolveManualFinding, Boolean> {
    override fun handle(command: ResolveManualFinding): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val updated = handle.createUpdate(
            """
            UPDATE quality_findings
            SET status = 'RESOLVED', resolved_at = :now
            WHERE tenant_key = :tenantKey AND id = :findingKey
              AND source_id = :manualSourceId
              AND status = 'OPEN'
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("findingKey", command.findingKey.value)
            .bind("manualSourceId", QualitySourceId.MANUAL.value)
            .bind("now", EpistolaClock.instant())
            .execute()
        updated > 0
    }
}
