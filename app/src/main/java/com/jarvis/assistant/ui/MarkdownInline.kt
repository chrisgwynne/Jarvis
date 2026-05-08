package com.jarvis.assistant.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight inline-Markdown renderer for assistant messages.
 *
 * Supports the three formats the LLM actually emits in voice-assistant
 * replies:
 *
 *   **bold**   /  __bold__
 *   *italic*   /  _italic_
 *   `inline code`
 *
 * Block-level features (headings, lists, fenced code, links) are out of
 * scope here — the conversation is short, spoken, and Jetpack Compose's
 * Text composable doesn't render them anyway.  Adding a full Markdown
 * dependency for three inline formats wasn't worth the APK weight.
 *
 * Implementation: single-pass character scan with a stack of open spans.
 * Any unterminated marker (lone `**` etc.) is rendered as plain text.
 *
 * @param codeColor optional colour for `inline code` spans.  When null,
 *   the span keeps the surrounding text colour and only switches font.
 */
@Composable
fun renderMarkdownInline(
    text: String,
    codeColor: Color? = null,
): AnnotatedString = remember(text, codeColor) {
    parseInlineMarkdown(text, codeColor)
}

internal fun parseInlineMarkdown(text: String, codeColor: Color?): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // ── Inline code: `…`  (single backtick, no nesting) ──
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) {
                        append(c); i++
                    } else {
                        val style = SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color      = codeColor ?: Color.Unspecified,
                        )
                        pushStyle(style)
                        append(text, i + 1, end)
                        pop()
                        i = end + 1
                    }
                }

                // ── Bold: **…** or __…__ ──
                (c == '*' || c == '_') &&
                    i + 1 < n && text[i + 1] == c -> {
                    val marker = "$c$c"
                    val end = text.indexOf(marker, i + 2)
                    if (end < 0) {
                        append(c); append(c); i += 2
                    } else {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        // Recurse on the inner span so italics inside bold work.
                        append(parseInlineMarkdown(text.substring(i + 2, end), codeColor))
                        pop()
                        i = end + 2
                    }
                }

                // ── Italic: *…* or _…_ (single marker, with intra-word
                //    underscore protection for `_`: both the opener and the
                //    closer must sit on a non-word boundary so that
                //    `snake_case_word` parses as plain text, not as
                //    `snake<i>case</i>word`.                                 ──
                (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) -> {
                    val end = text.indexOf(c, i + 1)
                    val nextChar = if (end + 1 < n) text[end + 1] else null
                    val isBoldMarker = end >= 0 && nextChar == c
                    val isInWordUnderscore = c == '_' &&
                        end >= 0 && nextChar != null && nextChar.isLetterOrDigit()
                    if (end < 0 || isBoldMarker || isInWordUnderscore) {
                        append(c); i++
                    } else {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(parseInlineMarkdown(text.substring(i + 1, end), codeColor))
                        pop()
                        i = end + 1
                    }
                }

                else -> { append(c); i++ }
            }
        }
    }
