package app.epistola.suite.mediator

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MediatorContextTest {
    @Test
    fun `runWithMediator binds mediator and captured clock`() {
        val instant = Instant.parse("2026-06-10T10:15:30Z")
        val mediator = TestMediator

        val observed = app.epistola.suite.time.EpistolaClock.withInstant(instant) {
            MediatorContext.runWithMediator(mediator) {
                MediatorContext.current() to MediatorContext.currentClock().instant()
            }
        }

        assertThat(observed.first).isSameAs(mediator)
        assertThat(observed.second).isEqualTo(instant)
    }

    @Test
    fun `runWithContext binds explicit clock`() {
        val instant = Instant.parse("2026-06-11T10:15:30Z")

        val observed = MediatorContext.runWithContext(
            MediatorExecutionContext(TestMediator, Clock.fixed(instant, ZoneId.of("UTC"))),
        ) {
            MediatorContext.currentClock().instant()
        }

        assertThat(observed).isEqualTo(instant)
    }

    @Test
    fun `capture includes effective clock and principal`() {
        val instant = Instant.parse("2026-06-12T10:15:30Z")
        val principal = principal()

        val context = app.epistola.suite.time.EpistolaClock.withInstant(instant) {
            SecurityContext.runWithPrincipal(principal) {
                MediatorContext.runWithMediator(TestMediator) {
                    MediatorContext.capture()
                }
            }
        }

        assertThat(context.mediator).isSameAs(TestMediator)
        assertThat(context.clock.instant()).isEqualTo(instant)
        assertThat(context.principal).isEqualTo(principal)
    }

    @Test
    fun `context bind restores mediator clock and principal`() {
        val instant = Instant.parse("2026-06-13T10:15:30Z")
        val principal = principal()
        val context = MediatorExecutionContext(
            mediator = TestMediator,
            clock = Clock.fixed(instant, ZoneId.of("UTC")),
            principal = principal,
        )

        val observed = context.bind {
            Triple(
                MediatorContext.current(),
                MediatorContext.currentClock().instant(),
                SecurityContext.current(),
            )
        }

        assertThat(observed.first).isSameAs(TestMediator)
        assertThat(observed.second).isEqualTo(instant)
        assertThat(observed.third).isEqualTo(principal)
    }

    private fun principal(): EpistolaPrincipal = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000123"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = mapOf(TenantKey.of("tenant-a") to setOf(TenantRole.READER)),
        currentTenantId = TenantKey.of("tenant-a"),
    )

    private object TestMediator : Mediator {
        override fun <R> send(command: Command<R>): R = error("not used")

        override fun <R> query(query: Query<R>): R = error("not used")
    }
}
