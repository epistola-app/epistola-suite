package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import app.epistola.suite.templates.contracts.model.ContractVersion
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.templates.validation.ValidationError
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * Replays the input data of recent generations against the current **draft** contract to find
 * whether a destructive schema change would have broken real, recent usage (issue #280).
 *
 * This complements [CheckContractPublishImpact][app.epistola.suite.templates.contracts.queries.CheckContractPublishImpact],
 * which checks the *fields a template references*. That cannot see, for example, a newly-required
 * field the template never references but callers must now supply — only replaying the actual
 * persisted payloads reveals it.
 *
 * Only **regressions** are reported: payloads that were valid under the currently-published
 * contract but fail the draft. Pre-existing invalid data is not blamed on this change. To keep
 * the validator work bounded, payloads are deduplicated by [RecentUsageShapeProjector] onto the
 * paths the schema diff actually touched before a representative of each distinct shape is
 * validated.
 *
 * The result reports counts and field paths only — never a payload value (the persisted data is
 * raw caller PII).
 *
 * @property templateId Template whose recent usage to check.
 * @property window How far back to look. Bounded in practice by generation-request partition
 *   retention, so usage older than that is not visible.
 * @property sampleLimit Maximum number of recent payloads to scan; if more exist within the
 *   window the result is flagged [RecentUsageImpact.capped].
 * @property candidateSchema The prospective new data model to test recent usage against. When
 *   null (the default), the current draft contract's data model is used — this is the on-demand
 *   UI case. Callers that detect a breaking change from an unsaved candidate (e.g. the REST
 *   `updateTemplate` dry-run, where no draft is persisted yet) pass the candidate directly.
 */
data class CheckRecentUsageCompatibility(
    val templateId: TemplateId,
    val window: Duration = Duration.ofDays(30),
    val sampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
    val candidateSchema: ObjectNode? = null,
) : Query<RecentUsageImpact>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = templateId.tenantKey

    companion object {
        const val DEFAULT_SAMPLE_LIMIT = 5000
    }
}

/**
 * Outcome of a recent-usage compatibility check.
 *
 * @property applicable Whether a check was performed at all. False when there is no draft, no
 *   published baseline, or the draft introduces no destructive change — in those cases nothing
 *   recent could regress and all counts are zero.
 * @property windowDays The look-back window actually used, in days.
 * @property sampledDocuments Number of recent payloads examined (after applying [capped]).
 * @property distinctShapes Number of distinct payload shapes among the sampled documents.
 * @property failingShapes Number of distinct shapes that regress under the draft.
 * @property failingDocuments Number of sampled documents that regress under the draft.
 * @property capped True when the sample hit [CheckRecentUsageCompatibility.sampleLimit] and older
 *   in-window usage went unchecked.
 * @property fields Per-field breakdown of what broke, most-affected first. Paths only, no values.
 */
data class RecentUsageImpact(
    val applicable: Boolean,
    val windowDays: Long,
    val sampledDocuments: Int,
    val distinctShapes: Int,
    val failingShapes: Int,
    val failingDocuments: Int,
    val capped: Boolean,
    val fields: List<UsageFieldImpact>,
) {
    /** No recent usage would break under the draft. */
    val compatible: Boolean get() = failingDocuments == 0

    companion object {
        fun notApplicable(windowDays: Long) = RecentUsageImpact(
            applicable = false,
            windowDays = windowDays,
            sampledDocuments = 0,
            distinctShapes = 0,
            failingShapes = 0,
            failingDocuments = 0,
            capped = false,
            fields = emptyList(),
        )
    }
}

/**
 * @property path Dotted schema path that broke (e.g. `customer.taxId`, `items[].sku`).
 * @property reason Human-readable, schema-derived explanation (never a payload value).
 * @property failingDocuments How many sampled documents broke at this path.
 */
data class UsageFieldImpact(
    val path: String,
    val reason: String,
    val failingDocuments: Int,
)

@Component
class CheckRecentUsageCompatibilityHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val schemaValidator: JsonSchemaValidator,
) : QueryHandler<CheckRecentUsageCompatibility, RecentUsageImpact> {

    private val checker = SchemaCompatibilityChecker()
    private val projector = RecentUsageShapeProjector()

    override fun handle(query: CheckRecentUsageCompatibility): RecentUsageImpact {
        val windowDays = query.window.toDays()

        // Use the caller-supplied candidate when present (e.g. the REST dry-run, where no draft is
        // persisted); otherwise fall back to the current draft. Either being absent, or the schema
        // being removed entirely, means nothing can regress.
        val newSchema = query.candidateSchema
            ?: loadContract(query.templateId, "draft")?.dataModel
            ?: return RecentUsageImpact.notApplicable(windowDays)
        // No published baseline → no notion of "was valid before", so no regression to report.
        val oldSchema = loadContract(query.templateId, "published")?.dataModel
            ?: return RecentUsageImpact.notApplicable(windowDays)

        val diff = checker.checkCompatibility(oldSchema, newSchema)
        if (diff.compatible) {
            // The draft only widens what is accepted; no valid-under-old payload can fail.
            return RecentUsageImpact.notApplicable(windowDays)
        }
        val affectedPaths = diff.breakingChanges.map { it.path }.filter { it.isNotEmpty() }.distinct()

        // Over-fetch by one so we can tell whether older in-window usage was left unchecked.
        val payloads = loadRecentPayloads(query.templateId, query.window, query.sampleLimit + 1)
        val capped = payloads.size > query.sampleLimit
        val sample = if (capped) payloads.take(query.sampleLimit) else payloads

        if (sample.isEmpty()) {
            return RecentUsageImpact(
                applicable = true,
                windowDays = windowDays,
                sampledDocuments = 0,
                distinctShapes = 0,
                failingShapes = 0,
                failingDocuments = 0,
                capped = capped,
                fields = emptyList(),
            )
        }

        // Deduplicate by the values at the affected paths: two payloads that agree there validate
        // identically, so only one representative per shape needs the validator.
        val shapeCounts = LinkedHashMap<String, Int>()
        val representatives = HashMap<String, ObjectNode>()
        for (payload in sample) {
            val shapeKey = projector.key(payload, affectedPaths)
            shapeCounts.merge(shapeKey, 1, Int::plus)
            representatives.putIfAbsent(shapeKey, payload)
        }

        val breakingDescriptions = diff.breakingChanges.associate { it.path to it.description }
        val fieldFailingDocuments = LinkedHashMap<String, Int>()
        var failingShapes = 0
        var failingDocuments = 0

        for ((shapeKey, count) in shapeCounts) {
            val representative = representatives.getValue(shapeKey)
            // Regressions only: skip data that was already invalid under the published contract.
            if (schemaValidator.validate(oldSchema, representative).isNotEmpty()) continue

            val errors = schemaValidator.validate(newSchema, representative)
            if (errors.isEmpty()) continue

            failingShapes++
            failingDocuments += count
            for (path in errors.flatMap(::errorFieldPaths).distinct()) {
                fieldFailingDocuments.merge(path, count, Int::plus)
            }
        }

        val fields = fieldFailingDocuments.entries
            .sortedByDescending { it.value }
            .map { (path, docs) ->
                UsageFieldImpact(
                    path = path,
                    reason = breakingDescriptions[path] ?: "No longer valid under the new schema",
                    failingDocuments = docs,
                )
            }

        return RecentUsageImpact(
            applicable = true,
            windowDays = windowDays,
            sampledDocuments = sample.size,
            distinctShapes = shapeCounts.size,
            failingShapes = failingShapes,
            failingDocuments = failingDocuments,
            capped = capped,
            fields = fields,
        )
    }

    private fun loadContract(templateId: TemplateId, status: String): ContractVersion? = jdbi.withHandle<ContractVersion?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                       status, created_at, published_at, created_by
                FROM contract_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey AND status = :status
                ORDER BY id DESC
                LIMIT 1
                """,
        )
            .bind("tenantKey", templateId.tenantKey)
            .bind("catalogKey", templateId.catalogKey)
            .bind("templateKey", templateId.key)
            .bind("status", status)
            .mapTo<ContractVersion>()
            .findOne()
            .orElse(null)
    }

    private fun loadRecentPayloads(templateId: TemplateId, window: Duration, limit: Int): List<ObjectNode> = jdbi.withHandle<List<ObjectNode>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT data
                FROM document_generation_requests
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                  AND template_key = :templateKey
                  AND status = 'COMPLETED'
                  AND created_at >= now() - :window::interval
                ORDER BY created_at DESC
                LIMIT :limit
                """,
        )
            .bind("tenantKey", templateId.tenantKey)
            .bind("catalogKey", templateId.catalogKey)
            .bind("templateKey", templateId.key)
            .bind("window", "${window.toSeconds()} seconds")
            .bind("limit", limit)
            .map { rs, _ -> objectMapper.readTree(rs.getString("data")) as ObjectNode }
            .list()
    }

    /**
     * Maps a validator error to the dotted schema path it concerns, matching the form used by
     * [SchemaCompatibilityChecker] (`customer.taxId`, `items[].sku`).
     *
     * networknt reports the failing value's *instance location* as a JSON Pointer (`/items/0/sku`,
     * empty at the root); for a missing required property that location is the containing object and
     * the property name lives in the message, so we recover it and append it to line the path up
     * with the corresponding breaking change.
     */
    private fun errorFieldPaths(error: ValidationError): List<String> {
        val base = pointerToDottedPath(error.path)
        val missing = REQUIRED_PROPERTY.find(error.message)?.groupValues?.get(1)
        return if (missing != null) {
            listOf(if (base.isEmpty()) missing else "$base.$missing")
        } else {
            listOf(base)
        }
    }

    /** `/items/0/sku` → `items[].sku`; `/status` → `status`; `` → ``. */
    private fun pointerToDottedPath(instanceLocation: String): String {
        val segments = instanceLocation.trim('/').split('/').filter(String::isNotEmpty)
        val builder = StringBuilder()
        for (segment in segments) {
            if (segment.all(Char::isDigit)) {
                builder.append("[]")
            } else {
                if (builder.isNotEmpty()) builder.append('.')
                builder.append(segment)
            }
        }
        return builder.toString()
    }

    private companion object {
        val REQUIRED_PROPERTY = Regex("""required property '([^']+)'""")
    }
}
