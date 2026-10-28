// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.assets

import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.types.ResourceType
import org.springframework.stereotype.Component

/** An image as a catalog resource type (ADR 0039): its bytes are a binary of its own. */
@Component
class ImageResourceType : ResourceType<ImageResource> {
    override val name = ImageStore.IMAGE
    override val wireClass = ImageResource::class.java
}
