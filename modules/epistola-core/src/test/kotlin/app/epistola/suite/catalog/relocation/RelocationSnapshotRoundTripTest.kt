// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.snapshot.BuildTenantSnapshot
import app.epistola.suite.catalog.snapshot.RestoreTenantSnapshot
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A tenant snapshot is how a tenant is backed up and restored. After moves of every kind the
 * snapshot must still build, restore to the same content, and keep each moved resource's identity
 * at its new address.
 */
class RelocationSnapshotRoundTripTest : RelocationTestSupport() {

    @Test
    fun `a tenant restores from a snapshot taken after moves of every kind`() {
        val tenant = tenantWith("Snapshot after moves")
        importFont(tenant, letters, "acme")
        uploadPng(tenant, letters, "logo", renderablePng())
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        publishTemplate(
            tenant,
            letters,
            singleNodeModel(Node(id = "logo", type = "image", props = mapOf("assetId" to "logo", "catalogKey" to letters.value)), usesTheme("brand", letters.value).themeRef),
        ) {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand", documentStyles = fontStyle("acme", letters.value)).execute()
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("acme")).execute()
        }
        withMediator { UpdateVariant(templateVariant(tenant, letters), "Main", mapOf("letters.brand" to "acme")).execute() }

        val moved = listOf(
            address(CatalogResourceType.FONT, letters, "acme"),
            address(CatalogResourceType.IMAGE, letters, "logo"),
            address(CatalogResourceType.THEME, letters, "brand"),
            address(CatalogResourceType.STENCIL, letters, "header"),
            address(CatalogResourceType.ATTRIBUTE, letters, "brand"),
        )
        move(tenant, moved.map { it.movedTo(shared) })
        // The published invoice still names letters, where nothing is left, so its catalog cannot be
        // snapshotted until the invoice is republished against where things live now.
        withMediator {
            CreateVersion(templateVariant(tenant, letters)).execute()
            UpdateDraft(
                templateVariant(tenant, letters),
                singleNodeModel(Node(id = "logo", type = "image", props = mapOf("assetId" to "logo", "catalogKey" to shared.value)), usesTheme("brand", shared.value).themeRef),
            ).execute()
            PublishVersion(VersionId(GetDraft(templateVariant(tenant, letters)).query()!!.id, templateVariant(tenant, letters))).execute()
        }
        val identities = moved.associateWith { identityAt(tenant, it.copy(catalogKey = shared.value)) }

        val snapshot = withMediator { BuildTenantSnapshot(tenant).execute() }
        withMediator { RestoreTenantSnapshot(tenant, snapshot.bytes).execute() }

        assertThat(withMediator { BuildTenantSnapshot(tenant).execute() }.snapshotFingerprint).isEqualTo(snapshot.snapshotFingerprint)
        for (address in moved) {
            assertThat(identityAt(tenant, address.copy(catalogKey = shared.value)))
                .describedAs("%s after restore", address.id)
                .isNotNull()
                .isEqualTo(identities[address])
        }
    }
}
