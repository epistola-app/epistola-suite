// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists

import app.epistola.catalog.protocol.CodeListResource
import app.epistola.suite.catalog.types.ResourceType
import org.springframework.stereotype.Component

/** A code list as a catalog resource type (ADR 0039): its `source` is a setting beside its released form. */
@Component
class CodeListResourceType : ResourceType<CodeListResource> {
    override val name = CodeListStore.CODE_LIST
    override val wireClass = CodeListResource::class.java
}
