// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseIsReleasedException
import app.epistola.suite.catalog.releases.GetCatalogDraft
import app.epistola.suite.catalog.revisions.ReleaseInUseException
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.invariants.CatalogInvariants
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two catalog commands at once, in the interleavings the TLA+ model of them
 * (`specs/tla/CatalogConcurrency.tla`) found to break a catalog invariant: one command's transaction
 * is held open after its write, and the other runs meanwhile. Each check reads only what is committed,
 * so without a lock taken before it, the second command's check cannot see the first's write.
 */
class CatalogConcurrencyTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    @Test
    fun `two catalogs declaring each other at once do not both get through`() {
        val tenant = createTenant("Concurrent declarations").id
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, shared, "Shared").execute()
        }

        val second = whileHeldOpen(
            hold = { declareDependency(tenant, letters, on = shared) },
            contend = { declareDependency(tenant, shared, on = letters) },
        )

        assertThat(second.exceptionOrNull()).describedAs("'shared' using 'letters' while 'letters' uses 'shared'")
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("uses")
        assertThat(withMediator { ListCatalogDependencies(testCatalog(tenant, shared)).query() }).isEmpty()
    }

    @Test
    fun `a release is not deleted while a draft pinning it is being saved`() {
        val tenant = createTenant("Concurrent pin of a draft").id
        val release = withMediator {
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            mediator.releaseNext(testCatalog(tenant, shared))
        }

        val second = whileHeldOpen(
            hold = { declareDependency(tenant, letters, on = shared, release = release) },
            contend = { DeleteCatalogRelease(testCatalog(tenant, shared), release).execute() },
        )

        assertThat(second.exceptionOrNull()).isInstanceOf(ReleaseInUseException::class.java)
        assertThat(withMediator { GetCatalog(testCatalog(tenant, shared)).query()!!.latestReleaseVersion }).isEqualTo(release)
    }

    @Test
    fun `a release is not deleted while a release pinning it is being cut`() {
        val tenant = createTenant("Concurrent pin of a release").id
        val release = withMediator {
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            val release = mediator.releaseNext(testCatalog(tenant, shared))
            // Letters names shared's next draft, which has nothing of its own to release: releasing
            // letters pins that draft's base, the release deleted below.
            val target = GetCatalog(testCatalog(tenant, shared)).query()!!
            val draft = GetCatalogDraft(testCatalog(tenant, shared)).query()!!.versionId
            UpdateCatalogDependencies(testCatalog(tenant, letters), listOf(DeclaredCatalogDependency(target.registry, target.namespace, shared.value, versionId = draft))).execute()
            release
        }

        val second = whileHeldOpen(
            hold = { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() },
            contend = { DeleteCatalogRelease(testCatalog(tenant, shared), release).execute() },
        )

        assertThat(second.exceptionOrNull()).isInstanceOf(ReleaseInUseException::class.java)
        assertThat(withMediator { GetCatalog(testCatalog(tenant, shared)).query()!!.latestReleaseVersion }).isEqualTo(release)
    }

    @Test
    fun `a draft is not changed while it is being released`() {
        val tenant = createTenant("Concurrent write to a draft being released").id
        val draft = withMediator {
            CreateCatalog(tenant, shared, "Shared").execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            GetCatalogDraft(testCatalog(tenant, shared)).query()!!.versionId
        }
        val target = withMediator { GetCatalog(testCatalog(tenant, shared)).query()!! }

        val second = whileHeldOpen(
            hold = { ReleaseCatalogDraft(testCatalog(tenant, letters), "1.0.0").execute() },
            contend = {
                UpdateCatalogDependencies(testCatalog(tenant, letters), listOf(DeclaredCatalogDependency(target.registry, target.namespace, shared.value, versionId = draft))).execute()
            },
        )

        // Refused, or written into the next draft as it would be had it run after: never into the release.
        second.exceptionOrNull()?.let { assertThat(it).isInstanceOf(CatalogReleaseIsReleasedException::class.java) }
        assertThat(CatalogInvariants.check(jdbi, tenant.value)).isEmpty()
    }

    /**
     * Runs [hold] in a transaction held open, then [contend] on another thread, and lets the holder
     * commit once the contender is blocked behind it or has finished without waiting: the overlap is
     * real, not two commands that happened to run one after the other.
     */
    private fun whileHeldOpen(hold: () -> Unit, contend: () -> Any?): Result<Any?> {
        val holderPid = AtomicInteger()
        val holderReady = CountDownLatch(1)
        val releaseHolder = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val holder = threads.submit {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        hold()
                        holderPid.set(jdbi.withHandle<Int, Exception> { it.createQuery("SELECT pg_backend_pid()").mapTo(Int::class.java).one() })
                        holderReady.countDown()
                        releaseHolder.await(HOLD_SECONDS, TimeUnit.SECONDS)
                    }
                }
            }
            assertThat(holderReady.await(HOLD_SECONDS, TimeUnit.SECONDS)).describedAs("holder ready").isTrue()

            val contender = threads.submit<Result<Any?>> { withMediator { runCatching { contend() } } }
            awaitBlockedBehindOrDone(holderPid.get(), contender)
            releaseHolder.countDown()

            holder.get(HOLD_SECONDS, TimeUnit.SECONDS)
            return contender.get(HOLD_SECONDS, TimeUnit.SECONDS)
        } finally {
            releaseHolder.countDown()
            threads.shutdownNow()
        }
    }

    /** Polls until some session waits on a lock [pid] holds, or [contender] is done. A condition, not a sleep. */
    private fun awaitBlockedBehindOrDone(pid: Int, contender: Future<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HOLD_SECONDS)
        while (System.nanoTime() < deadline) {
            if (contender.isDone) return
            val blocked = jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid)))")
                    .bind("pid", pid)
                    .mapTo(Boolean::class.java)
                    .one()
            }
            if (blocked) return
            Thread.onSpinWait()
        }
        throw AssertionError("the contender neither finished nor blocked behind the held transaction")
    }

    private companion object {
        const val HOLD_SECONDS = 30L
    }
}
