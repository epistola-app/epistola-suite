package app.epistola.suite.common.paging

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [PageRequest] fails fast on out-of-range bounds at construction — no query runs,
 * so junk input can never reach SQL or silently produce a wrong/empty page.
 */
class PageRequestTest {

    @Test
    fun `rejects a page below one`() {
        assertThatThrownBy { PageRequest(page = 0, size = 50) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a non-positive size`() {
        assertThatThrownBy { PageRequest(page = 1, size = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a negative size`() {
        assertThatThrownBy { PageRequest(page = 1, size = -5) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accepts a positive page and size`() {
        val request = PageRequest(page = 2, size = 25)
        assertThat(request.page).isEqualTo(2)
        assertThat(request.size).isEqualTo(25)
    }
}
