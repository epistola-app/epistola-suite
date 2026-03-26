package app.epistola.generation.pdf

/**
 * Systematic spacing scale based on multiples of a configurable base unit.
 *
 * Uses `Nsp` suffix notation (e.g., `2sp`, `0.5sp`) where N is a multiplier
 * applied to the base unit. With the default base unit of 4pt:
 * - `2sp` resolves to 8pt
 * - `3sp` resolves to 12pt
 * - `0.5sp` resolves to 2pt
 *
 * The scale is intentionally non-linear to provide fine control at small sizes
 * and larger jumps at bigger sizes, matching the perceptual needs of document layout.
 */
object SpacingScale {

    /** Default base unit in points. 4pt divides cleanly into 72pt/inch (18 steps). */
    const val DEFAULT_BASE_UNIT = 4f

    /**
     * Named scale steps: multiplier name → multiplier value.
     *
     * These are the "blessed" steps that appear in the editor UI.
     * The scale is non-linear: fine-grained at small values, coarser at large values.
     */
    val STEPS: LinkedHashMap<String, Float> = linkedMapOf(
        "0" to 0f,
        "0.5" to 0.5f,
        "1" to 1f,
        "1.5" to 1.5f,
        "2" to 2f,
        "3" to 3f,
        "4" to 4f,
        "5" to 5f,
        "6" to 6f,
        "8" to 8f,
        "10" to 10f,
        "12" to 12f,
        "16" to 16f,
    )

    /**
     * Resolves a scale step to an absolute pt value.
     *
     * @param step The scale step name (e.g., "2", "0.5") or an arbitrary numeric multiplier
     * @param baseUnit The base unit in points (default: [DEFAULT_BASE_UNIT])
     * @return The resolved value in points
     */
    fun resolve(step: String, baseUnit: Float = DEFAULT_BASE_UNIT): Float {
        val multiplier = STEPS[step] ?: step.toFloatOrNull() ?: return 0f
        return multiplier * baseUnit
    }

    /**
     * Parses an `Nsp` value string to an absolute pt value.
     *
     * @param value The string value, e.g., `"3sp"` or `"0.5sp"`
     * @param baseUnit The base unit in points (default: [DEFAULT_BASE_UNIT])
     * @return The resolved pt value, or null if the string is not an sp value
     */
    fun parseSp(value: String, baseUnit: Float = DEFAULT_BASE_UNIT): Float? {
        val match = SP_PATTERN.matchEntire(value) ?: return null
        val step = match.groupValues[1]
        return resolve(step, baseUnit)
    }

    private val SP_PATTERN = Regex("""([\d.]+)sp""")
}
