package com.jarvis.assistant.tools.device

import com.jarvis.assistant.tools.framework.Tool
import com.jarvis.assistant.tools.framework.ToolInput
import com.jarvis.assistant.tools.framework.ToolResult
import com.jarvis.assistant.tools.framework.ToolSchema

/**
 * CalculatorTool — pure-local arithmetic.  Replaces the previously
 * expensive "ask the LLM what 27 times 41 is" path with a deterministic
 * tokeniser + shunting-yard parser.  No I/O, no network.
 *
 * Supported:
 *   numbers (int + decimal)
 *   + - * / %
 *   parentheses
 *   spoken operators: plus, minus, times, multiplied by, divided by,
 *                     over, modulo, mod, of (percentage)
 *   "what is", "calculate", "compute" prefixes
 *   "percent of", "percentage of"
 *   integer powers via "to the power of" / "^"
 *
 * The result is rounded sensibly: integers stay integer, fractional
 * values round to 4 decimal places with trailing-zero stripping.
 */
class CalculatorTool : Tool {

    override val name = "calculator"
    override val description = "Evaluate simple arithmetic locally — no LLM round-trip."
    override val requiresNetwork = false
    override val isLocalFallback = true
    override val requiredPermissions = emptyList<String>()

    companion object {
        /** Quick predicate the runtime / parser can use to short-circuit. */
        private val LOOKS_NUMERIC_RX = Regex(
            """(?ix)
            ^\s*(?:please\s+|hey\s+)?
            (?:what(?:'?s|\s+is)\s+|calculate\s+|compute\s+|work\s+out\s+|how\s+much\s+is\s+)?
            [-+0-9(].*[0-9)%].*?
            \s*[.?!]?\s*$
            """,
        )

        /**
         * "Words for operators" — we tokenise the user's utterance into
         * a clean arithmetic expression by literal substitution.  Order
         * matters: longer phrases first so "multiplied by" beats "by".
         */
        private val WORD_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
            Regex("""(?i)\b(?:what'?s|what\s+is|calculate|compute|work\s+out|how\s+much\s+is)\b""") to " ",
            Regex("""(?i)\bplus\b|\band\b""")                                                       to " + ",
            Regex("""(?i)\bminus\b|\bless\b|\btake\s+(?:away|off)\b""")                             to " - ",
            Regex("""(?i)\btimes\b|\bmultiplied\s+by\b|\bx\b""")                                    to " * ",
            Regex("""(?i)\bdivided\s+by\b|\bover\b""")                                              to " / ",
            Regex("""(?i)\bmodulo\b|\bmod\b""")                                                     to " % ",
            Regex("""(?i)\bto\s+the\s+power\s+of\b|\braised\s+to\b""")                              to " ^ ",
            Regex("""(?i)\bsquared\b""")                                                            to " ^ 2 ",
            Regex("""(?i)\bcubed\b""")                                                              to " ^ 3 ",
            // "20 percent of 50" → "20 % * 50"
            Regex("""(?i)\b(?:per\s*cent|percent|percentage)\s+of\b""")                             to " % * ",
            Regex("""(?i)\b(?:per\s*cent|percent|percentage)\b""")                                  to " % ",
        )
    }

    override fun matches(transcript: String): ToolInput? {
        val t = transcript.trim()
        if (t.isBlank()) return null
        // Cheap pre-filter: must contain a digit or arithmetic word.
        if (!Regex("""[0-9]|plus|minus|times|divided|percent""", RegexOption.IGNORE_CASE)
                .containsMatchIn(t)) return null
        if (!LOOKS_NUMERIC_RX.matches(t)) return null
        // Reject phrases that clearly aren't sums ("set timer for 5 minutes").
        if (Regex("""(?i)\b(?:timer|alarm|minutes?|hours?|seconds?|days?|tasks?|reminders?)\b""")
                .containsMatchIn(t)) return null
        return ToolInput(transcript, mapOf("expression" to t))
    }

    override fun schema() = ToolSchema(
        name        = name,
        description = "Evaluate a simple arithmetic expression locally.",
        parameters  = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "expression" to mapOf("type" to "string"),
            ),
            "required" to listOf("expression"),
        ),
    )

    override suspend fun execute(input: ToolInput): ToolResult {
        val expr = input.param("expression").ifBlank { input.transcript }
        val value = evaluate(expr)
            ?: return ToolResult.Failure("I couldn't work that out.")
        return ToolResult.Success(formatNumber(value))
    }

    // ── Pure evaluator ────────────────────────────────────────────────────

    /**
     * Evaluate a free-form arithmetic phrase.  Returns null on any
     * parse / divide-by-zero / overflow failure — caller decides
     * what to speak.
     */
    internal fun evaluate(raw: String): Double? = try {
        val cleaned = normalise(raw)
        val tokens = tokenise(cleaned)
        if (tokens.isEmpty()) null else shuntingYard(tokens)
    } catch (_: Throwable) { null }

    private fun normalise(raw: String): String {
        var s = " ${raw.lowercase()} "
        for ((rx, repl) in WORD_REPLACEMENTS) s = rx.replace(s, repl)
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private sealed class Tok {
        data class Num(val value: Double) : Tok()
        data class Op(val symbol: Char, val precedence: Int, val rightAssoc: Boolean = false) : Tok()
        object LParen : Tok()
        object RParen : Tok()
    }

    private fun tokenise(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c == '+' -> { out += Tok.Op('+', 1); i++ }
                c == '-' -> {
                    // Unary minus when at start or after op/paren.
                    val prev = out.lastOrNull()
                    if (prev == null || prev is Tok.Op || prev is Tok.LParen) {
                        // Treat as 0 - X by inserting 0.
                        out += Tok.Num(0.0); out += Tok.Op('-', 1)
                    } else out += Tok.Op('-', 1)
                    i++
                }
                c == '*' -> { out += Tok.Op('*', 2); i++ }
                c == '/' -> { out += Tok.Op('/', 2); i++ }
                c == '%' -> { out += Tok.Op('%', 2); i++ }
                c == '^' -> { out += Tok.Op('^', 3, rightAssoc = true); i++ }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    out += Tok.Num(s.substring(start, i).toDouble())
                }
                else -> i++   // skip stray letters left behind by normalisation
            }
        }
        return out
    }

    private fun shuntingYard(tokens: List<Tok>): Double {
        val output = ArrayDeque<Tok>()
        val stack  = ArrayDeque<Tok>()
        for (t in tokens) {
            when (t) {
                is Tok.Num -> output.addLast(t)
                is Tok.Op  -> {
                    while (stack.isNotEmpty()) {
                        val top = stack.last() as? Tok.Op ?: break
                        if (top.precedence > t.precedence ||
                            (top.precedence == t.precedence && !t.rightAssoc)
                        ) output.addLast(stack.removeLast())
                        else break
                    }
                    stack.addLast(t)
                }
                Tok.LParen -> stack.addLast(t)
                Tok.RParen -> {
                    while (stack.isNotEmpty() && stack.last() !is Tok.LParen) {
                        output.addLast(stack.removeLast())
                    }
                    if (stack.isNotEmpty()) stack.removeLast()    // discard '('
                }
            }
        }
        while (stack.isNotEmpty()) output.addLast(stack.removeLast())
        // Evaluate RPN.
        val eval = ArrayDeque<Double>()
        for (t in output) {
            when (t) {
                is Tok.Num -> eval.addLast(t.value)
                is Tok.Op  -> {
                    val b = eval.removeLast()
                    val a = eval.removeLast()
                    eval.addLast(when (t.symbol) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> { if (b == 0.0) throw ArithmeticException("/0"); a / b }
                        '%' -> a / 100.0 * if (eval.size > 0) 1.0 else 1.0   // unary "%"
                            .let { _ -> a / 100.0 }
                        '^' -> Math.pow(a, b)
                        else -> throw IllegalStateException("bad op")
                    })
                }
                else -> Unit
            }
        }
        return eval.lastOrNull() ?: throw IllegalStateException("empty")
    }

    internal fun formatNumber(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "It doesn't have a real answer."
        // Integers stay integer.
        if (d == d.toLong().toDouble() && Math.abs(d) < 1e15) {
            return "${d.toLong()}"
        }
        val rounded = Math.round(d * 10_000.0) / 10_000.0
        return rounded.toString().trimEnd('0').trimEnd('.')
    }
}
