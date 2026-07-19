package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ContractVersionId
import app.epistola.suite.common.ids.EntityIdBase
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * Identifies who reported a finding. Free-form so a remote checker can name itself without a code
 * change here; [MANUAL] is the one reserved value.
 */
@JvmInline
value class QualitySourceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "sourceId must not be blank" }
        require(value.length <= 64) { "sourceId must be at most 64 characters, got ${value.length}" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Findings raised by a person, not a check. Reserved: no [QualityFindingSource] may claim it,
         * and reconciliation never touches it — a human's note is not something an automated
         * submission gets to resolve away.
         */
        val MANUAL = QualitySourceId("manual")
    }
}

/**
 * How bad a finding is. The **known** set — not a closed one.
 *
 * The column carries no CHECK, deliberately: the ledger only renders and sorts by severity and
 * never interprets it, so pinning the vocabulary in the schema would make a fourth level (a HINT,
 * a CRITICAL) a migration — the same reasoning that keeps rules out of a local catalog table.
 *
 * Nothing can write an unknown value *today* — [SubmittedFinding.severity] is this type, so an
 * in-process source cannot express one. The REST ingest is where one could first arrive, and
 * [parse] exists so that lands as a rendering question rather than a 500.
 */
enum class QualitySeverity {
    INFO,
    WARNING,
    ERROR,
    ;

    companion object {
        /**
         * Reads a stored severity, tolerating one this suite does not know.
         *
         * A read model that throws on an unexpected value takes out the whole page rather than the
         * one row that carries it — the same reason `readNodeIds` tolerates a shape it did not
         * expect. [WARNING] is the fallback because it is the honest middle: [INFO] would hide a
         * finding a source thought urgent, [ERROR] would cry wolf about one it did not.
         *
         * This coerces, and coercion loses the source's own word for it. When a remote source can
         * genuinely define severities, this is the seam that becomes an open value class (as
         * `AssetMediaType` already is) rather than a wider enum.
         */
        fun parse(value: String): QualitySeverity = entries.find { it.name == value } ?: WARNING
    }
}

/** What kind of thing a finding is about. Mirrors the entity types a subject URN can name. */
enum class QualitySubjectType { TEMPLATE, VARIANT, VERSION, CONTRACT_VERSION }

/** OPEN or RESOLVED as persisted; see [EffectiveQualityStatus] for what a reader actually sees. */
enum class QualityStatus { OPEN, RESOLVED }

/**
 * What a reader sees. `IGNORED` is deliberately **not** a persisted status — it is derived from a
 * live ignore row at read time, which is what keeps "an unchanged fingerprint retains its ignore"
 * true by construction across a resolve/resurface cycle.
 */
enum class EffectiveQualityStatus { OPEN, IGNORED, RESOLVED }

/**
 * What a source analysed, and where its findings hang.
 *
 * [urn] identifies the analysed thing precisely (a specific version, say). [ignoreScopeUrn] is
 * deliberately **coarser** — the owning template — because an ignore must carry forward across
 * versions instead of being re-applied after every publish.
 *
 * v1 constrains subjects to the template family, so [catalogKey] and [templateKey] are always
 * present. A future tenant-scoped subject (e.g. a compatibility verdict, which is per
 * `(targetVersion, catalog)` and has no template) will need those relaxed; the URN-shaped ignore
 * scope is already general enough to carry it.
 */
data class QualitySubject(
    val type: QualitySubjectType,
    val urn: String,
    val ignoreScopeUrn: String,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    val variantKey: String? = null,
    val versionKey: Int? = null,
) {
    companion object {
        fun of(templateId: TemplateId) = QualitySubject(
            type = QualitySubjectType.TEMPLATE,
            urn = templateId.toUrn(),
            ignoreScopeUrn = templateId.toUrn(),
            tenantKey = templateId.tenantKey,
            catalogKey = templateId.catalogKey,
            templateKey = templateId.key,
        )

        fun of(variantId: VariantId) = QualitySubject(
            type = QualitySubjectType.VARIANT,
            urn = variantId.toUrn(),
            ignoreScopeUrn = variantId.templateId.toUrn(),
            tenantKey = variantId.tenantKey,
            catalogKey = variantId.catalogKey,
            templateKey = variantId.templateKey,
            variantKey = variantId.key.value,
        )

        fun of(versionId: VersionId) = QualitySubject(
            type = QualitySubjectType.VERSION,
            urn = versionId.toUrn(),
            ignoreScopeUrn = versionId.variantId.templateId.toUrn(),
            tenantKey = versionId.tenantKey,
            catalogKey = versionId.catalogKey,
            templateKey = versionId.templateKey,
            variantKey = versionId.variantKey.value,
            versionKey = versionId.key.value,
        )

        fun of(contractVersionId: ContractVersionId) = QualitySubject(
            type = QualitySubjectType.CONTRACT_VERSION,
            urn = contractVersionId.toUrn(),
            ignoreScopeUrn = contractVersionId.templateId.toUrn(),
            tenantKey = contractVersionId.tenantKey,
            catalogKey = contractVersionId.catalogKey,
            templateKey = contractVersionId.templateKey,
            versionKey = contractVersionId.key.value,
        )
    }
}

/**
 * A finding as a source reports it. Self-describing: there is no local rule catalog, so everything a
 * reader needs to render this — the message, the severity, a docs link — travels with the finding.
 * That lets a remote source add or reword rules without a suite release.
 */
data class SubmittedFinding(
    val ruleId: String,
    val severity: QualitySeverity,
    /**
     * Identity of the *problem*, computed by the source and **opaque** to the ledger.
     *
     * The contract, which auto-resolve and ignore-carry-forward both rest on:
     *  - **stable** across re-runs while the same problem is present, and
     *  - **different** once the problem materially changes.
     *
     * Too volatile (e.g. hashing the whole document) and every edit resurfaces every ignore. Too
     * stable (e.g. just the ruleId) and an ignore silently swallows a genuinely different problem.
     * A reasonable recipe is `sha256(ruleId | subjectUrn | nodeId ?: path | normalized-evidence)`.
     */
    val fingerprint: String,
    val message: String,
    /**
     * Stable i18n key for [message], letting the ledger re-render it in another locale later
     * without re-running the source. The interpolation params ride in [context]; [message] is the
     * default-locale fallback shown when nothing translates the code.
     *
     * Distinct from [ruleId]: the rule that fired versus the message template to render. Often the
     * same, but a rule that emits materially different wording per case wants distinct codes. Null
     * when a source does not localize — most do not, yet.
     */
    val messageCode: String? = null,
    /**
     * The editor nodes this finding is about — each gets a marker on the canvas and in the
     * structure tree, and the first is where "go to" navigates.
     *
     * A **list**, because a finding is often genuinely about several elements at once: two
     * paragraphs that contradict each other, a set of blocks that disagree on a date format, a
     * heading inconsistent with its sibling. Emitting one finding per node would split one problem
     * into several that a reader has to reassemble — and worse, each would have to be ignored
     * separately and could resolve independently while the actual problem persisted.
     *
     * Order is the source's own order of relevance; put the node an author should look at first, first.
     *
     * Empty is fine and means the finding is not about any particular element — a document-level or
     * data-level observation. Use [path] for those when there is a data location to point at.
     */
    val nodeIds: List<String> = emptyList(),
    /** A data/JSON path, when the finding is about the data rather than an element. */
    val path: String? = null,
    val docsUrl: String? = null,
    /**
     * Structured **evidence** for the reader (e.g. `{"length": 142}`), shown under "Evidence", and
     * the interpolation params behind [messageCode]. Never interpreted by the ledger.
     */
    val context: ObjectNode = JsonNodeFactory.instance.objectNode(),
    /**
     * **Operational** data that is never shown to the reader — a checker's version or trace id, the
     * suite version behind a PDF finding. The opposite of [context]: context explains the finding
     * to a person, metadata is for machines and debugging. Never interpreted by the ledger.
     */
    val metadata: ObjectNode = JsonNodeFactory.instance.objectNode(),
) {
    init {
        require(ruleId.isNotBlank()) { "ruleId must not be blank" }
        require(messageCode == null || messageCode.isNotBlank()) { "messageCode, when present, must not be blank" }
        require(fingerprint.isNotBlank()) { "fingerprint must not be blank" }
        require(message.isNotBlank()) { "message must not be blank" }
        require(nodeIds.none { it.isBlank() }) { "nodeIds must not contain blank entries" }
        require(nodeIds.size == nodeIds.distinct().size) { "nodeIds must not repeat: $nodeIds" }
    }

    /** The node an author should be taken to first, if any. */
    val primaryNodeId: String? get() = nodeIds.firstOrNull()
}

/**
 * A finding as read back: what the source submitted, plus the ledger's own bookkeeping.
 */
data class QualityFinding(
    val key: QualityFindingKey,
    val sourceId: QualitySourceId,
    val ruleId: String,
    val severity: QualitySeverity,
    val subjectUrn: String,
    val subjectType: QualitySubjectType,
    val ignoreScopeUrn: String,
    val catalogKey: CatalogKey,
    val templateKey: TemplateKey,
    val variantKey: String?,
    val versionKey: Int?,
    /** The elements this finding marks in the editor; the first is the navigation target. */
    val nodeIds: List<String>,
    val path: String?,
    val message: String,
    /** The i18n key for [message]; null when the source did not localize. */
    val messageCode: String?,
    val docsUrl: String?,
    val fingerprint: String,
    val inputFingerprint: String?,
    /** Evidence, shown to the reader. */
    val context: ObjectNode,
    /** Operational data, never shown to the reader. */
    val metadata: ObjectNode,
    val effectiveStatus: EffectiveQualityStatus,
    /** Why it was ignored, when it is. */
    val ignoreReason: String?,
    /**
     * Whether this finding's source reconciles — i.e. whether it can auto-resolve when fixed.
     *
     * False only for [QualitySourceId.MANUAL]: a human's note is never resolved by a submission, so
     * the UI must offer an explicit Resolve action for it. This is the one place the ledger's two
     * lifecycles are visible, and it is surfaced rather than hidden so callers can't mistake a
     * manual finding for one that will clear itself.
     */
    val reconciled: Boolean,
    val commentCount: Int,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val resolvedAt: Instant?,
) {
    /** The node an author should be taken to first, if any. */
    val primaryNodeId: String? get() = nodeIds.firstOrNull()

    /** True when this finding marks [nodeId] in the editor. */
    fun marks(nodeId: String): Boolean = nodeId in nodeIds
}

/**
 * A human's disposition of a finding, as a source reads it back.
 *
 * [ignored] is false for a *revoked* ignore. That matters: sources poll with a `since` cursor, so a
 * revocation has to be an observable event. A hard delete would simply vanish from the feed and the
 * source would go on suppressing the finding forever.
 */
data class FindingDisposition(
    val sourceId: QualitySourceId,
    val ruleId: String,
    val fingerprint: String,
    val ignoreScopeUrn: String,
    val ignored: Boolean,
    val reason: String?,
    val changedAt: Instant,
)

/** One page of findings plus the total matching the same filters, from one `COUNT(*) OVER()`. */
data class QualityFindingPage(
    val items: List<QualityFinding>,
    val total: Int,
)

/** Outcome of a reconciling submission — what actually changed, for logging and the "Check now" UI. */
data class SubmitFindingsResult(
    val opened: Int,
    val reopened: Int,
    val resolved: Int,
    val unchanged: Int,
)

/** Convenience: the URN of any entity, for callers building a [QualitySubject] by hand. */
fun EntityIdBase.qualityUrn(): String = toUrn()
