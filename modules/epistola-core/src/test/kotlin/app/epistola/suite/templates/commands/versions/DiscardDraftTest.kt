// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DraftHasNoPublishedBaseException
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(30)
class DiscardDraftTest : IntegrationTestBase() {

    private lateinit var templateId: TemplateId
    private lateinit var defaultVariantId: VariantId

    @BeforeEach
    fun createTemplate() {
        val tenant = createTenant("DiscardDraft Test")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "discard-draft-test").execute()
        }
        defaultVariantId = VariantId(
            VariantKey.INITIAL,
            templateId,
        )
    }

    /** Publishes the auto-created v1 draft, then opens a fresh draft (v2) on top of it. */
    private fun publishThenCreateDraft() {
        val draft = withMediator { GetDraft(defaultVariantId).query()!! }
        withMediator { PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute() }
        withMediator { CreateVersion(defaultVariantId).execute() }
    }

    @Test
    fun `discards the draft and keeps the published version`() {
        publishThenCreateDraft()
        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNotNull

        withMediator { DiscardDraft(variantId = defaultVariantId).execute() }

        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNull()
        val published = withMediator { GetLatestPublishedVersion(defaultVariantId).query() }
        assertThat(published).isNotNull
        assertThat(published!!.status).isEqualTo(VersionStatus.PUBLISHED)
    }

    @Test
    fun `throws when the variant has never been published`() {
        // Fresh template: a draft v1 exists but nothing is published yet.
        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNotNull

        assertThatThrownBy {
            withMediator { DiscardDraft(variantId = defaultVariantId).execute() }
        }.isInstanceOf(DraftHasNoPublishedBaseException::class.java)

        // The draft is left intact.
        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNotNull
    }

    @Test
    fun `is a no-op when no draft exists`() {
        // Publish v1 and do not open a new draft.
        val draft = withMediator { GetDraft(defaultVariantId).query()!! }
        withMediator { PublishVersion(versionId = VersionId(draft.id, defaultVariantId)).execute() }
        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNull()

        withMediator { DiscardDraft(variantId = defaultVariantId).execute() }

        assertThat(withMediator { GetDraft(defaultVariantId).query() }).isNull()
        assertThat(withMediator { GetLatestPublishedVersion(defaultVariantId).query() }).isNotNull
    }
}
