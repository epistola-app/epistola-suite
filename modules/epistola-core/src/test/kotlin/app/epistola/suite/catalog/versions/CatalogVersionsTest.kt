// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A catalog consists of versions (contract ADR 0002): released ones and, for an authored catalog,
 * the one draft its working copy is. Releasing the draft opens the next patch.
 */
class CatalogVersionsTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        tenant
    }

    private fun versions(tenant: TenantKey) = withMediator { ListCatalogVersions(tenant, letters).query() }.map { it.version to it.status }

    @Test
    fun `a new catalog has draft 1_0_0, and releasing it opens the next patch on it`() {
        val tenant = catalog("versions-draft")
        assertThat(versions(tenant)).containsExactly("1.0.0" to CatalogVersionStatus.DRAFT)

        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant))) }

        assertThat(versions(tenant)).containsExactly("1.0.1" to CatalogVersionStatus.DRAFT, "1.0.0" to CatalogVersionStatus.RELEASED)
        val draft = withMediator { GetCatalogVersion(tenant, letters, "1.0.1").query()!! }
        assertThat(draft.base).isEqualTo("1.0.0")
        assertThat(withMediator { GetCatalogVersion(tenant, letters, "1.0.0").query()!! }.contentRetained).isTrue()
    }

    @Test
    fun `a draft can be renumbered above every release, and a release of it keeps that number`() {
        val tenant = catalog("versions-renumber")
        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant))) }

        withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "2.0.0").execute() }
        assertThat(versions(tenant).first()).isEqualTo("2.0.0" to CatalogVersionStatus.DRAFT)

        withMediator { ReleaseCatalogVersion(tenant, letters, "2.0.0").execute() }
        assertThat(versions(tenant).map { it.first }).containsExactly("2.0.1", "2.0.0", "1.0.0")
    }

    @Test
    fun `a renumber is refused onto a number in use, below the latest release, or of a released version`() {
        val tenant = catalog("versions-refused")
        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant))) }

        assertThatThrownBy { withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "1.0.0").execute() } }
            .isInstanceOf(CatalogVersionInUseException::class.java)
        assertThatThrownBy { withMediator { RenumberCatalogVersion(tenant, letters, "1.0.1", "0.9.0").execute() } }
            .isInstanceOf(SingleCatalogDraftException::class.java)
            .hasMessageContaining("not above 1.0.0")
        assertThatThrownBy { withMediator { RenumberCatalogVersion(tenant, letters, "1.0.0", "3.0.0").execute() } }
            .isInstanceOf(CatalogVersionReleasedException::class.java)
        assertThat(withMediator { PreviewCatalogVersionRenumber(tenant, letters, "1.0.1", "1.1.0").query() }).isEmpty()
    }

    @Test
    fun `a second draft cannot be opened, nor the one discarded, until drafts keep their own content`() {
        val tenant = catalog("versions-single")

        assertThatThrownBy { withMediator { CreateCatalogVersion(tenant, letters, "2.0.0").execute() } }
            .isInstanceOf(SingleCatalogDraftException::class.java)
        assertThatThrownBy { withMediator { DeleteCatalogVersion(tenant, letters, "1.0.0").execute() } }
            .isInstanceOf(SingleCatalogDraftException::class.java)
    }
}
