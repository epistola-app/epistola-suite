package app.epistola.suite.common.paging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ilikeContains] is the single LIKE-pattern escaper every list search query uses, so the
 * user's `%` / `_` / `\` are matched literally instead of as wildcards. See ADR 0007.
 */
class LikePatternTest {

    @Test
    fun `wraps a plain term in wildcards`() {
        assertThat(ilikeContains("invoice")).isEqualTo("%invoice%")
    }

    @Test
    fun `escapes LIKE wildcards so they match literally`() {
        // `_` and `%` are LIKE metacharacters; escaped, they match the literal characters.
        assertThat(ilikeContains("svc_prod")).isEqualTo("%svc\\_prod%")
        assertThat(ilikeContains("50%")).isEqualTo("%50\\%%")
    }

    @Test
    fun `escapes the backslash escape char itself, before the metacharacters`() {
        // A literal backslash becomes a doubled backslash (so it isn't read as an escape).
        assertThat(ilikeContains("a\\b")).isEqualTo("%a\\\\b%")
    }
}
