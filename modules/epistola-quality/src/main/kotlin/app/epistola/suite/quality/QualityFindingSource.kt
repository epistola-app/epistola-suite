// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.catalog.protocol.FontRef
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateDocument
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Coarse input families a source can ask the runner to prepare.
 *
 * Keep these as capability families, not one enum entry per eventual fact. A requirement can grow
 * new fields inside its facet when a source actually needs them.
 */
enum class QualityDataRequirement {
    /** References discovered in the checked template, resolved against current tenant/catalog state. */
    RESOLVED_TEMPLATE_DEPENDENCIES,
}

/**
 * Resolved facts about resources the checked template references.
 *
 * This starts with fonts because the unresolved-font source is the first concrete DB-backed check.
 * Assets, themes, stencils, code lists, and similar dependencies belong here when a source needs
 * them; the facet is about dependencies in this one subject, not all tenant resources.
 */
data class ResolvedTemplateDependencies(
    val fonts: Map<FontRef, Boolean> = emptyMap(),
) {
    companion object {
        val EMPTY = ResolvedTemplateDependencies()
    }
}

/**
 * Everything an in-process source is handed for one subject. Assembled once by the caller so a
 * source never touches JDBI — a source is a pure function from this to a list of findings, which is
 * what makes it unit-testable with a plain fixture and no Spring context.
 *
 * ### Pre-resolved facts
 *
 * Some checks need data the ledger keeps in the database (whether referenced resources resolve, an
 * asset's dimensions, a stencil's latest version). A source cannot read it — it is pure by contract
 * — so the **caller** resolves the requested facets while assembling this input and hands the answer
 * in. Add fields to a facet only when a source actually needs them; two of the early sources
 * ([sources.ExampleQualitySource], the accessibility one) needed nothing but the model, and
 * speculative widening is what this note exists to discourage.
 */
data class QualityCheckInput(
    val subject: QualitySubject,
    val templateModel: TemplateDocument,
    /**
     * The template's example data. Quality checks analyse **only** this — never real user data.
     * Empty when the template has no contract or no examples yet.
     */
    val dataExamples: List<DataExample>,
    /** The contract's JSON-Schema data model, when the template has one. */
    val dataModel: ObjectNode?,
    /** Resolved dependency facts requested through [QualityDataRequirement.RESOLVED_TEMPLATE_DEPENDENCIES]. */
    val dependencies: ResolvedTemplateDependencies = ResolvedTemplateDependencies.EMPTY,
)

/**
 * SPI for a quality check that runs **in-process**.
 *
 * Implement this and the framework will run you after a version is published and when an author
 * hits "Check now" in the editor. Being a bean *is* the "runs locally" flag — there is deliberately
 * no capability flag to keep in sync.
 *
 * A source that executes **remotely** implements nothing. It pushes its finding set to the ledger on
 * its own schedule and reads dispositions back with a `since` cursor. Both paths converge on
 * `SubmitQualityFindings`, so reconciliation, ignores and staleness behave identically either way.
 *
 * There is deliberately no `fetch()`/`sync()` here: a local source is *called* (the framework owns
 * its scheduling) and a remote source *pushes* (it owns its own). Neither needs the framework to go
 * fetch from it, and a `fetch()` would invite fan-out-on-read — the thing the ledger exists to avoid.
 *
 * Collected Spring-style, mirroring `NavContributor`.
 *
 * ### Contract
 *
 * [check] returns the **full current finding set** for the subject — not a delta. Anything a source
 * previously reported and now omits is auto-resolved. Implementations must therefore be
 * deterministic for a given [QualityCheckInput], and must not throw for an ordinary "nothing wrong"
 * result (return an empty list; it correctly resolves everything the source had open).
 *
 * See [SubmittedFinding.fingerprint] for the identity contract — it is the part that is easy to get
 * subtly wrong.
 */
interface QualityFindingSource {
    /** Stable identity of this source. Must not be [QualitySourceId.MANUAL]. */
    val sourceId: QualitySourceId

    /** Shown in the report's and the editor panel's source filter. */
    val displayName: String

    /**
     * Input families this source needs beyond the template model and example data. The runner
     * resolves the union for the selected sources before calling [check].
     */
    val requirements: Set<QualityDataRequirement> get() = emptySet()

    /**
     * Whether this source should run for a tenant at all. Default true; override for a source that
     * is licensed, configured per tenant, or dependent on something that may be absent.
     */
    fun isAvailable(tenantKey: TenantKey): Boolean = true

    /** The full current finding set for [input]'s subject. Empty means "nothing wrong here". */
    fun check(input: QualityCheckInput): List<SubmittedFinding>
}

/**
 * Collects every in-process [QualityFindingSource]. Mirrors the `NavContributor` aggregator: the
 * host asks for contributions, the contributors never know about each other.
 *
 * Rejects a source claiming the reserved [QualitySourceId.MANUAL] at startup rather than letting it
 * quietly resolve away humans' review notes — reconciliation is scoped by source id, so a source
 * that successfully claimed `manual` would wipe every manual finding on its first submission.
 */
@Component
class QualitySourceRegistry(
    private val sources: List<QualityFindingSource>,
) {
    init {
        val reserved = sources.filter { it.sourceId == QualitySourceId.MANUAL }
        require(reserved.isEmpty()) {
            "QualitySourceId.MANUAL is reserved for human-raised findings and cannot be claimed by a source: " +
                reserved.joinToString { it::class.java.name }
        }
        val duplicates = sources.groupBy { it.sourceId }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate quality source ids — reconciliation is scoped by source id, so two sources sharing one " +
                "would resolve each other's findings: " +
                duplicates.entries.joinToString { (id, dupes) -> "$id -> ${dupes.joinToString { it::class.java.name }}" }
        }
    }

    fun all(): List<QualityFindingSource> = sources

    fun byId(sourceId: QualitySourceId): QualityFindingSource? = sources.firstOrNull { it.sourceId == sourceId }

    /** The sources that should run for this tenant. */
    fun availableFor(tenantKey: TenantKey): List<QualityFindingSource> = sources.filter { it.isAvailable(tenantKey) }
}
