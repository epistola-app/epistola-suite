// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.catalog.protocol.FontResource
import app.epistola.suite.catalog.types.ResourceType
import org.springframework.stereotype.Component

/** A font as a catalog resource type (ADR 0039): its faces are binaries of its own. */
@Component
class FontResourceType : ResourceType<FontResource> {
    override val name = FontStore.FONT
    override val wireClass = FontResource::class.java
}
