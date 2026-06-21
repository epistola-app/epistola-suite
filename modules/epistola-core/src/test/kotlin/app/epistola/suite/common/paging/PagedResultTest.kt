package app.epistola.suite.common.paging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage of the [PagedResult] derived getters (no DB). These are the
 * boundary cases the "Showing X–Y of N" footer and the page count depend on.
 */
class PagedResultTest {

    private fun paged(total: Long, page: Int, size: Int) = PagedResult(items = emptyList<String>(), page = page, size = size, total = total)

    @Test
    fun `empty result is one page with a zero range`() {
        val r = paged(total = 0, page = 1, size = 50)
        assertThat(r.totalPages).isEqualTo(1)
        assertThat(r.from).isEqualTo(0)
        assertThat(r.to).isEqualTo(0)
    }

    @Test
    fun `first page range starts at one`() {
        val r = paged(total = 10, page = 1, size = 4)
        assertThat(r.totalPages).isEqualTo(3)
        assertThat(r.from).isEqualTo(1)
        assertThat(r.to).isEqualTo(4)
    }

    @Test
    fun `partial last page reports a short upper bound`() {
        val r = paged(total = 3, page = 2, size = 2)
        assertThat(r.totalPages).isEqualTo(2)
        assertThat(r.from).isEqualTo(3)
        assertThat(r.to).isEqualTo(3)
    }

    @Test
    fun `exact multiple does not add a trailing empty page`() {
        val r = paged(total = 4, page = 2, size = 2)
        assertThat(r.totalPages).isEqualTo(2)
        assertThat(r.from).isEqualTo(3)
        assertThat(r.to).isEqualTo(4)
    }
}
