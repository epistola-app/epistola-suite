// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ResourceStatus
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.stencils.Stencil
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
    fun `stencil working-copy DTO retains the catalog document instance`() {
        val stencil = Stencil(
            id = StencilKey.of("address"),
            tenantKey = TenantKey.of("tenant"),
            name = "Address",
            content = document,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        assertSame(document, stencil.toContentDto(ResourceStatus.MODIFIED).content)
    }
}
