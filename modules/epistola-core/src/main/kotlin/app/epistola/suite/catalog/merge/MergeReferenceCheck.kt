// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Before a merge writes anything, and again once it is written, before it commits: every reference
 * the draft's content holds resolves (ADR 0032). Within the catalog to a resource the result has; into another catalog through
 * the dependency the result declares on it, to the release that declaration pins (never that
 * catalog's next release) or the draft it names; and the catalog's default theme the same way. A
 * reference into a catalog the result does not declare is refused.
 *
 * Only what the merge broke is refused: a reference that did not resolve in the draft before the
 * merge either is the draft's own, and releasing it refuses it. Declarations nothing refers to any
 * more are kept, as editing keeps them ([app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer]
 * declares a catalog when a write starts using it and never removes one).
 */
@Component
class MergeReferenceCheck(
    private val jdbi: Jdbi,
    private val references: MergeReferences,
    private val dependencyStore: CatalogDependencyStore,
    private val assembler: ReleaseContentAssembler,
    private val contentBuilder: CatalogContentBuilder,
    private val targeting: CatalogReleaseTargeting,
) {

    /** Why a reference does not resolve. */
    sealed interface Reason {
        /** It points into a catalog the result does not declare. */
        data object Undeclared : Reason

        /** The release the result pins of its catalog does not have it. */
        data class NotInRelease(val version: String) : Reason

        /** The draft of its catalog the result names does not have it. */
        data class NotInDraft(val draftLabel: String) : Reason

        /** It points within the catalog, which does not have it. */
        data object NotInCatalog : Reason
    }

    /** A reference of [source] (an entry, `type/key`) that does not resolve, and why. */
    data class Unresolved(val source: String, val reference: EntryReference, val reason: Reason)

    /**
     * Refuses the merge of [plan] when its result holds a reference that does not resolve and did
     * before. The result is [after] (the draft as written), or, before anything is written, what
     * [actions] make of the draft ([planned]). [actions] also say what to choose instead.
     *
     * @throws MergeRefusedException naming each reference, why it does not resolve, and what to choose.
     */
    fun requireResolved(tenantKey: TenantKey, catalogKey: CatalogKey, plan: MergePlan, actions: Map<String, MergeAction>, after: MergeInput? = null) {
        val lookup = Lookup(tenantKey)
        val result = after?.values ?: planned(plan, actions)
        val before = unresolved(catalogKey, plan.inputs.ours.values, lookup).map { it.source to it.reference }.toSet()
        val broken = unresolved(catalogKey, result, lookup).filter { (it.source to it.reference) !in before }
        if (broken.isEmpty()) return
        val release = "v${plan.inputs.release}"
        throw MergeRefusedException(
            catalogKey,
            "Cannot merge $release into ${plan.inputs.draft.label}: " + broken.joinToString("; ") { sentence(catalogKey, it, release, plan, actions) } + ".",
        )
    }

    /** Every entry of the draft once [actions] are applied: taken from the release, removed, or as the draft has it. */
    private fun planned(plan: MergePlan, actions: Map<String, MergeAction>): Map<String, MergeValue> = plan.entries.mapNotNull { sides ->
        val value = when (val action = actions[sides.contentKey]) {
            is MergeAction.Write -> action.value
            MergeAction.Remove -> null
            else -> sides.ours
        }
        value?.let { sides.contentKey to it }
    }.toMap()

    /** Every reference of [values] (a side's entries, by `type/key`) that does not resolve. */
    private fun unresolved(catalogKey: CatalogKey, values: Map<String, MergeValue>, lookup: Lookup): List<Unresolved> {
        val own = values.filterValues { it is MergeValue.Resource }.keys
        val declared = values.values.mapNotNull { ((it as? MergeValue.Setting)?.setting as? CatalogSetting.Dependency)?.declaration }.associateBy { it.catalogKey }
        return values.entries.sortedBy { it.key }.flatMap { (entry, value) ->
            references.of(value).mapNotNull { reference ->
                val reason = when {
                    !reference.intoOther(catalogKey) -> Reason.NotInCatalog.takeIf { "${reference.type}/${reference.key}" !in own }
                    else -> lookup.reasonFor(reference, declared[reference.catalogKey])
                }
                reason?.let { Unresolved(entry, reference, it) }
            }
        }
    }

    /** Resolves references into other catalogs, reading each release or draft once. */
    private inner class Lookup(private val tenantKey: TenantKey) {
        private val held = mutableMapOf<Pair<String, String>, Set<String>>()

        /** Why [reference] does not resolve through [declaration], or null when it does (or cannot be told yet). */
        fun reasonFor(reference: EntryReference, declaration: CatalogDependencyDeclaration?): Reason? {
            if (declaration == null) return Reason.Undeclared
            val other = CatalogKey.of(declaration.catalogKey)
            val wanted = "${reference.type}/${reference.key}"
            val draftKey = declaration.draftId
            val version = declaration.releaseVersion
            return when {
                draftKey != null -> {
                    val draft = jdbi.withHandle<Pair<java.util.UUID, String>?, Exception> { dependencyStore.draftOf(it, tenantKey, declaration, draftKey) } ?: return null
                    val keys = held.getOrPut("draft" to draftKey) { targeting.within(draft.first) { contentBuilder.build(tenantKey, other).resourceDetails.keys } }
                    Reason.NotInDraft(draft.second).takeIf { wanted !in keys }
                }

                version != null -> {
                    val keys = held.getOrPut("${other.value}@" to version) { assembler.assemble(tenantKey, other, version)?.content?.resourceDetails?.keys.orEmpty() }
                    Reason.NotInRelease(version).takeIf { wanted !in keys }
                }

                // Pinning nothing yet: releasing refuses it, and there is nothing to check against.
                else -> null
            }
        }
    }

    /** One unresolved reference as a sentence, with what the person can choose instead. */
    private fun sentence(catalogKey: CatalogKey, unresolved: Unresolved, release: String, plan: MergePlan, actions: Map<String, MergeAction>): String {
        val (source, reference, reason) = unresolved
        val other = reference.catalogKey
        val problem = when (source) {
            DEFAULT_THEME -> when (reason) {
                Reason.Undeclared -> "the default theme '${reference.key}' of '$other' needs '$other' as a dependency"
                is Reason.NotInRelease -> "the default theme '${reference.key}' is not in v${reason.version} of '$other', the release this catalog uses"
                is Reason.NotInDraft -> "the default theme '${reference.key}' is not in ${reason.draftLabel} of '$other', which this catalog uses"
                Reason.NotInCatalog -> "the default theme '${reference.key}' is not in this catalog"
            }

            else -> {
                val what = sourceLabel(source)
                when (reason) {
                    Reason.Undeclared -> "$what uses ${reference.label(catalogKey)}, which needs '$other' as a dependency"
                    is Reason.NotInRelease -> "$what uses ${reference.label(catalogKey)}, which v${reason.version} of '$other', the release this catalog uses, does not have"
                    is Reason.NotInDraft -> "$what uses ${reference.label(catalogKey)}, which ${reason.draftLabel} of '$other' does not have"
                    Reason.NotInCatalog -> "$what uses ${reference.label(catalogKey)}, which this catalog does not have"
                }
            }
        }
        val classified = plan.resources.associateBy { it.contentKey }
        val needed = reference.entryIn(catalogKey)
        val hints = buildList {
            // The entry it needs was a choice: the other side of it goes with what is kept.
            if (classified[needed]?.outcome == MergeOutcome.CONFLICT) {
                val tookTheirs = actions[needed] is MergeAction.Write || actions[needed] == MergeAction.Remove
                if (tookTheirs && classified[needed]?.ours != MergeChange.REMOVED) add("keep your ${entryLabel(needed)}")
                if (!tookTheirs && classified[needed]?.inTheirs == true) add("take $release's ${entryLabel(needed)} too")
            }
            // It was a choice, and the release's side was taken: keeping the draft's does without it.
            if (source != needed && classified[source]?.outcome == MergeOutcome.CONFLICT && actions[source] is MergeAction.Write) {
                add("keep your ${if (source == DEFAULT_THEME) "default theme" else sourceLabel(source)}")
            }
        }
        return problem + if (hints.isEmpty()) "" else ": " + hints.joinToString(", or ")
    }

    private fun sourceLabel(contentKey: String): String = "${MergeReferences.word(contentKey.substringBefore('/'))} '${contentKey.substringAfter('/')}'"

    private fun entryLabel(contentKey: String): String = if (contentKey.substringBefore('/') == CatalogSetting.DEPENDENCY_TYPE) {
        "dependency on '${contentKey.substringAfter('/')}'"
    } else {
        sourceLabel(contentKey)
    }

    private companion object {
        val DEFAULT_THEME = CatalogSetting.DefaultTheme(null).contentKey
    }
}
