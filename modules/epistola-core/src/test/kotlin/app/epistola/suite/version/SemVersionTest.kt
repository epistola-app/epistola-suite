package app.epistola.suite.version

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SemVersionTest {
    @Test
    fun `final release outranks release candidates`() {
        assertThat(SemVersion.parse("1.0.0")!!.compareTo(SemVersion.parse("1.0.0-RC2")!!)).isGreaterThan(0)
    }

    @Test
    fun `release candidates compare by identifier`() {
        assertThat(SemVersion.parse("1.0.0-RC2")!!.compareTo(SemVersion.parse("1.0.0-RC1")!!)).isGreaterThan(0)
        assertThat(SemVersion.parse("1.0.0-beta.11")!!.compareTo(SemVersion.parse("1.0.0-beta.2")!!)).isGreaterThan(0)
    }

    @Test
    fun `snapshot suffix is ignored for comparison`() {
        assertThat(SemVersion.parse("1.0.0-RC3-SNAPSHOT")).isEqualTo(SemVersion.parse("1.0.0-RC3"))
        assertThat(SemVersion.parse("1.0.0-SNAPSHOT")).isEqualTo(SemVersion.parse("1.0.0"))
    }

    @Test
    fun `dev is not a semver release`() {
        assertThat(SemVersion.parse("dev")).isNull()
    }
}
