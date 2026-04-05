package app.epistola.generation

import java.time.LocalDate
import java.time.ZoneId

/**
 * Scope at which a system parameter is available.
 */
enum class SystemParamScope {
    /** Available in all contexts (body, headers, footers). */
    GLOBAL,

    /** Available only in page-scoped contexts (headers, footers). */
    PAGE_SCOPED,
}

/**
 * Describes a single system parameter that the rendering engine provides.
 *
 * @param path Dot-notation path under the `sys` namespace (e.g., "page.number")
 * @param description Human-readable description for editor UI
 * @param type The value type (e.g., "integer", "string")
 * @param scope Where this parameter is available
 */
data class SystemParameterDescriptor(
    val path: String,
    val description: String,
    val type: String,
    val scope: SystemParamScope,
    /** Mock value used by the editor for expression preview. */
    val mockValue: Any? = null,
) {
    /** Full dotted path including the `sys.` prefix (e.g., "sys.page.number"). */
    val fullPath: String get() = "sys.$path"
}

/** Default timezone used for date-related rendering (e.g., sys.today, $formatDate). */
val DEFAULT_RENDER_TIMEZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

/**
 * Registry of system parameters available to templates at render time.
 *
 * System parameters are runtime values injected by the rendering engine
 * (e.g., page numbers) that are independent of the template's data model.
 */
object SystemParameterRegistry {
    private val descriptors = mutableListOf<SystemParameterDescriptor>()

    init {
        register(
            SystemParameterDescriptor(
                path = "page.number",
                description = "Current page number. Available in page headers/footers only.",
                type = "integer",
                scope = SystemParamScope.PAGE_SCOPED,
                mockValue = 1,
            ),
        )
        register(
            SystemParameterDescriptor(
                path = "today",
                description = "Today's date in ISO format (YYYY-MM-DD). Use \$formatDate() for locale-specific formatting.",
                type = "date",
                scope = SystemParamScope.GLOBAL,
                mockValue = "2026-04-03",
            ),
        )
    }

    fun register(descriptor: SystemParameterDescriptor) {
        descriptors.add(descriptor)
    }

    fun all(): List<SystemParameterDescriptor> = descriptors.toList()

    /** Build a nested map from dot-path keys and their values. */
    fun buildNestedMap(values: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((path, value) in values) {
            val parts = path.split(".")
            var current = result
            for (i in 0 until parts.size - 1) {
                @Suppress("UNCHECKED_CAST")
                current = current.getOrPut(parts[i]) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
            }
            current[parts.last()] = value
        }
        return result
    }

    /** Build page-scoped system parameters (for headers/footers). */
    fun buildPageParams(pageNumber: Int): Map<String, Any?> = buildNestedMap(mapOf("page.number" to pageNumber))

    /** Build global system parameters (e.g., today's date). */
    fun buildGlobalParams(): Map<String, Any?> = buildNestedMap(mapOf("today" to LocalDate.now(DEFAULT_RENDER_TIMEZONE).toString()))
}
