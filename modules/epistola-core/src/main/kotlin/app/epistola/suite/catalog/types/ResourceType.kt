// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.types

import app.epistola.catalog.protocol.CatalogResource
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.CatalogId
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * One kind of catalog resource, as the contract names it, said by the domain that owns it (ADR 0039).
 * Catalog stores, exports and installs every type through this, and names no content domain: each
 * domain contributes its own as a bean, and [ResourceTypes] collects them.
 */
interface ResourceType<R : CatalogResource> {
    /** The wire name (`theme`, `codeList`, …): the `resource_type` of its rows and the manifest entry's `type`. */
    val name: String

    /** The contract's class for it. */
    val wireClass: Class<R>

    /** The types whose resources must be in place before one of this is installed (an attribute binds a code list). */
    val installedAfter: Set<String> get() = emptySet()

    /** How its content is laid out in rows: one, or a parent and its parts (a template and its variants). */
    val layout: ContentLayout get() = ContentLayout.Single

    /**
     * Puts [resource], as a release brings it, into the version [catalog]'s messages target: created,
     * or updated in place when the version holds it. Checked and normalised as the type requires, and
     * authorised as changing one of these is. Throws when it cannot be put there.
     */
    fun receive(catalog: CatalogId, resource: R, arrival: Arrival): InstallStatus

    /**
     * Marks [key] of the version [catalog]'s messages target ready through this type's own checks
     * (a template's or a stencil's command), and returns true; false for a type with no checks of its
     * own, whose ready mark the catalog sets itself.
     */
    fun markReady(catalog: CatalogId, key: String): Boolean = false

    /**
     * What changing [old] into [new] means for a version number, each reason with its kind (contract
     * ADR 0002): a removed variant is breaking, an added code-list entry additive. Empty for a type
     * whose changes are all fixes.
     */
    fun changes(old: R, new: R): List<ResourceChange> = emptyList()

    /**
     * The references [resource] (its released form, as stored) carries outside the reference sites
     * every document has ([app.epistola.suite.catalog.graph.ResourceReferenceSites]): a template's
     * theme and its variants' attributes. Indexed with them in `resource_references` when stored; a
     * new one in content that already exists needs a migration indexing it there too.
     */
    fun references(resource: JsonNode): Set<app.epistola.suite.catalog.revisions.ContentReference> = emptySet()

    /**
     * Points each reference of [references] in [resource] (its wire form, changed in place) for which
     * [to] gives another at that one: what a refactoring does to content that names what it moved
     * (ADR 0042). True when one changed.
     */
    fun repoint(resource: ObjectNode, to: (app.epistola.suite.catalog.revisions.ContentReference) -> app.epistola.suite.catalog.revisions.ContentReference?): Boolean = false
}

/** One reason a resource's change is of [kind], said in [text] ("variant 'b' removed"). */
data class ResourceChange(val kind: app.epistola.suite.catalog.releases.ChangeKind, val text: String)

/**
 * What a resource arrives with: the release [version] that brings it, the [binaries] it names by
 * archive path (an image's content, a font's faces), and what an alias of it means ([resolve]: the
 * catalog an alias names, its own for none, null when nothing it declares does).
 */
class Arrival(
    val version: String,
    val binaries: (String) -> ByteArray?,
    val resolve: (String?) -> CatalogId?,
)

/** How a type's content is stored: as one content, or as a parent and one row per part. */
sealed interface ContentLayout {
    /** One row, one content: the envelope's `resource` is the wire form. */
    data object Single : ContentLayout

    /**
     * A parent content and one row per part, so that editing one part stores only that part (a
     * template: the template, and one row per variant with its model).
     */
    interface Parts : ContentLayout {
        /** The row type of a part: not a wire type, so never a manifest entry. */
        val partType: String

        /**
         * The wire form of a resource stored as [parent] with [parts] by key, both as stored
         * (envelopes). A parent stored before its type had parts has none, and is its wire form already.
         */
        fun assemble(parent: JsonNode, parts: Map<String, JsonNode>): JsonNode
    }
}

/** Every [ResourceType] the application has, by name, and the order an archive installs them in. */
@Component
class ResourceTypes(types: List<ResourceType<*>>) {
    private val byName: Map<String, ResourceType<*>> = types.associateBy { it.name }.also { named ->
        check(named.size == types.size) { "Two resource types share a name: ${types.groupBy { it.name }.filterValues { it.size > 1 }.keys}" }
        types.forEach { type ->
            val unknown = type.installedAfter - named.keys
            check(unknown.isEmpty()) { "Resource type ${type.name} is installed after unknown types $unknown" }
        }
    }

    /** Every type's name. */
    val names: Set<String> get() = byName.keys

    /** The type named [name]; fails for one nobody contributed. */
    fun of(name: String): ResourceType<*> = byName[name] ?: throw IllegalArgumentException("Unknown resource type: $name")

    /** The type named [name], or null. */
    fun find(name: String): ResourceType<*>? = byName[name]

    /** The type [resource] is of. */
    fun of(resource: CatalogResource): ResourceType<*> = byName.values.firstOrNull { it.wireClass.isInstance(resource) }
        ?: throw IllegalArgumentException("No resource type for ${resource::class.simpleName}")

    /** [type]'s [ResourceType.receive] of [resource], which is of that type. */
    fun <R : CatalogResource> receive(type: ResourceType<R>, catalog: CatalogId, resource: CatalogResource, arrival: Arrival): InstallStatus = type.receive(catalog, type.wireClass.cast(resource), arrival)

    /** What changing [old] into [new] means, as their type says; empty when they are of different types. */
    fun changes(old: CatalogResource, new: CatalogResource): List<ResourceChange> {
        val type = of(new)
        return if (type.wireClass.isInstance(old)) changesOf(type, old, new) else emptyList()
    }

    private fun <R : CatalogResource> changesOf(type: ResourceType<R>, old: CatalogResource, new: CatalogResource): List<ResourceChange> = type.changes(type.wireClass.cast(old), type.wireClass.cast(new))

    /**
     * Every type, each after the ones it is installed after, ties by name. A cycle fails at startup.
     */
    val installOrder: List<ResourceType<*>> = buildList {
        val placed = mutableSetOf<String>()
        val remaining = byName.values.sortedBy { it.name }.toMutableList()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { it.installedAfter.all(placed::contains) }
                ?: error("Resource types are installed after each other in a cycle: ${remaining.map { it.name }}")
            add(next)
            placed += next.name
            remaining -= next
        }
    }
}
