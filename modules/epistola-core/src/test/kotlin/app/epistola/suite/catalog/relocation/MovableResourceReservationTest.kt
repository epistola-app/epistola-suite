// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.identity.CatalogResourceAddressReservedException
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MovableResourceReservationTest : IntegrationTestBase() {
    /**
     * `requireAddressAvailable` is shared, but each type's create command has to call it, and
     * nothing fails when the call is missed: no alias can exist for a type before it is movable.
     * So every movable type is exercised the same way here -- move one, then try to create a
     * replacement at the address it vacated. Registering a new [MovableResource] makes the `when`
     * below non-exhaustive, so the type cannot become movable without also being covered.
     */
    @Test
    fun `every movable type reserves the address it vacates`() {
        for (movable in MovableResource.entries) {
            val tenant = createTenant("Reserve ${movable.name.lowercase()}")
            val sourceCatalog = CatalogKey.of("letters")
            val targetCatalog = CatalogKey.of("shared")
            val sourceCatalogId = CatalogId(sourceCatalog, TenantId(tenant.id))
            withMediator {
                CreateCatalog(tenant.id, sourceCatalog, "Letters").execute()
                CreateCatalog(tenant.id, targetCatalog, "Shared").execute()
            }
            val create: () -> Unit = when (movable) {
                MovableResource.STENCIL -> ({ CreateStencil(StencilId(StencilKey.of("moved"), sourceCatalogId), "Moved").execute() })
                MovableResource.ATTRIBUTE -> ({ CreateAttributeDefinition(AttributeId(AttributeKey.of("moved"), sourceCatalogId), "Moved").execute() })
                MovableResource.TEMPLATE -> ({ CreateDocumentTemplate(TemplateId(TemplateKey.of("moved"), sourceCatalogId), "Moved").execute() })
                MovableResource.CODE_LIST -> (
                    {
                        CreateCodeList(
                            CodeListId(CodeListKey.of("moved"), sourceCatalogId),
                            displayName = "Moved",
                            sourceType = CodeListSource.INLINE,
                            // An inline code list must have at least one entry.
                            entries = listOf(CodeListEntry("only", "Only")),
                        ).execute()
                    }
                    )
            }
            withMediator { create() }

            val address = ResourceAddress(movable.type, sourceCatalog.value, "moved")
            val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(address.movedTo(targetCatalog))).query() }
            assertThat(preview.blockers).describedAs(movable.name).isEmpty()
            withMediator { MoveCatalogResources(tenant.id, listOf(address.movedTo(targetCatalog)), preview.planFingerprint).execute() }

            assertThatThrownBy { withMediator { create() } }
                .describedAs("%s must reserve the address it vacated", movable.name)
                .isInstanceOf(CatalogResourceAddressReservedException::class.java)
        }
    }
}
