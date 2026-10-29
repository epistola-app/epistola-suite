// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CodeListBindingRef
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.commands.ImportAttribute
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceChange
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.mediator.execute
import org.springframework.stereotype.Component

/** A variant attribute as a catalog resource type (ADR 0039): it may bind a code list, by its catalog's alias. */
@Component
class AttributeResourceType : ResourceType<AttributeResource> {
    override val name = AttributeStore.ATTRIBUTE
    override val wireClass = AttributeResource::class.java
    override val installedAfter = setOf(CodeListStore.CODE_LIST)

    override fun receive(catalog: CatalogId, resource: AttributeResource, arrival: Arrival): InstallStatus = ImportAttribute(
        catalog = catalog,
        slug = resource.key,
        displayName = resource.name,
        allowedValues = resource.allowedValues,
        codeListCatalog = resource.codeListBinding?.let { arrival.resolve(it.catalogKey) },
        codeListSlug = resource.codeListBinding?.key?.let(CodeListKey::of),
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()

    /** A value it no longer allows, or another code-list binding, is breaking; allowing more is additive. */
    override fun changes(old: AttributeResource, new: AttributeResource): List<ResourceChange> = buildList {
        fun reason(kind: ChangeKind, text: String) = add(ResourceChange(kind, text))
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

    private fun CodeListBindingRef.describe() = listOfNotNull(catalogKey, key).joinToString("/")
}
