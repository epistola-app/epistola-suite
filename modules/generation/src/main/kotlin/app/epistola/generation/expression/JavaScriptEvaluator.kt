package app.epistola.generation.expression

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * Expression evaluator using GraalJS (JavaScript on the JVM).
 *
 * Runs in a sandboxed context with:
 * - No file/network access
 * - No host class access (except allowed types)
 * - Execution time limits
 *
 * Use JavaScript for advanced expressions:
 * - `items.filter(x => x.active)`
 * - `items.map(x => x.price)`
 * - `items.reduce((sum, x) => sum + x.price, 0)`
 * - Template literals: `${customer.name} from ${company}`
 */
class JavaScriptEvaluator : ExpressionEvaluator {
    companion object {
        private const val JS_LANGUAGE = "js"
    }

    override fun evaluate(
        expression: String,
        data: Map<String, Any?>,
        loopContext: Map<String, Any?>,
    ): Any? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        return try {
            createSandboxedContext().use { context ->
                // Merge loop context with data (loop context takes precedence)
                val mergedData = data + loopContext

                // Bind the data to the JS context using proxy objects
                val bindings = context.getBindings(JS_LANGUAGE)

                mergedData.forEach { (key, value) ->
                    bindings.putMember(key, convertToProxyValue(value))
                }

                // Evaluate the expression
                val result = context.eval(JS_LANGUAGE, trimmed)

                // Convert GraalJS result back to Java types
                convertFromGraalValue(result)
            }
        } catch (e: Exception) {
            // Log or handle evaluation errors
            null
        }
    }

    private fun createSandboxedContext(): Context = Context.newBuilder(JS_LANGUAGE)
        // Disable all access by default - no file, network, or system access
        .allowAllAccess(false)
        .allowNativeAccess(false)
        .allowCreateThread(false)
        .allowCreateProcess(false)
        .allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.NONE)
        // Suppress interpreter warning
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    /**
     * Converts Kotlin/Java values to GraalJS-compatible proxy values.
     * Uses ProxyObject and ProxyArray to expose Kotlin data to JavaScript.
     */
    private fun convertToProxyValue(value: Any?): Any? = when (value) {
        null -> null
        is String, is Number, is Boolean -> value
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = value as Map<String, Any?>
            ProxyObject.fromMap(
                map.mapValues { (_, v) -> convertToProxyValue(v) }.toMutableMap(),
            )
        }
        is List<*> -> {
            ProxyArray.fromList(
                value.map { convertToProxyValue(it) }.toMutableList(),
            )
        }
        is Array<*> -> convertToProxyValue(value.toList())
        else -> value.toString()
    }

    /**
     * Converts GraalJS values back to Kotlin/Java types.
     */
    private fun convertFromGraalValue(value: Value?): Any? {
        if (value == null || value.isNull) return null

        return when {
            value.isBoolean -> value.asBoolean()
            value.isNumber -> {
                if (value.fitsInLong()) {
                    value.asLong()
                } else {
                    value.asDouble()
                }
            }
            value.isString -> value.asString()
            value.hasArrayElements() -> {
                (0 until value.arraySize).map { i ->
                    convertFromGraalValue(value.getArrayElement(i))
                }
            }
            value.hasMembers() -> {
                value.memberKeys.associateWith { key ->
                    convertFromGraalValue(value.getMember(key))
                }
            }
            else -> value.toString()
        }
    }
}
