// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Releasing a catalog checks every stencil instance in it against the stencil the release holds
 * (contract ADR 0006): a template published while it fitted is refused once the stencil changed
 * under it, until either is fixed.
 */
class ReleaseStencilCheckTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun letterhead(placeholderName: String) = stencilDocument {
        text("logo", "LOGO")
        placeholder("slot", placeholderName)
        build("logo", "slot")
    }

    private val templateFillingBody = stencilDocument {
        text("my-body", "Dear customer")
        placeholder("fill", "body", fill = listOf("my-body"))
        stencil("inst", "letterhead", listOf("fill"))
        build("inst")
    }

    /** `letters` with a letterhead stencil and an invoice published while it filled `body`. */
    private fun setUp(): Pair<TenantId, StencilId> = withMediator {
        val tenantId = TenantId(createTenant("Release stencil check").id)
        val catalogId = CatalogId(letters, tenantId)
        CreateCatalog(tenantId.key, letters, "Letters").execute()
        val stencil = StencilId(StencilKey.of("letterhead"), catalogId)
        CreateStencil(stencil, "Letterhead", content = letterhead("body")).execute()
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalogId))
        CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
        PublishVersion(VersionId(UpdateDraft(variant, templateFillingBody).execute()!!.id, variant)).execute()
        tenantId to stencil
    }

    @Test
    fun `a catalog whose instances fit its stencils releases`() {
        val (tenantId, _) = setUp()

        val result = withMediator { ReleaseCatalogVersion(tenantId.key, letters, "1.0.0").execute() }

        assertThat(result.version).isEqualTo("1.0.0")
    }

    @Test
    fun `a release is refused when a stencil lost a placeholder a template fills`() {
        val (tenantId, stencil) = setUp()
        withMediator { UpdateStencil(stencil, content = letterhead("content")).execute() }

        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenantId.key, letters, "1.0.0").execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_REFERENCE_INVALID)
            .hasMessageContaining("template 'invoice' fills placeholder 'body', which stencil letters/letterhead does not have")
    }

    @Test
    fun `a release is refused when a stencil's own content uses a stencil that does not fit`() {
        val (tenantId, stencil) = setUp()
        withMediator {
            // A frame stencil that fills a placeholder the letterhead does not have.
            CreateStencil(
                StencilId(StencilKey.of("frame"), stencil.catalogId),
                "Frame",
                content = stencilDocument {
                    text("frame-text", "framed")
                    placeholder("frame-fill", "footer", fill = listOf("frame-text"))
                    stencil("inner", "letterhead", listOf("frame-fill"))
                    build("inner")
                },
            ).execute()
        }

        assertThatThrownBy { withMediator { ReleaseCatalogVersion(tenantId.key, letters, "1.0.0").execute() } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("stencil 'frame' fills placeholder 'footer'")
    }
}
