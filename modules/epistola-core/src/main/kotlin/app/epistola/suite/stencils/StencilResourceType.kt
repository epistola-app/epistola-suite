// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.protocol.StencilResource
import app.epistola.suite.catalog.types.ResourceType
import org.springframework.stereotype.Component

/** A stencil as a catalog resource type (ADR 0039): its document names a theme, fonts and images. */
@Component
class StencilResourceType : ResourceType<StencilResource> {
    override val name = StencilStore.STENCIL
    override val wireClass = StencilResource::class.java
    override val installedAfter = setOf("theme", "font", "image")
}
