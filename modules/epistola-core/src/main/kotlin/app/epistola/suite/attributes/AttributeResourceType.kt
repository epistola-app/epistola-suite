// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.catalog.types.ResourceType
import org.springframework.stereotype.Component

/** A variant attribute as a catalog resource type (ADR 0039): it may bind a code list. */
@Component
class AttributeResourceType : ResourceType<AttributeResource> {
    override val name = AttributeStore.ATTRIBUTE
    override val wireClass = AttributeResource::class.java
    override val installedAfter = setOf(CodeListStore.CODE_LIST)
}
