// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releases.CatalogReleaseIsReleasedException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Two people release the same draft at once. The second must be told the version was just released
 * (a 409 on every surface), not fail on an internal check (a 500).
 *
 * Racing two threads and hoping they collide proves nothing, so the overlap is made real: the first
 * release runs in a transaction this test holds open, and the second starts while it is uncommitted.
 * The second reads the draft as still a draft, then waits on the first, and finds it released.
 */
class ConcurrentReleaseTest : IntegrationTestBase() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `the second of two concurrent releases of one draft is told it was just released`() {
        val tenant = createTenant("Release race")
        val catalogKey = CatalogKey.of("race-cat")
        val catalog = testCatalog(tenant.id, catalogKey)
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Race Cat").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("th1"), testCatalog(TenantId(tenant.id), catalogKey)), name = "T1").execute()
            MarkCatalogReady(catalog).execute()
        }

        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val holderThread = Executors.newSingleThreadExecutor()
        val contenderThread = Executors.newSingleThreadExecutor()
        try {
            val holder = holderThread.submit {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        ReleaseCatalogDraft(catalog = catalog, version = "1.0.0").execute()
                        holderReady.countDown()
                        releaseHolder.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
            assertThat(holderReady.await(HOLD_SECONDS, TimeUnit.SECONDS)).isTrue()

            val contender = contenderThread.submit<ReleaseCatalogDraftResult> {
                withMediator { ReleaseCatalogDraft(catalog = catalog, version = "1.0.0").execute() }
            }
            assertThatThrownBy { contender.get(1, TimeUnit.SECONDS) }
                .describedAs("the second release must wait on the first, not race past it")
                .isInstanceOf(TimeoutException::class.java)

            releaseHolder.countDown()
            holder.get(HOLD_SECONDS, TimeUnit.SECONDS)
            assertThatThrownBy { contender.get(HOLD_SECONDS, TimeUnit.SECONDS) }
                .isInstanceOf(ExecutionException::class.java)
                .cause()
                .isInstanceOf(CatalogReleaseIsReleasedException::class.java)
                .hasMessageContaining("released as 1.0.0")
        } finally {
            releaseHolder.countDown()
            holderThread.shutdownNow()
            contenderThread.shutdownNow()
        }

        val released = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT count(*) FROM catalog_versions WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND state = 'released'")
                .bind("t", tenant.id)
                .bindCatalog("c", catalog)
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(released).describedAs("one draft, one release").isEqualTo(1)
    }

    private companion object {
        const val HOLD_SECONDS = 10L
    }
}
