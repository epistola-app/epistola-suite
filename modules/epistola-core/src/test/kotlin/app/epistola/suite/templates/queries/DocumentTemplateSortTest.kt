package app.epistola.suite.templates.queries

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DocumentTemplateSortTest {
    @Test
    fun `known values resolve, case-insensitively`() {
        assertThat(DocumentTemplateSort.fromParamOrNull("name")).isEqualTo(DocumentTemplateSort.NAME)
        assertThat(DocumentTemplateSort.fromParamOrNull("createdAt")).isEqualTo(DocumentTemplateSort.CREATED)
        assertThat(DocumentTemplateSort.fromParamOrNull("lastModified")).isEqualTo(DocumentTemplateSort.UPDATED)
        assertThat(DocumentTemplateSort.fromParamOrNull("NAME")).isEqualTo(DocumentTemplateSort.NAME)
        assertThat(DocumentTemplateSort.fromParamOrNull("LastModified")).isEqualTo(DocumentTemplateSort.UPDATED)
    }

    @Test
    fun `unrecognized values resolve to null rather than falling back`() {
        // The REST layer maps a non-null null result to a 400; a lenient fallback here would
        // instead silently ignore the caller's requested sort.
        assertThat(DocumentTemplateSort.fromParamOrNull("title")).isNull()
        assertThat(DocumentTemplateSort.fromParamOrNull("name ")).isNull()
        assertThat(DocumentTemplateSort.fromParamOrNull("")).isNull()
    }

    @Test
    fun `paramValues lists the supported keys in declaration order`() {
        assertThat(DocumentTemplateSort.paramValues).containsExactly("name", "createdAt", "lastModified")
    }
}
