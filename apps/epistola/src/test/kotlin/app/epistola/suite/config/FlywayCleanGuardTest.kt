package app.epistola.suite.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit coverage for the database-reset guardrail: `flyway.clean()` may run only
 * when BOTH the `local` profile is active AND the datasource is a loopback host,
 * no matter what `spring.flyway.clean-disabled` says.
 */
class FlywayCleanGuardTest {

    private val config = FlywayConfig()

    @Test
    fun `clean stays disabled when the property disables it, regardless of profile or host`() {
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = true, localProfileActive = true, loopbackDatasource = true)).isTrue()
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = true, localProfileActive = false, loopbackDatasource = false)).isTrue()
    }

    @Test
    fun `clean is permitted only with the local profile AND a loopback datasource`() {
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = true, loopbackDatasource = true)).isFalse()
    }

    @Test
    fun `clean is force-disabled if either the profile or the loopback condition fails`() {
        // local profile but a remote DB (the "enabled local profile in prod" case)
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = true, loopbackDatasource = false)).isTrue()
        // loopback DB but not the local profile
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = false, loopbackDatasource = true)).isTrue()
        // neither
        assertThat(config.resolveCleanDisabled(cleanDisabledProperty = false, localProfileActive = false, loopbackDatasource = false)).isTrue()
    }

    @Test
    fun `loopback detection accepts localhost, 127-x, and IPv6 loopback`() {
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://127.0.0.1:4001/epistola")).isTrue()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://127.5.6.7:5432/epistola")).isTrue()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://localhost:5432/epistola")).isTrue()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://[::1]:5432/epistola")).isTrue()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://user:pass@127.0.0.1:5432/epistola")).isTrue()
    }

    @Test
    fun `loopback detection rejects remote hosts, HA URLs, and unparseable input, failing closed`() {
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://db.prod.example.com:5432/epistola")).isFalse()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://10.0.0.5:5432/epistola")).isFalse()
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://192.168.1.10:5432/epistola")).isFalse()
        // a hostname that merely starts with 127-ish text must not match
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://127-prod.example.com:5432/epistola")).isFalse()
        // multi-host HA failover URL — fail closed
        assertThat(config.isLoopbackDatasource("jdbc:postgresql://127.0.0.1:5432,db2:5432/epistola")).isFalse()
        // empty / garbage
        assertThat(config.isLoopbackDatasource("")).isFalse()
        assertThat(config.isLoopbackDatasource("not-a-jdbc-url")).isFalse()
    }
}
