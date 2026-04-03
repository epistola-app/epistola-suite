package app.epistola.generation

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemParameterRegistryTest {
    @Test
    fun `registry contains page number descriptor`() {
        val descriptors = SystemParameterRegistry.all()
        assertTrue(descriptors.size >= 2, "Registry should have at least two descriptors")

        val pageNumber = descriptors.find { it.path == "page.number" }
        assertTrue(pageNumber != null, "page.number descriptor should be registered")
        assertEquals("integer", pageNumber.type)
        assertEquals(SystemParamScope.PAGE_SCOPED, pageNumber.scope)
        assertEquals("sys.page.number", pageNumber.fullPath)
        assertEquals(1, pageNumber.mockValue)
    }

    @Test
    fun `registry contains today descriptor`() {
        val descriptors = SystemParameterRegistry.all()

        val today = descriptors.find { it.path == "today" }
        assertTrue(today != null, "today descriptor should be registered")
        assertEquals("date", today.type)
        assertEquals(SystemParamScope.GLOBAL, today.scope)
        assertEquals("sys.today", today.fullPath)
        assertEquals("2026-04-03", today.mockValue)
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
    fun `buildPageParams creates page number structure`() {
        val result = SystemParameterRegistry.buildPageParams(5)

        @Suppress("UNCHECKED_CAST")
        val page = result["page"] as Map<String, Any?>
        assertEquals(5, page["number"])
    }

    @Test
    fun `buildGlobalParams returns today's date`() {
        val result = SystemParameterRegistry.buildGlobalParams()
        assertEquals(LocalDate.now().toString(), result["today"])
    }
}
