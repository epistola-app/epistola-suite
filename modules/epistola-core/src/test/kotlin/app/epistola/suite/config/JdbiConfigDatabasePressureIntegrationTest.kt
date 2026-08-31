// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.database.DatabasePressureMonitor
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired

/**
 * Verifies the [DatabasePressureMonitor] is genuinely wired into the shared [Jdbi] bean's
 * `SqlLogger` (in `JdbiConfig`), not just correct in isolation -- including that a real
 * Postgres statement-timeout cancellation reports SQLSTATE `57014` the way
 * `DatabasePressureMonitor.isCriticalSqlState` expects.
 */
class JdbiConfigDatabasePressureIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var databasePressureMonitor: DatabasePressureMonitor

    @Test
    fun `successful statements feed the pressure monitor`() {
        val before = databasePressureMonitor.snapshot(EpistolaClock.instant().toEpochMilli()).sampleCount

        jdbi.withHandle<Int, Exception> { handle -> handle.createQuery("SELECT 1").mapTo<Int>().one() }

        val after = databasePressureMonitor.snapshot(EpistolaClock.instant().toEpochMilli()).sampleCount
        assertThat(after).isGreaterThan(before)
    }

    @Test
    fun `a real statement-timeout cancellation is recognised as critical`() {
        jdbi.useTransaction<Exception> { handle ->
            // SET LOCAL scopes the timeout to this transaction only, so it can never leak
            // onto a pooled connection reused by a later, unrelated statement.
            handle.execute("SET LOCAL statement_timeout = '1ms'")
            assertThrows<UnableToExecuteStatementException> {
                // The mapped type is irrelevant: pg_sleep is cancelled server-side by the
                // timeout before any row is ever produced or mapped.
                handle.createQuery("SELECT pg_sleep(0.05)").mapTo<Int>().findOne()
            }
        }

        assertThat(databasePressureMonitor.snapshot(EpistolaClock.instant().toEpochMilli()).criticalFailureObserved).isTrue()
    }
}
