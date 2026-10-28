// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.catalog.protocol.CodeListResource
import app.epistola.suite.attributes.codelists.commands.ImportCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.releases.ChangeKind
import app.epistola.suite.catalog.types.Arrival
import app.epistola.suite.catalog.types.ResourceChange
import app.epistola.suite.catalog.types.ResourceType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.mediator.execute
import org.springframework.stereotype.Component

/** A code list as a catalog resource type (ADR 0039): its `source` is a setting beside its released form. */
@Component
class CodeListResourceType : ResourceType<CodeListResource> {
    override val name = CodeListStore.CODE_LIST
    override val wireClass = CodeListResource::class.java

    override fun receive(catalog: CatalogId, resource: CodeListResource, arrival: Arrival): InstallStatus = ImportCodeList(
        catalog = catalog,
        slug = resource.key,
        displayName = resource.name,
        description = resource.description,
        entries = resource.entries.map { wire -> CodeListEntry(code = wire.code, label = wire.label, sortOrder = wire.sortOrder, hidden = wire.hidden) },
        // As received: none clears what the draft held.
        metadata = resource.metadata.orEmpty(),
    ).execute()

    /** An entry removed is breaking (a value that was valid no longer is); one added or changed is additive. */
    override fun changes(old: CodeListResource, new: CodeListResource): List<ResourceChange> = buildList {
        val was = old.entries.orEmpty().associateBy { it.code }
        val now = new.entries.orEmpty().associateBy { it.code }
        (was.keys - now.keys).sorted().forEach { add(ResourceChange(ChangeKind.BREAKING, "entry '$it' removed")) }
        (now.keys - was.keys).sorted().forEach { add(ResourceChange(ChangeKind.ADDITIVE, "entry '$it' added")) }
        was.keys.intersect(now.keys).sorted().filter { was[it] != now[it] }.forEach { add(ResourceChange(ChangeKind.ADDITIVE, "entry '$it' changed")) }
    }
}
