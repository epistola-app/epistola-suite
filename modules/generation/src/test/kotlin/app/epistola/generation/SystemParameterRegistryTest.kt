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
    }

    @Test
    fun `register adds new descriptor`() {
        // Use a fresh list approach — since SystemParameterRegistry is a singleton,
        // we just verify the count increases
        val before = SystemParameterRegistry.all().size

        SystemParameterRegistry.register(
            SystemParameterDescriptor(
                path = "test.param",
                description = "Test parameter",
                type = "string",
                scope = SystemParamScope.GLOBAL,
            ),
        )

        val after = SystemParameterRegistry.all()
        assertEquals(before + 1, after.size)
        assertTrue(after.any { it.path == "test.param" })
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
}
