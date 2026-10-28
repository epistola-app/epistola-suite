// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.suite.common.PageRequest
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
        assertThat(meta.total).isEqualTo(25)
        assertThat(meta.totalPages).isEqualTo(3)
    }

    @Test
    fun `a page request is the clamped limit and offset`() {
        assertThat(Pagination.requestOf(page = 2, size = 10)).isEqualTo(PageRequest(limit = 10, offset = 20))
        assertThat(Pagination.requestOf(page = -1, size = 500)).isEqualTo(PageRequest(limit = Pagination.MAX_PAGE_SIZE, offset = 0))
        assertThat(Pagination.requestOf(page = 0, size = 0)).isEqualTo(PageRequest(limit = 1, offset = 0))
    }
}
