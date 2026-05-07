package com.jarvis.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.memory.db.entity.ConversationTurn

private val BubbleUser       = Color(0xFF003344)
private val BubbleAssistant  = Color(0xFF1A0F2E)
private val TextCyan         = Color(0xFF00BCD4)
private val TextPurple       = Color(0xFFCE93D8)
private val TextBody         = Color(0xFFCCCCDD)
private val PanelBg          = Color(0xFF0C0C18)
private val ChipBg           = Color(0xFF161628)
private val ChipText         = Color(0xFF505065)

@Composable
fun ConversationPanel(
    turns: List<ConversationTurn>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new turns arrive
    val turnCount = turns.size
    LaunchedEffect(turnCount) {
        if (expanded && turnCount > 0) {
            listState.animateScrollToItem(turnCount - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // ── Collapse/expand chip ──────────────────────────────────────────────
        val latest = turns.lastOrNull()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ChipBg)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (latest != null)
                    latest.content.take(60) + if (latest.content.length > 60) "…" else ""
                else
                    "No conversation yet",
                color = ChipText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▲" else "▼",
                color = ChipText,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // ── Expandable conversation list ──────────────────────────────────────
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit  = shrinkVertically()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(PanelBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(turns, key = { it.id }) { turn ->
                    TurnBubble(turn)
                }
            }
        }
    }
}

@Composable
private fun TurnBubble(turn: ConversationTurn) {
    val isUser = turn.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd   = 12.dp,
                        bottomStart = if (isUser) 12.dp else 2.dp,
                        bottomEnd   = if (isUser) 2.dp else 12.dp
                    )
                )
                .background(if (isUser) BubbleUser else BubbleAssistant)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text       = if (isUser) "You" else "Jarvis",
                color      = if (isUser) TextCyan else TextPurple,
                fontSize   = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(2.dp))
            // Render inline Markdown — assistant replies frequently use **bold**
            // and `code` fragments; rendering them as raw asterisks/backticks
            // looked tonally off and added visual noise.  User turns never
            // contain Markdown (they're STT transcripts) so this is a no-op
            // for them.
            Text(
                text       = if (isUser) androidx.compose.ui.text.AnnotatedString(turn.content)
                             else renderMarkdownInline(turn.content, codeColor = TextCyan),
                color      = TextBody,
                fontSize   = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
