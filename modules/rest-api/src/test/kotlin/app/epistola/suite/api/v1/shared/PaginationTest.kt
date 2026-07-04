package app.epistola.suite.api.v1.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PaginationTest {
    @Test
    fun `limitOf clamps size into the contract range`() {
        assertThat(Pagination.limitOf(20)).isEqualTo(20)
        assertThat(Pagination.limitOf(0)).isEqualTo(1)
        assertThat(Pagination.limitOf(-5)).isEqualTo(1)
        assertThat(Pagination.limitOf(9999)).isEqualTo(Pagination.MAX_PAGE_SIZE)
    }

    @Test
    fun `offsetOf multiplies the clamped page and size`() {
        assertThat(Pagination.offsetOf(0, 20)).isEqualTo(0)
        assertThat(Pagination.offsetOf(2, 20)).isEqualTo(40)
        assertThat(Pagination.offsetOf(-1, 20)).isEqualTo(0)
    }

    @Test
    fun `pageMeta computes totalPages by ceiling division`() {
        assertThat(Pagination.pageMeta(0, 20, 0).totalPages).isEqualTo(0)
        assertThat(Pagination.pageMeta(0, 20, 20).totalPages).isEqualTo(1)
        assertThat(Pagination.pageMeta(0, 20, 21).totalPages).isEqualTo(2)
        assertThat(Pagination.pageMeta(0, 20, 41).totalPages).isEqualTo(3)
        val meta = Pagination.pageMeta(1, 10, 25)
        assertThat(meta.number).isEqualTo(1)
        assertThat(meta.propertySize).isEqualTo(10)
        assertThat(meta.totalElements).isEqualTo(25)
        assertThat(meta.totalPages).isEqualTo(3)
    }

    @Test
    fun `paginate slices the requested page and reports the full total`() {
        val all = (1..5).toList()

        val first = Pagination.paginate(all, page = 0, size = 2)
        assertThat(first.items).containsExactly(1, 2)
        assertThat(first.page.totalElements).isEqualTo(5)
        assertThat(first.page.totalPages).isEqualTo(3)

        val last = Pagination.paginate(all, page = 2, size = 2)
        assertThat(last.items).containsExactly(5)

        // A page past the end yields no items but still the true total.
        val beyond = Pagination.paginate(all, page = 9, size = 2)
        assertThat(beyond.items).isEmpty()
        assertThat(beyond.page.totalElements).isEqualTo(5)
    }

    @Test
    fun `paginate on an empty list yields no items and zero pages`() {
        val slice = Pagination.paginate(emptyList<Int>(), page = 0, size = 20)
        assertThat(slice.items).isEmpty()
        assertThat(slice.page.totalElements).isEqualTo(0)
        assertThat(slice.page.totalPages).isEqualTo(0)
    }
}
