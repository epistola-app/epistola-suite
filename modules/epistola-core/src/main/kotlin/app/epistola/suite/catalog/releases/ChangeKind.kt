// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CatalogDependency
import app.epistola.catalog.protocol.CatalogInfo
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.catalog.protocol.StencilResource
import app.epistola.catalog.protocol.TemplateResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.dependencies.UseCompatibility
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.CatalogId
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
 * - **Declared dependencies**: what a catalog depends on is not part of what others rely on, so a
 *   dependency added is additive (an installation now brings it along), and one removed or its range
 *   changed is no change at all. A lock moved to another version of a dependency is breaking only when
 *   that version breaks a resource this catalog uses ([lockMove], the per-use check of
 *   `UseCompatibility`); otherwise it is a fix, whatever the step in its number. Without [lockMove] a
 *   lock move is a fix.
 * - **Presets** (contract ADR 0007): a catalog's available presets include those of the catalogs it
 *   uses, so content elsewhere may name one. One removed is breaking, one added additive, one renamed
 *   or described differently a fix.
 * - **Default theme and license**: a fix. The default theme decides how this catalog's templates
 *   that name no theme look, as a theme's own content does, which is a fix too; nothing another
 *   catalog or a caller relies on changes. The license is about the content, not part of it.
 */
class ChangeKindEvaluator(
    private val objectMapper: ObjectMapper,
    /** What moving dependency `catalogKey`'s lock from one version to another breaks of what is used, as reasons. */
    private val lockMove: ((catalogKey: String, from: String, to: String) -> List<String>)? = null,
) {

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
        reasons += settings(base.catalog, draft.catalog)
        return ChangeAssessment(reasons)
    }

    /** The presets, the default theme and the license (contract ADR 0007): what the catalog holds beside its resources. */
    private fun settings(old: CatalogInfo, new: CatalogInfo): List<ChangeReason> {
        val reasons = mutableListOf<ChangeReason>()
        val was = old.presets.associateBy { it.key }
        val now = new.presets.associateBy { it.key }
        (was.keys - now.keys).sorted().forEach { reasons += ChangeReason(ChangeKind.BREAKING, "preset", it, "removed, while content of a catalog that uses this one may name it") }
        (now.keys - was.keys).sorted().forEach { reasons += ChangeReason(ChangeKind.ADDITIVE, "preset", it, "added") }
        was.keys.intersect(now.keys).sorted().filter { was[it] != now[it] }.forEach { reasons += ChangeReason(ChangeKind.NONE, "preset", it, "renamed or described differently") }
        // The own catalog's theme is the same whether or not the reference names its alias.
        fun CatalogInfo.theme() = defaultTheme?.let { (it.catalogKey ?: key) to it.key }
        // Another default theme or license is a minor change: what a template renders with, or the terms
        // it is used under, change for everyone who uses the catalog, though nothing they do stops working.
        if (old.theme() != new.theme()) reasons += ChangeReason(ChangeKind.ADDITIVE, "catalog", "defaultTheme", "templates without a theme of their own render with another one")
        if (old.license != new.license) reasons += ChangeReason(ChangeKind.ADDITIVE, "catalog", "license", "changed")
        return reasons
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
        (was.keys - now.keys).sorted().forEach { reasons += reason(ChangeKind.NONE, it, "no longer used") }
        (now.keys - was.keys).sorted().forEach { reasons += reason(ChangeKind.ADDITIVE, it, "added; an installation brings it along") }
        for (id in was.keys.intersect(now.keys).sorted()) {
            val before = was.getValue(id)
            val after = now.getValue(id)
            // A declaration not locked yet is locked when the draft is released: nothing to compare.
            val lockedBefore = before.releaseVersion?.takeIf { SemVer.parseOrNull(it) != null } ?: continue
            val lockedAfter = after.releaseVersion?.takeIf { SemVer.parseOrNull(it) != null } ?: continue
            if (lockedBefore == lockedAfter) continue
            val broken = lockMove?.invoke(after.catalogKey, lockedBefore, lockedAfter).orEmpty()
            reasons += if (broken.isEmpty()) {
                reason(ChangeKind.NONE, id, "now uses v$lockedAfter instead of v$lockedBefore, which fits what is used")
            } else {
                reason(ChangeKind.BREAKING, id, "v$lockedAfter breaks what is used of it: " + broken.joinToString("; "))
            }
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

    private fun app.epistola.catalog.protocol.CodeListBindingRef.describe() = listOfNotNull(catalogKey, key).joinToString("/")
}

/**
 * What the draft the running message targets changed against release [base], by [ChangeKindEvaluator].
 * The caller targets the draft (a [CatalogReleaseScoped] message); the base is read from the content
 * its release retained.
 */
@Component
class DraftChangeKinds(
    private val jdbi: org.jdbi.v3.core.Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val releases: ReleaseContentAssembler,
    private val compatibility: UseCompatibility,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Null when the catalog has no release [base], so there is nothing to compare. The catalogs the
     * draft uses are declared as it starts using them ([app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer]),
     * so its declarations are what it is released with.
     */
    fun against(catalog: CatalogId, base: String): ChangeAssessment? {
        val released = releases.assemble(catalog, base)?.content ?: return null
        val draft = contentBuilder.build(catalog)
        // A dependency's newer version counts only for what the draft uses of it.
        val evaluator = ChangeKindEvaluator(objectMapper) { dependency, from, to ->
            // The catalog the alias names, by the draft's declaration of it (ADR 0034, section 9).
            val target = app.epistola.suite.catalog.CatalogReferences.resolve(catalog, dependency, draft.catalogDependencies.orEmpty().map(app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration::of))
            if (target == null) {
                emptyList()
            } else {
                compatibility.breaks(target, from, to, UseCompatibility.usedOf(draft.dependencies.orEmpty(), dependency))
                    .map { "${typeName(it.type)} '${it.key}': ${it.reason}" }
            }
        }
        val assessment = evaluator.evaluate(released, draft)
        // Newer rendering defaults change what every document looks like: a minor (#1127).
        val renderedWith = jdbi.withHandle<Int?, Exception> { handle ->
            handle.createQuery("SELECT rendering_defaults_version FROM catalog_releases WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v")
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .bind("v", base)
                .map { rs, _ -> rs.getObject(1) as Int? }
                .list()
                .firstOrNull()
        }
        val current = app.epistola.generation.pdf.RenderingDefaults.CURRENT.version
        if (renderedWith == null || renderedWith == current) return assessment
        return ChangeAssessment(assessment.reasons + ChangeReason(ChangeKind.ADDITIVE, "catalog", "renderingDefaults", "rendered with V$current instead of V$renderedWith, so documents may look different"))
    }
}

private fun typeName(type: String): String = if (type == "codeList") "code list" else type
