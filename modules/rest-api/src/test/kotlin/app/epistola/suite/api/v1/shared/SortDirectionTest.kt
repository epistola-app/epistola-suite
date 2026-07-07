package app.epistola.suite.api.v1.shared

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SortDirectionTest {
    @Test
    fun `known values map to their direction, case-insensitively`() {
        assertThat(SortDirection.fromParam("asc")).isEqualTo(SortDirection.ASC)
        assertThat(SortDirection.fromParam("desc")).isEqualTo(SortDirection.DESC)
        assertThat(SortDirection.fromParam("ASC")).isEqualTo(SortDirection.ASC)
        assertThat(SortDirection.fromParam("Desc")).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `unknown, null, and blank values fall back to the DESC default`() {
        assertThat(SortDirection.fromParam("up")).isEqualTo(SortDirection.DESC)
        assertThat(SortDirection.fromParam("asc ")).isEqualTo(SortDirection.DESC)
        assertThat(SortDirection.fromParam(null)).isEqualTo(SortDirection.DESC)
        assertThat(SortDirection.fromParam("")).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `descending flag matches the direction`() {
        assertThat(SortDirection.ASC.descending).isFalse()
        assertThat(SortDirection.DESC.descending).isTrue()
    }
}
