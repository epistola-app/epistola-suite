// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.graph

import app.epistola.suite.common.ids.ResourceIdentity

enum class CatalogResourceType(val wireName: String) {
    IMAGE("image"),
    CODE_LIST("codeList"),
    FONT("font"),
    ATTRIBUTE("attribute"),
    THEME("theme"),
    STENCIL("stencil"),
    TEMPLATE("template"),
}

data class ResourceAddress(
    val type: CatalogResourceType,
    /** The catalog's namespace: with [catalogKey], its address (ADR 0034, section 9). */
    val namespace: String,
    /** The catalog's key. */
    val catalogKey: String,
    val key: String,
) {
    /** The catalog's address, `namespace/key`. */
    val catalogAddress: String get() = "$namespace/$catalogKey"

    /**
     * Stable text form, used for sorting, blocker messages, graph ids and the organise deep link.
     *
     * Shaped like [app.epistola.suite.common.ids.EntityIdBase.path]: a colon after the type, then
     * the address as a path, the catalog by its namespace and key. The tenant is left out because
     * every surface carrying one of these already names the tenant elsewhere in its URL.
     */
    val id: String get() = "${type.wireName}:$namespace/$catalogKey/$key"

    /** The catalog it is in, in [tenantKey]. */
    fun catalogIn(tenantKey: app.epistola.suite.common.ids.TenantKey): app.epistola.suite.common.ids.CatalogId = app.epistola.suite.common.ids.CatalogId(namespace, app.epistola.suite.common.ids.CatalogKey.of(catalogKey), app.epistola.suite.common.ids.TenantId(tenantKey))

    companion object {
        /** The resource [key] of [type] in the catalog at [catalogAddress] (`namespace/key`). */
        fun at(type: CatalogResourceType, catalogAddress: String, key: String): ResourceAddress {
            require('/' in catalogAddress) { "A catalog is named by its address, namespace/key: $catalogAddress" }
            return ResourceAddress(type, catalogAddress.substringBefore('/'), catalogAddress.substringAfter('/'), key)
        }

        /** The address [id] gives, or null when it is not one. */
        fun parse(id: String): ResourceAddress? {
            val type = CatalogResourceType.entries.firstOrNull { id.startsWith("${it.wireName}:") } ?: return null
            val parts = id.substringAfter(':').split('/')
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return ResourceAddress(type, parts[0], parts[1], parts[2])
        }
    }
}

data class ResourceNode(
    /**
     * Stable tenant-local identity, unchanged by a relocation.
     *
     * The address is what authors and catalog exchange use, but it moves. Callers that need to
     * follow a resource across a move -- notably relocation itself -- hold this instead.
     */
    val resourceId: ResourceIdentity,
    val address: ResourceAddress,
    val name: String,
    val catalogName: String,
)

enum class ReferenceSemantics {
    RUNTIME,
    AUTHORING,
    PROVENANCE,
}

enum class ReferenceQualification {
    EXPLICIT,
    RELATIVE,
    TENANT_GLOBAL,
}

enum class ReferenceResolution {
    RESOLVED,
    MISSING,
    AMBIGUOUS,
}

data class ReferenceSelector(
    val type: CatalogResourceType,
    /** The alias the reference names its catalog by (the catalog's key, ADR 0034); null for none. */
    val catalogKey: String?,
    val key: String,
)

data class ResourceEdge(
    val id: String,
    val source: ResourceAddress,
    val target: ResourceAddress?,
    val targetSelector: ReferenceSelector,
    val targetCandidates: List<ResourceAddress>,
    val qualification: ReferenceQualification,
    val resolution: ReferenceResolution,
)

data class TenantResourceGraph(
    val nodes: List<ResourceNode>,
    val edges: List<ResourceEdge>,
)
