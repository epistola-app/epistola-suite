package app.epistola.suite.migration

import app.epistola.suite.config.FlywayConfig.MigrationMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MigrationModeArgsTest {

    @Test
    fun `migration mode parses case-insensitively`() {
        assertThat(MigrationMode.from("migrate")).isEqualTo(MigrationMode.MIGRATE)
        assertThat(MigrationMode.from("MIGRATE")).isEqualTo(MigrationMode.MIGRATE)
        assertThat(MigrationMode.from("validate")).isEqualTo(MigrationMode.VALIDATE)
        assertThat(MigrationMode.from("Validate")).isEqualTo(MigrationMode.VALIDATE)
    }

    @Test
    fun `invalid migration mode fails fast`() {
        assertThatThrownBy { MigrationMode.from("nope") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("epistola.migration.mode")
    }

    @Test
    fun `migrate run is requested via the --migrate program arg`() {
        assertThat(MigrationLauncher.requested(arrayOf("--migrate"))).isTrue()
        assertThat(MigrationLauncher.requested(arrayOf("foo", "--migrate", "bar"))).isTrue()
    }

    @Test
    fun `migrate run is not requested without the arg (and EPISTOLA_RUN_MODE unset in tests)`() {
        assertThat(MigrationLauncher.requested(emptyArray())).isFalse()
        assertThat(MigrationLauncher.requested(arrayOf("--server", "foo"))).isFalse()
    }
}
