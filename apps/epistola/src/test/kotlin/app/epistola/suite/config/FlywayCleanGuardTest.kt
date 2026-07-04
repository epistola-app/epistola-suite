package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the database-reset guardrail: `flyway.clean()` may run only
 * under the `local` profile, no matter what `spring.flyway.clean-disabled` says.
 */
class FlywayCleanGuardTest {

    private val config = FlywayConfig()

    @Test
    fun `clean stays disabled when the property disables it, regardless of profile`() {
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = true, localProfileActive = true)).isTrue()
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = true, localProfileActive = false)).isTrue()
    }

    @Test
    fun `clean is permitted only under the local profile`() {
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = true)).isFalse()
    }

    @Test
    fun `clean is force-disabled outside the local profile even when the property enables it`() {
        // The prod misconfiguration case: SPRING_FLYWAY_CLEAN_DISABLED=false with no local profile.
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = false)).isTrue()
    }
}
