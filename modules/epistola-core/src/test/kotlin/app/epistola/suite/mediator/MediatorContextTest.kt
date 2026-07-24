// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.time.EpistolaClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class MediatorContextTest {
    @Test
    fun `runWithMediator binds mediator and captured clock`() {
        val instant = Instant.parse("2026-06-10T10:15:30Z")
        val mediator = TestMediator

        val observed = EpistolaClock.withInstant(instant) {
            MediatorContext.runWithMediator(mediator) {
                MediatorContext.current() to MediatorContext.currentClock().instant()
            }
        }

        assertThat(observed.first).isSameAs(mediator)
        assertThat(observed.second).isEqualTo(instant)
    }

    @Test
    fun `runWithMediator binds explicit clock scope`() {
        val instant = Instant.parse("2026-06-11T10:15:30Z")

        val observed = EpistolaClock.withInstant(instant) {
            MediatorContext.runWithMediator(TestMediator) {
                MediatorContext.currentClock().instant()
            }
        }

        assertThat(observed).isEqualTo(instant)
    }

    @Test
    fun `runnable captures effective clock and principal`() {
        val instant = Instant.parse("2026-06-12T10:15:30Z")
        val principal = principal()
        var observed: Triple<Mediator, Instant, EpistolaPrincipal>? = null

        val capturingRunnable = EpistolaClock.withInstant(instant) {
            SecurityContext.runWithPrincipal(principal) {
                MediatorContext.runWithMediator(TestMediator) {
                    MediatorContext.runnable(TestMediator) {
                        observed = Triple(
                            MediatorContext.current(),
                            MediatorContext.currentClock().instant(),
                            SecurityContext.current(),
                        )
                    }
                }
            }
        }

        capturingRunnable.run()
        val actual = requireNotNull(observed)

        assertThat(actual.first).isSameAs(TestMediator)
        assertThat(actual.second).isEqualTo(instant)
        assertThat(actual.third).isEqualTo(principal)
    }

    @Test
    fun `runnable can bind an explicit principal`() {
        val instant = Instant.parse("2026-06-13T10:15:30Z")
        val principal = principal()
        var observed: Triple<Mediator, Instant, EpistolaPrincipal>? = null

        val capturingRunnable = EpistolaClock.withInstant(instant) {
            MediatorContext.runnable(TestMediator, principal) {
                observed = Triple(
                    MediatorContext.current(),
                    MediatorContext.currentClock().instant(),
                    SecurityContext.current(),
                )
            }
        }

        capturingRunnable.run()
        val actual = requireNotNull(observed)

        assertThat(actual.first).isSameAs(TestMediator)
        assertThat(actual.second).isEqualTo(instant)
        assertThat(actual.third).isEqualTo(principal)
    }

    private fun principal(): EpistolaPrincipal = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000123"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = mapOf(TenantKey.of("tenant-a") to setOf(TenantRole.CONTENT_VIEWER)),
        currentTenantId = TenantKey.of("tenant-a"),
    )

    private object TestMediator : Mediator {
        override fun <R> send(command: Command<R>): R = error("not used")

        override fun <R> query(query: Query<R>): R = error("not used")
    }
}
