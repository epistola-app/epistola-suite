package app.epistola.generation

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
 * @param path Dot-notation path under the `sys` namespace (e.g., "pages.current")
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
    /** Whether this parameter requires two-pass rendering (value only known after a full render). */
    val twoPass: Boolean = false,
) {
    /** Full dotted path including the `sys.` prefix (e.g., "sys.pages.current"). */
    val fullPath: String get() = "sys.$path"
}

/** Default timezone used for date-related rendering (e.g., sys.render.time, $formatDate). */
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
                path = "pages.current",
                description = "Current page number. Available in page headers/footers only.",
                type = "integer",
                scope = SystemParamScope.PAGE_SCOPED,
                mockValue = 1,
            ),
        )
        register(
            SystemParameterDescriptor(
                path = "pages.total",
                description = "Total number of pages in the document.",
                type = "integer",
                scope = SystemParamScope.GLOBAL,
                mockValue = 1,
                twoPass = true,
            ),
        )
        register(
            SystemParameterDescriptor(
                path = "render.time",
                description = "Render timestamp as ISO-8601 offset datetime. Use \$formatDate() to format.",
                type = "datetime",
                scope = SystemParamScope.GLOBAL,
                mockValue = "2026-04-03T08:30:00Z",
            ),
        )
    }

    fun register(descriptor: SystemParameterDescriptor) {
        descriptors.add(descriptor)
    }

    fun all(): List<SystemParameterDescriptor> = descriptors.toList()

    /** Full paths of parameters that require two-pass rendering (for TwoPassAnalyzer). */
    fun twoPassPatterns(): List<String> = descriptors.filter { it.twoPass }.map { it.fullPath }

    /** Full paths of page-scoped parameters (for TwoPassAnalyzer). */
    fun pageScopedPatterns(): List<String> = descriptors.filter { it.scope == SystemParamScope.PAGE_SCOPED }.map { it.fullPath }

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

    /** Build page-scoped system parameters (page number + total for headers/footers). */
    fun buildPageParams(pageNumber: Int, totalPages: Int): Map<String, Any?> = buildNestedMap(
        mapOf(
            "pages.current" to pageNumber,
            "pages.total" to totalPages,
        ),
    )

    /** Build global system parameters that are available in all contexts (body, headers, footers). */
    fun buildGlobalParams(): Map<String, Any?> = buildNestedMap(
        mapOf("render.time" to OffsetDateTime.now(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
    )
}
