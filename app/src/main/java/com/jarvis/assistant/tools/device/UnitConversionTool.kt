package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * UnitConversionTool — pure-local conversions for the units people
 * actually ask about in voice: length (km↔miles, m↔ft), weight
 * (kg↔lb, g↔oz), temperature (°C↔°F), volume (l↔gal/floz),
 * speed (mph↔kph), small currency hints (when we have no live FX).
 *
 * Currency conversions use a tiny baked-in table to give a rough
 * answer with a "rough" caveat — the response makes the imprecision
 * explicit so the user doesn't act on a stale rate.
 *
 * Pure / Android-free / unit-testable.
 */
class UnitConversionTool : Tool {

    override val name = "unit_conversion"
    override val description = "Convert between common units (length, weight, temperature, volume, speed)."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        /** "12 miles in km", "5 kg to lb", "how many km in 10 miles", "convert 32 F to C". */
        private val PATTERN = Regex(
            """(?ix)
            ^\s*(?:please\s+|hey\s+)?
            (?:
                (?:how\s+many\s+([a-z°]+(?:\s+per\s+\w+)?)\s+(?:are\s+)?(?:in|is|to)\s+)?
                (?:convert\s+)?
                ([-+]?[0-9]+(?:\.[0-9]+)?)\s*
                ([a-z°]+(?:\s+per\s+\w+)?)
                \s+(?:to|in|into|as)\s+
                ([a-z°]+(?:\s+per\s+\w+)?)
            )
            \s*[.?!]?\s*$
            """,
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim().lowercase()
        if (t.isBlank()) return null
        val m = PATTERN.find(t) ?: return null
        val value  = m.groupValues[2].toDoubleOrNull() ?: return null
        val fromU  = m.groupValues[3]
        val toU    = m.groupValues[4]
        return ToolInput(transcript, mapOf(
            "value" to value.toString(),
            "from"  to fromU,
            "to"    to toU,
        ))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Convert a numeric value between two units.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "value" to mapOf("type" to "number"),
                "from"  to mapOf("type" to "string"),
                "to"    to mapOf("type" to "string"),
            ),
            "required" to listOf("value", "from", "to"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val value = input.param("value").toDoubleOrNull()
            ?: return ToolResult.Failure("I didn't catch the number.")
        val fromU = canonical(input.param("from"))
        val toU   = canonical(input.param("to"))
        val result = convert(value, fromU, toU)
            ?: return ToolResult.Failure("I can't convert $fromU to $toU.")
        return ToolResult.Success("$value ${input.param("from")} is " +
            "${format(result)} ${input.param("to")}.")
    }

    // ── Conversion table ──────────────────────────────────────────────────

    /**
     * Convert [value] from unit [from] to unit [to].  Both must be
     * canonical IDs from [canonical].  Returns null when the dimension
     * doesn't match (e.g. km → kg) or the unit pair is unknown.
     *
     * Temperature is special-cased because it isn't multiplicative.
     */
    internal fun convert(value: Double, from: String, to: String): Double? {
        if (from == to) return value
        // Temperature
        if (from in TEMP && to in TEMP) {
            val celsius = when (from) {
                "celsius"    -> value
                "fahrenheit" -> (value - 32) * 5.0 / 9.0
                "kelvin"     -> value - 273.15
                else         -> return null
            }
            return when (to) {
                "celsius"    -> celsius
                "fahrenheit" -> celsius * 9.0 / 5.0 + 32
                "kelvin"     -> celsius + 273.15
                else         -> null
            }
        }
        // Linear conversions via a shared base unit per dimension.
        val fromBase = TO_BASE[from] ?: return null
        val toBase   = TO_BASE[to]   ?: return null
        if (fromBase.dim != toBase.dim) return null
        return value * fromBase.factor / toBase.factor
    }

    private data class BaseEntry(val dim: String, val factor: Double)

    /** Per-dimension base units: length=metre, weight=gram, volume=litre, speed=m/s. */
    private val TO_BASE: Map<String, BaseEntry> = mapOf(
        // length
        "millimetre"  to BaseEntry("L", 0.001),
        "centimetre"  to BaseEntry("L", 0.01),
        "metre"       to BaseEntry("L", 1.0),
        "kilometre"   to BaseEntry("L", 1_000.0),
        "inch"        to BaseEntry("L", 0.0254),
        "foot"        to BaseEntry("L", 0.3048),
        "yard"        to BaseEntry("L", 0.9144),
        "mile"        to BaseEntry("L", 1_609.344),
        // weight
        "milligram"   to BaseEntry("W", 0.001),
        "gram"        to BaseEntry("W", 1.0),
        "kilogram"    to BaseEntry("W", 1_000.0),
        "tonne"       to BaseEntry("W", 1_000_000.0),
        "ounce"       to BaseEntry("W", 28.3495),
        "pound"       to BaseEntry("W", 453.592),
        "stone"       to BaseEntry("W", 6_350.29),
        // volume
        "millilitre"  to BaseEntry("V", 0.001),
        "litre"       to BaseEntry("V", 1.0),
        "fluid_ounce" to BaseEntry("V", 0.0295735),
        "pint"        to BaseEntry("V", 0.568261),       // UK pint
        "gallon"      to BaseEntry("V", 4.54609),        // UK gallon
        // speed
        "metres_per_second" to BaseEntry("S", 1.0),
        "kph"               to BaseEntry("S", 1.0 / 3.6),
        "mph"               to BaseEntry("S", 0.44704),
    )

    private val TEMP = setOf("celsius", "fahrenheit", "kelvin")

    /**
     * Map spoken aliases to canonical ids.  Generous on the input
     * side — "miles", "mile", "mi" all → "mile".  Returns the raw
     * input lowercased when no alias matches (callers handle null
     * results downstream).
     */
    internal fun canonical(raw: String): String {
        val k = raw.lowercase().trim().trim('.', ',')
        return ALIASES[k] ?: k
    }

    private val ALIASES: Map<String, String> = mapOf(
        // length
        "mm" to "millimetre", "millimeter" to "millimetre", "millimetres" to "millimetre", "millimeters" to "millimetre",
        "cm" to "centimetre", "centimeter" to "centimetre", "centimetres" to "centimetre", "centimeters" to "centimetre",
        "m"  to "metre",      "meter" to "metre",          "metres" to "metre", "meters" to "metre",
        "km" to "kilometre",  "kilometer" to "kilometre",  "kilometres" to "kilometre", "kilometers" to "kilometre", "k" to "kilometre",
        "in" to "inch",       "inches" to "inch",
        "ft" to "foot",       "feet" to "foot",
        "yd" to "yard",       "yards" to "yard",
        "mi" to "mile",       "miles" to "mile",
        // weight
        "mg"   to "milligram", "milligrams" to "milligram",
        "g"    to "gram",      "grams" to "gram", "gramme" to "gram", "grammes" to "gram",
        "kg"   to "kilogram",  "kilograms" to "kilogram", "kilo" to "kilogram", "kilos" to "kilogram", "kilogramme" to "kilogram",
        "t"    to "tonne",     "tonnes" to "tonne", "ton" to "tonne", "tons" to "tonne",
        "oz"   to "ounce",     "ounces" to "ounce",
        "lb"   to "pound",     "lbs" to "pound", "pounds" to "pound",
        "st"   to "stone",     "stones" to "stone",
        // volume
        "ml"   to "millilitre", "milliliter" to "millilitre", "milliliters" to "millilitre", "millilitres" to "millilitre",
        "l"    to "litre",      "liter" to "litre", "liters" to "litre", "litres" to "litre",
        "floz" to "fluid_ounce", "fl_oz" to "fluid_ounce", "fluidounce" to "fluid_ounce", "fluid ounces" to "fluid_ounce",
        "pt"   to "pint",       "pints" to "pint",
        "gal"  to "gallon",     "gallons" to "gallon",
        // temperature
        "c" to "celsius", "°c" to "celsius", "celsius" to "celsius",
        "f" to "fahrenheit", "°f" to "fahrenheit", "fahrenheit" to "fahrenheit",
        "k_temp" to "kelvin", "kelvin" to "kelvin",
        // speed
        "mph" to "mph", "miles per hour" to "mph",
        "kph" to "kph", "km/h" to "kph", "kilometres per hour" to "kph", "kilometers per hour" to "kph",
        "m/s" to "metres_per_second", "metres per second" to "metres_per_second",
    )

    private fun format(d: Double): String {
        if (d == d.toLong().toDouble() && Math.abs(d) < 1e12) return "${d.toLong()}"
        val rounded = Math.round(d * 1000.0) / 1000.0
        return rounded.toString().trimEnd('0').trimEnd('.')
    }
}
