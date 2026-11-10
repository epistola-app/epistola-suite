// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import app.epistola.suite.support.requests.sync.SupportRequestRoundLock
import app.epistola.suite.testing.IntegrationTestBase
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/**
 * The feedback round's cluster lock. "Another node" is a second round holding the lock on a thread of
 * its own, as another node's round does: the lock is a lease row in the database, not a session.
 */
@org.junit.jupiter.api.parallel.Isolated("It counts the borrowed connections of the pool every test class in this context shares")
class SupportRequestRoundLockIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var lock: SupportRequestRoundLock

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    /** Holds the lock in a round of its own until [letGo], as another node's round does. */
    private fun otherNodeHolds(letGo: CountDownLatch) {
        val holding = CountDownLatch(1)
        Thread.ofVirtual().start {
            lock.runExclusively(Duration.ofSeconds(30)) {
                holding.countDown()
                letGo.await(30, TimeUnit.SECONDS)
            }
        }
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `a round waits for another node's round and then runs`() {
        val letGo = CountDownLatch(1)
        otherNodeHolds(letGo)
        val ran = AtomicBoolean(false)
        val result = AtomicBoolean(false)
        val waiting = Thread.ofVirtual().start { result.set(lock.runExclusively(Duration.ofSeconds(10)) { ran.set(true) }) }

        waiting.join(Duration.ofMillis(600))
        assertThat(ran.get()).`as`("not while the other node's round runs").isFalse()
        letGo.countDown()
        waiting.join(Duration.ofSeconds(10))

        assertThat(ran.get()).isTrue()
        assertThat(result.get()).isTrue()
    }

    @Test
    fun `a round gives up after its wait when another node's round goes on`() {
        val letGo = CountDownLatch(1)
        otherNodeHolds(letGo)
        try {
            val ran = AtomicBoolean(false)
            val started = System.nanoTime()

            val result = lock.runExclusively(Duration.ofMillis(500)) { ran.set(true) }

            assertThat(result).isFalse()
            assertThat(ran.get()).isFalse()
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        } finally {
            letGo.countDown()
        }
    }

    /**
     * A round lasts its budget plus the call under way, longer than the pool's leak detection
     * threshold allows a connection to be borrowed: holding one for the round (as a session-level
     * advisory lock must) logs false "connection leak" warnings while the service is slow, and does
     * not survive a pooler in transaction mode. The round's commands run in their own transactions.
     */
    @Test
    fun `a round holds no pooled connection while it runs, and its commands run in their own transactions`() {
        val pool = dataSource.unwrap(HikariDataSource::class.java).hikariPoolMXBean
        var borrowedDuringRound = Int.MAX_VALUE
        var autoCommitInTransaction: Boolean? = null

        val ran = lock.runExclusively(Duration.ofSeconds(10)) {
            // The fewest borrowed over a few looks: other work may borrow one for a moment.
            repeat(5) {
                borrowedDuringRound = minOf(borrowedDuringRound, pool.activeConnections)
                Thread.sleep(20)
            }
            autoCommitInTransaction = TransactionTemplate(transactionManager).execute { jdbi.withHandle<Boolean, Exception> { it.connection.autoCommit } }
        }

        assertThat(ran).isTrue()
        assertThat(borrowedDuringRound).`as`("connections borrowed while the round runs").isZero()
        assertThat(autoCommitInTransaction).`as`("joins the Spring transaction").isFalse()
    }

    /**
     * A node that dies mid-round leaves its lease behind; once it ran out, by the database's clock,
     * the next round takes over. Raw SQL ages the lease: a test cannot move the database's clock.
     */
    @Test
    fun `a lease left by a round on a node that died is taken over once it runs out`() {
        val letGo = CountDownLatch(1)
        otherNodeHolds(letGo)
        try {
            assertThat(lock.runExclusively(Duration.ZERO) {}).`as`("while the other round may still run").isFalse()

            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    "UPDATE app_metadata SET value = jsonb_set(value, '{claimedAt}', to_jsonb((value ->> 'claimedAt')::timestamptz - make_interval(secs => :s))) WHERE key = :key",
                )
                    .bind("s", lock.lease.seconds + 1)
                    .bind("key", SupportRequestRoundLock.KEY)
                    .execute()
            }

            val ran = AtomicBoolean(false)
            assertThat(lock.runExclusively(Duration.ZERO) { ran.set(true) }).isTrue()
            assertThat(ran.get()).isTrue()
        } finally {
            letGo.countDown()
        }
    }

    @Test
    fun `the lock is free again after a round, also one that failed`() {
        // Other test classes' rounds may hold it for a moment: a wait, but a bounded one.
        runCatching { lock.runExclusively(Duration.ofSeconds(10)) { error("the round failed") } }

        val ran = AtomicBoolean(false)
        assertThat(lock.runExclusively(Duration.ofSeconds(10)) { ran.set(true) }).isTrue()
        assertThat(ran.get()).isTrue()
    }
}
