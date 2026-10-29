// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceChange
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.ImportStencil
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.stencils.model.StencilInstances
import org.springframework.stereotype.Component

/** A stencil as a catalog resource type (ADR 0039): its document names a theme, fonts and images. */
@Component
class StencilResourceType : ResourceType<StencilResource> {
    override val name = StencilStore.STENCIL
    override val wireClass = StencilResource::class.java
    override val installedAfter = setOf("theme", "font", "image")

    override fun receive(catalog: CatalogId, resource: StencilResource, arrival: Arrival): InstallStatus = ImportStencil(
        catalog = catalog,
        slug = resource.key,
        name = resource.name,
        description = resource.description,
        tags = resource.tags,
        content = resource.content,
        parameterSchema = resource.parameterSchema,
        readsTemplateData = resource.readsTemplateData,
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()

    override fun markReady(catalog: CatalogId, key: String): Boolean {
        MarkStencilReady(StencilId(StencilKey.of(key), catalog)).execute()
        return true
    }

    /**
     * What an instance of it relies on: its placeholders ([StencilInstances.placeholders]) and the
     * parameters it must be bound ([StencilInstances.mustBind]). A placeholder removed, a parameter
     * that must now be bound, or one whose type changed, is breaking; the opposite is additive.
     */
    override fun changes(old: StencilResource, new: StencilResource): List<ResourceChange> = buildList {
        fun reason(kind: ChangeKind, text: String) = add(ResourceChange(kind, text))
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

    private fun properties(schema: Map<String, Any?>?): Map<String, Any?> = (schema?.get("properties") as? Map<*, *>)
        ?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap().orEmpty()

    /** A parameter's type as a binding must satisfy it: its type and format, and its items' for a list. */
    private fun typeOf(property: Any?): String {
        val map = property as? Map<*, *> ?: return "unknown"
        val own = listOfNotNull(map["type"]?.toString(), map["format"]?.toString()).joinToString(" ").ifEmpty { "any" }
        val items = (map["items"] as? Map<*, *>)?.let { typeOf(it) }
        return if (items != null) "$own of $items" else own
    }
}
