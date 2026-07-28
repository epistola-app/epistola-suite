// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertSame

class PortableModelDtoMappersTest {
    private val document = TemplateDocument(
        root = "n-root",
        nodes = mapOf("n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root"))),
        slots = mapOf("s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children")),
    )
    private val createdAt = OffsetDateTime.parse("2026-07-28T10:00:00Z")

    @Test
    fun `template version DTO retains the catalog document instance`() {
        val version = TemplateVersion(
            id = VersionKey.of(1),
            tenantKey = TenantKey.of("tenant"),
            variantKey = VariantKey.of("default"),
            templateModel = document,
            status = VersionStatus.DRAFT,
            createdAt = createdAt,
            publishedAt = null,
            archivedAt = null,
        )

        assertSame(document, version.toDto().templateModel)
    }

    @Test
    fun `stencil version DTO retains the catalog document instance`() {
        val version = StencilVersion(
            id = VersionKey.of(1),
            tenantKey = TenantKey.of("tenant"),
            stencilKey = StencilKey.of("address"),
            content = document,
            status = StencilVersionStatus.DRAFT,
            createdAt = createdAt,
            publishedAt = null,
            archivedAt = null,
        )

        assertSame(document, version.toDto().content)
    }
}
