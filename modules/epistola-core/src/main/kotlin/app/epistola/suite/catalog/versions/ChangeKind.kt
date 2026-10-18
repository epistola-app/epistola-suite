// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.dependencies.MissingDependencyDeclarer
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.model.StencilInstances
import app.epistola.suite.templates.contracts.SchemaCompatibilityChecker
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * What kind of change a draft holds against its base, lowest first (contract ADR 0001 "A hotfix may
 * not break anything", ADR 0002 renumber warnings). [BREAKING] needs a major version, [ADDITIVE] at
 * least a minor, [NONE] fits a patch.
 */
enum class ChangeKind { NONE, ADDITIVE, BREAKING }

/** One reason a draft's changes are [kind], naming the resource ([type], [key]) it is about. */
data class ChangeReason(val kind: ChangeKind, val type: String, val key: String, val reason: String) {
    override fun toString(): String = "$type '$key': $reason"
}

/** What a draft changed against its base that is more than a fix, every reason with its kind. */
data class ChangeAssessment(val reasons: List<ChangeReason>) {
    /** The highest kind of the reasons; [ChangeKind.NONE] when there are none. */
    val kind: ChangeKind get() = reasons.maxOfOrNull { it.kind } ?: ChangeKind.NONE

    /** The reasons above [allowed], breaking ones first. */
    fun above(allowed: ChangeKind): List<ChangeReason> = reasons.filter { it.kind > allowed }.sortedByDescending { it.kind }
}

/** Which part of a version number a new version raises over its base. */
enum class VersionStep(
    /** The highest kind of change this step may carry. */
    val allows: ChangeKind,
) {
    PATCH(ChangeKind.NONE),
    MINOR(ChangeKind.ADDITIVE),
    MAJOR(ChangeKind.BREAKING),
    ;

    companion object {
        /** The step from [base] to [to]; null when [to] is not above [base]. */
        fun between(base: SemVer, to: SemVer): VersionStep? = when {
            to <= base -> null
            to.major != base.major -> MAJOR
            to.minor != base.minor -> MINOR
            else -> PATCH
        }
    }
}

/**
 * Compares a draft's content with its base release's and says, resource by resource, which changes
 * are breaking and which additive (contract ADR 0001 lists what a hotfix may not change; ADR 0002
 * measures a renumber's version against it). Anything not listed here is a fix: [ChangeKind.NONE].
 *
 * - **Resources**: one removed is breaking, one added additive. Applies to every type.
 * - **Template data contract**: it must accept every input the base accepted. Narrowed is breaking
 *   ([SchemaCompatibilityChecker], which answers exactly that); any other change widens it, additive.
 *   A contract where there was none narrows (breaking); dropping it accepts anything (additive).
 * - **Variants**: one removed is breaking, one added additive. Changed attributes are breaking: a
 *   request that selected the variant by them may get another.
 * - **Stencil interface**: by the rule that decides whether an instance still fits
 *   ([StencilInstances.placeholders], [StencilInstances.mustBind]): a placeholder removed, or a
 *   parameter an instance must now bind, is breaking; so is a parameter whose type changed, which a
 *   binding of the old type no longer satisfies. A placeholder or optional parameter added, a parameter
 *   no longer required, or one removed (an instance's binding of it is ignored), is additive.
 * - **Attribute definitions**: allowed values removed, a restriction where there was none, or another
 *   code-list binding is breaking; values added, or the restriction lifted, is additive.
 * - **Code-list entries**: a code removed is breaking; a code added, or an entry's label, order or
 *   hidden flag changed, additive (ADR 0001 keeps entries unchanged in a hotfix; a hidden entry stays
 *   valid for what used it).
 * - **Declared dependencies**: one added (an installation needs it), removed, or its range changed
 *   is breaking. A lock moved within the same range is as big as the move: to another major breaking,
 *   another minor additive, a higher patch nothing; a lower lock is breaking.
 */
class ChangeKindEvaluator(private val objectMapper: ObjectMapper) {

    private val schemas = SchemaCompatibilityChecker()

    fun evaluate(base: CatalogContent, draft: CatalogContent): ChangeAssessment {
        val reasons = mutableListOf<ChangeReason>()
        val before = base.resourceDetails.mapValues { it.value.resource }
        val after = draft.resourceDetails.mapValues { it.value.resource }
        for (key in (before.keys + after.keys).sorted()) {
            val type = key.substringBefore('/')
            val resourceKey = key.substringAfter('/')
            fun reason(kind: ChangeKind, text: String) {
                reasons += ChangeReason(kind, type, resourceKey, text)
            }
            val old = before[key]
            val new = after[key]
            when {
                new == null -> reason(ChangeKind.BREAKING, "removed")
                old == null -> reason(ChangeKind.ADDITIVE, "added")
                old is TemplateResource && new is TemplateResource -> template(old, new, ::reason)
                old is StencilResource && new is StencilResource -> stencil(old, new, ::reason)
                old is AttributeResource && new is AttributeResource -> attribute(old, new, ::reason)
                old is CodeListResource && new is CodeListResource -> codeList(old, new, ::reason)
                else -> Unit
            }
        }
        reasons += dependencies(base.catalogDependencies.orEmpty(), draft.catalogDependencies.orEmpty())
        return ChangeAssessment(reasons)
    }

    private fun template(old: TemplateResource, new: TemplateResource, reason: (ChangeKind, String) -> Unit) {
        val oldSchema = old.dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) }
        val newSchema = new.dataModel?.let { objectMapper.valueToTree<ObjectNode>(it) }
        when {
            oldSchema == newSchema -> Unit

            oldSchema == null -> reason(ChangeKind.BREAKING, "a data contract was added, which rejects input accepted before")

            newSchema == null -> reason(ChangeKind.ADDITIVE, "its data contract was removed, so any input is accepted")

            else -> {
                val result = schemas.checkCompatibility(oldSchema, newSchema)
                if (result.compatible) {
                    reason(ChangeKind.ADDITIVE, "its data contract accepts more input")
                } else {
                    result.breakingChanges.forEach { change ->
                        reason(ChangeKind.BREAKING, "data contract: " + change.description + (change.path.takeIf { it.isNotEmpty() && !change.description.contains(it) }?.let { " ($it)" } ?: ""))
                    }
                }
            }
        }
        val oldVariants = old.variants.orEmpty().associateBy { it.key }
        val newVariants = new.variants.orEmpty().associateBy { it.key }
        (oldVariants.keys - newVariants.keys).sorted().forEach { reason(ChangeKind.BREAKING, "variant '$it' removed") }
        (newVariants.keys - oldVariants.keys).sorted().forEach { reason(ChangeKind.ADDITIVE, "variant '$it' added") }
        oldVariants.keys.intersect(newVariants.keys).sorted().forEach { variant ->
            val was = oldVariants.getValue(variant).attributes.orEmpty()
            val now = newVariants.getValue(variant).attributes.orEmpty()
            if (was != now) reason(ChangeKind.BREAKING, "variant '$variant' attributes changed from ${was.describe()} to ${now.describe()}")
        }
    }

    private fun stencil(old: StencilResource, new: StencilResource, reason: (ChangeKind, String) -> Unit) {
        val oldPlaceholders = StencilInstances.placeholders(old.content)
        val newPlaceholders = StencilInstances.placeholders(new.content)
        (oldPlaceholders - newPlaceholders).sorted().forEach { reason(ChangeKind.BREAKING, "placeholder '$it' removed") }
        (newPlaceholders - oldPlaceholders).sorted().forEach { reason(ChangeKind.ADDITIVE, "placeholder '$it' added") }

        val oldParameters = properties(old.parameterSchema)
        val newParameters = properties(new.parameterSchema)
        val oldMustBind = StencilInstances.mustBind(old.parameterSchema)
        val newMustBind = StencilInstances.mustBind(new.parameterSchema)
        (newMustBind - oldMustBind).sorted().forEach { reason(ChangeKind.BREAKING, "parameter '$it' must now be bound") }
        (oldMustBind - newMustBind).filter { it in newParameters }.sorted().forEach { reason(ChangeKind.ADDITIVE, "parameter '$it' no longer has to be bound") }
        (newParameters.keys - oldParameters.keys - newMustBind).sorted().forEach { reason(ChangeKind.ADDITIVE, "parameter '$it' added") }
        (oldParameters.keys - newParameters.keys).sorted().forEach { reason(ChangeKind.ADDITIVE, "parameter '$it' removed; a binding of it is ignored") }
        oldParameters.keys.intersect(newParameters.keys).sorted().forEach { name ->
            val was = typeOf(oldParameters[name])
            val now = typeOf(newParameters[name])
            if (was != now) reason(ChangeKind.BREAKING, "parameter '$name' changed type from $was to $now")
        }
    }

    private fun attribute(old: AttributeResource, new: AttributeResource, reason: (ChangeKind, String) -> Unit) {
        val was = old.allowedValues.orEmpty().toSet()
        val now = new.allowedValues.orEmpty().toSet()
        when {
            was == now -> Unit

            // No allowed values means any value is allowed.
            was.isEmpty() -> reason(ChangeKind.BREAKING, "now allows only ${now.sorted().joinToString()}")

            now.isEmpty() -> reason(ChangeKind.ADDITIVE, "allows any value")

            else -> {
                (was - now).sorted().takeIf { it.isNotEmpty() }?.let { reason(ChangeKind.BREAKING, "no longer allows ${it.joinToString()}") }
                (now - was).sorted().takeIf { it.isNotEmpty() }?.let { reason(ChangeKind.ADDITIVE, "also allows ${it.joinToString()}") }
            }
        }
        if (old.codeListBinding != new.codeListBinding) {
            reason(ChangeKind.BREAKING, "its code-list binding changed from ${old.codeListBinding?.describe() ?: "none"} to ${new.codeListBinding?.describe() ?: "none"}")
        }
    }

    private fun codeList(old: CodeListResource, new: CodeListResource, reason: (ChangeKind, String) -> Unit) {
        val was = old.entries.orEmpty().associateBy { it.code }
        val now = new.entries.orEmpty().associateBy { it.code }
        (was.keys - now.keys).sorted().forEach { reason(ChangeKind.BREAKING, "entry '$it' removed") }
        (now.keys - was.keys).sorted().forEach { reason(ChangeKind.ADDITIVE, "entry '$it' added") }
        was.keys.intersect(now.keys).sorted().filter { was[it] != now[it] }.forEach { reason(ChangeKind.ADDITIVE, "entry '$it' changed") }
    }

    private fun dependencies(old: List<CatalogDependency>, new: List<CatalogDependency>): List<ChangeReason> {
        fun CatalogDependency.id() = "$registry/$namespace/$catalogKey"
        val was = old.associateBy { it.id() }
        val now = new.associateBy { it.id() }
        fun reason(kind: ChangeKind, id: String, text: String) = ChangeReason(kind, "dependency", id, text)
        val reasons = mutableListOf<ChangeReason>()
        (was.keys - now.keys).sorted().forEach { reasons += reason(ChangeKind.BREAKING, it, "removed") }
        (now.keys - was.keys).sorted().forEach { reasons += reason(ChangeKind.BREAKING, it, "added; an installation needs it") }
        for (id in was.keys.intersect(now.keys).sorted()) {
            val before = was.getValue(id)
            val after = now.getValue(id)
            if (before.version != after.version) {
                reasons += reason(ChangeKind.BREAKING, id, "its range changed from ${before.version} to ${after.version}")
                continue
            }
            // A declaration not locked yet is locked when the draft is released: nothing to compare.
            val lockedBefore = before.releaseVersion?.let(SemVer::parseOrNull) ?: continue
            val lockedAfter = after.releaseVersion?.let(SemVer::parseOrNull) ?: continue
            val kind = when (VersionStep.between(lockedBefore, lockedAfter)) {
                null -> if (lockedAfter == lockedBefore) null else ChangeKind.BREAKING
                VersionStep.PATCH -> null
                VersionStep.MINOR -> ChangeKind.ADDITIVE
                VersionStep.MAJOR -> ChangeKind.BREAKING
            }
            kind?.let { reasons += reason(it, id, "locked to $lockedAfter instead of $lockedBefore") }
        }
        return reasons
    }

    private fun properties(schema: Map<String, Any?>?): Map<String, Any?> = (schema?.get("properties") as? Map<*, *>)
        ?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap().orEmpty()

    /** A parameter's type as a binding must satisfy it: its type and format, and its items' for a list. */
    private fun typeOf(property: Any?): String {
        val map = property as? Map<*, *> ?: return "unknown"
        val own = listOfNotNull(map["type"]?.toString(), map["format"]?.toString()).joinToString(" ").ifEmpty { "any" }
        val items = (map["items"] as? Map<*, *>)?.let { typeOf(it) }
        return if (items != null) "$own of $items" else own
    }

    private fun Map<String, String>.describe() = if (isEmpty()) "none" else entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }

    private fun app.epistola.catalog.protocol.CodeListBindingRef.describe() = listOfNotNull(namespace, catalogKey, key).joinToString("/")
}

/**
 * What the draft the running message targets changed against release [base], by [ChangeKindEvaluator].
 * The caller targets the draft (a [CatalogVersionScoped] message); the base is read from the content
 * its release retained.
 */
@Component
class DraftChangeKinds(
    private val contentBuilder: CatalogContentBuilder,
    private val releases: ReleaseContentAssembler,
    private val declarer: MissingDependencyDeclarer,
    objectMapper: ObjectMapper,
) {
    private val evaluator = ChangeKindEvaluator(objectMapper)

    /**
     * Null when the catalog has no release [base] that retained its content, so there is nothing to
     * compare. The draft counts the catalogs it uses but does not declare as declared: publishing
     * declares them before it releases, so the number it is released as has to account for them.
     */
    fun against(tenantKey: TenantKey, catalogKey: CatalogKey, base: String): ChangeAssessment? {
        val released = releases.assemble(tenantKey, catalogKey, base)?.content ?: return null
        val draft = contentBuilder.build(tenantKey, catalogKey)
        val undeclared = declarer.wouldDeclare(tenantKey, catalogKey, draft)
        val asPublished = if (undeclared.isEmpty()) draft else draft.copy(catalogDependencies = draft.catalogDependencies.orEmpty() + undeclared.map { it.toWire() })
        return evaluator.evaluate(released, asPublished)
    }
}
