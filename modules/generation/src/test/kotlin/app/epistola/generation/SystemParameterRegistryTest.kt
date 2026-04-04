package app.epistola.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemParameterRegistryTest {
    @Test
    fun `registry contains page number descriptor`() {
        val descriptors = SystemParameterRegistry.all()
        assertTrue(descriptors.isNotEmpty(), "Registry should have at least one descriptor")

        val pageNumber = descriptors.find { it.path == "page.number" }
        assertTrue(pageNumber != null, "page.number descriptor should be registered")
        assertEquals("integer", pageNumber.type)
        assertEquals(SystemParamScope.PAGE_SCOPED, pageNumber.scope)
        assertEquals("sys.page.number", pageNumber.fullPath)
        assertEquals(1, pageNumber.mockValue)
    }

    @Test
    fun `registry contains page total descriptor`() {
        val descriptors = SystemParameterRegistry.all()

        val pageTotal = descriptors.find { it.path == "page.total" }
        assertTrue(pageTotal != null, "page.total descriptor should be registered")
        assertEquals("integer", pageTotal.type)
        assertEquals(SystemParamScope.PAGE_SCOPED, pageTotal.scope)
        assertEquals("sys.page.total", pageTotal.fullPath)
        assertEquals(1, pageTotal.mockValue)
    }

    @Test
    fun `fullPath includes sys prefix`() {
        val descriptor = SystemParameterDescriptor(
            path = "page.total",
            description = "Total page count",
            type = "integer",
            scope = SystemParamScope.PAGE_SCOPED,
        )
        assertEquals("sys.page.total", descriptor.fullPath)
    }

    @Test
    fun `buildNestedMap creates nested structure from dot paths`() {
        val result = SystemParameterRegistry.buildNestedMap(
            mapOf("page.number" to 3, "page.total" to 10),
        )

        @Suppress("UNCHECKED_CAST")
        val page = result["page"] as Map<String, Any?>
        assertEquals(3, page["number"])
        assertEquals(10, page["total"])
    }

    @Test
    fun `buildNestedMap handles single-level key`() {
        val result = SystemParameterRegistry.buildNestedMap(mapOf("version" to "1.0"))
        assertEquals("1.0", result["version"])
    }

    @Test
    fun `buildNestedMap handles deeply nested paths`() {
        val result = SystemParameterRegistry.buildNestedMap(mapOf("a.b.c.d" to 42))

        @Suppress("UNCHECKED_CAST")
        val a = result["a"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val b = a["b"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val c = b["c"] as Map<String, Any?>
        assertEquals(42, c["d"])
    }

    @Test
    fun `buildNestedMap returns empty map for empty input`() {
        val result = SystemParameterRegistry.buildNestedMap(emptyMap())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildPageParams creates page number and total structure`() {
        val result = SystemParameterRegistry.buildPageParams(5, 10)

        @Suppress("UNCHECKED_CAST")
        val page = result["page"] as Map<String, Any?>
        assertEquals(5, page["number"])
        assertEquals(10, page["total"])
    }

    @Test
    fun `buildGlobalParams returns empty map`() {
        val result = SystemParameterRegistry.buildGlobalParams()
        assertTrue(result.isEmpty())
    }
}
