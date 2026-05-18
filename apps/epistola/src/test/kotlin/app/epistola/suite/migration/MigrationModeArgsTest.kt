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
        assertThat(MigrationMode.from("embedded")).isEqualTo(MigrationMode.EMBEDDED)
        assertThat(MigrationMode.from("EMBEDDED")).isEqualTo(MigrationMode.EMBEDDED)
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
    fun `migrate step is requested via the --migrate program arg`() {
        assertThat(MigrationLauncher.requested(arrayOf("--migrate"), migrationModeEnv = null)).isTrue()
        assertThat(MigrationLauncher.requested(arrayOf("foo", "--migrate", "bar"), migrationModeEnv = null)).isTrue()
    }

    @Test
    fun `migrate step is requested via EPISTOLA_MIGRATION_MODE=migrate (case-insensitive)`() {
        assertThat(MigrationLauncher.requested(emptyArray(), migrationModeEnv = "migrate")).isTrue()
        assertThat(MigrationLauncher.requested(emptyArray(), migrationModeEnv = "Migrate")).isTrue()
    }

    @Test
    fun `migrate step is not requested for embedded or validate`() {
        assertThat(MigrationLauncher.requested(emptyArray(), migrationModeEnv = null)).isFalse()
        assertThat(MigrationLauncher.requested(emptyArray(), migrationModeEnv = "embedded")).isFalse()
        assertThat(MigrationLauncher.requested(emptyArray(), migrationModeEnv = "validate")).isFalse()
        assertThat(MigrationLauncher.requested(arrayOf("--server"), migrationModeEnv = "validate")).isFalse()
    }
}
