// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.identity

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class CatalogResourceIdentityIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `command-created resources receive stable internal identities`() {
        val tenant = createTenant("Resource identities")
        val tenantId = TenantId(tenant.id)
        val catalogKey = CatalogKey.of("letters")
        val catalogId = CatalogId(catalogKey, tenantId)
        val stencilId = StencilId(StencilKey.of("header"), catalogId)

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId), "Brand").execute()
            CreateStencil(stencilId, "Header").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId), "Invoice").execute()
        }

        val resources = jdbi.withHandle<List<IdentityRow>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT resource_id, resource_type, catalog_key::text, resource_key
                FROM catalog_resources
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                ORDER BY resource_type
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("catalogKey", catalogKey)
                .map { rs, _ ->
                    IdentityRow(
                        rs.getObject("resource_id", UUID::class.java),
                        rs.getString("resource_type"),
                        rs.getString("catalog_key"),
                        rs.getString("resource_key"),
                    )
                }.list()
        }

        assertThat(resources.map { it.type to it.key }).containsExactly(
            "stencil" to "header",
            "template" to "invoice",
            "theme" to "brand",
        )
        assertThat(resources.map { it.resourceId }).doesNotHaveDuplicates()
        assertThat(resources).allMatch { it.catalogKey == "letters" }

        withMediator { DeleteStencil(stencilId).execute() }

        val deletedIdentityExists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT EXISTS(
                    SELECT 1 FROM catalog_resources
                    WHERE tenant_key = :tenantKey
                      AND resource_type = 'stencil'
                      AND catalog_key = :catalogKey
                      AND resource_key = 'header'
                )
                """,
            )
                .bind("tenantKey", tenant.id)
                .bind("catalogKey", catalogKey)
                .mapTo(Boolean::class.java)
                .one()
        }
        assertThat(deletedIdentityExists).isFalse()
    }

    private data class IdentityRow(
        val resourceId: UUID,
        val type: String,
        val catalogKey: String,
        val key: String,
    )

    /**
     * The sync trigger's INSERT branch reads the registry for an identity already at this address,
     * then writes one. Between those two steps another transaction can do the same, and both would
     * register a different identity at the same public address -- after which the address resolves
     * to whichever row is read first, and the loser's aliases and generation history point at an
     * identity nothing can reach by name.
     *
     * Nothing in the trigger prevents that; the unique constraint on the address does, which is why
     * it is worth a test of its own. Racing two threads and hoping they collide proves nothing --
     * run that way they simply happen one after the other and any arrangement passes. So the
     * overlap is made real: one create runs inside a transaction this test holds open, taking the
     * address's index entry without committing it, while the second attempts the same address.
     */
    @Test
    fun `two transactions cannot register different identities at one address`() {
        val tenant = createTenant("Identity race")
        val catalogKey = CatalogKey.of("letters")
        val themeId = ThemeId(ThemeKey.of("brand"), CatalogId(catalogKey, TenantId(tenant.id)))
        withMediator { CreateCatalog(tenant.id, catalogKey, "Letters").execute() }

        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Executors.newSingleThreadExecutor()
        val contenderThread = Executors.newSingleThreadExecutor()
        try {
            val holder = holderThread.submit {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        CreateTheme(id = themeId, name = "Brand").execute()
                        holderReady.countDown()
                        // Hold the address's uncommitted index entry while the contender tries for it.
                        releaseHolder.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
            assertThat(holderReady.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()

            val contender = contenderThread.submit<Result<Unit>> {
                withMediator {
                    runCatching {
                        CreateTheme(id = themeId, name = "Brand again").execute()
                        Unit
                    }
                }
            }

            // The contender blocks on the uncommitted index entry rather than registering a second
            // identity, and is refused once the holder commits.
            assertThatThrownBy { contender.get(1, TimeUnit.SECONDS) }
                .describedAs("the contender must block on the held address, not race past it")
                .isInstanceOf(TimeoutException::class.java)
            releaseHolder.countDown()
            holder.get(HOLD_SECONDS, TimeUnit.SECONDS)
            assertThat(contender.get(HOLD_SECONDS, TimeUnit.SECONDS).isFailure)
                .describedAs("the second create at the same address must be refused")
                .isTrue()
        } finally {
            releaseHolder.countDown()
            holderThread.shutdownNow()
            contenderThread.shutdownNow()
        }

        val registered = jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createQuery(
                    "SELECT count(*) FROM catalog_resources WHERE tenant_key = :t " +
                        "AND resource_type = 'theme' AND catalog_key = :c AND resource_key = 'brand'",
                )
                .bind("t", tenant.id)
                .bind("c", catalogKey)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(registered).describedAs("one address, one identity").isEqualTo(1)
    }

    private companion object {
        const val HOLD_SECONDS = 10L
    }
}
