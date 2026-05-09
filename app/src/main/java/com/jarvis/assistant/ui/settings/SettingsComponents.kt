package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A visual block that groups related rows under an optional title and footer.
 *
 * Pattern mirrors native iOS/Android Settings: rows sit in a rounded card, the
 * section label sits above the card in small-caps, and an optional caption sits
 * below. Dividers between rows are drawn automatically.
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    description: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title.uppercase(),
                color = SettingsTheme.Cyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        if (description != null) {
            Text(
                text = description,
                color = SettingsTheme.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsTheme.Surface, SettingsTheme.GroupShape)
                .padding(vertical = 4.dp),
        ) {
            content()
        }
        if (footer != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = footer,
                color = SettingsTheme.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Thin divider used between rows inside a [SettingsGroup]. */
@Composable
internal fun SettingsRowDivider() {
    HorizontalDivider(
        color = SettingsTheme.BgDark,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/** Standard tall-enough tap-target row used by every row variant. */
@Composable
private fun BaseRow(
    onClick: (() -> Unit)?,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Row(
        modifier = clickable.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = verticalAlignment,
    ) {
        content()
    }
}

/**
 * Title + optional description stacked column — shared helper.
 */
@Composable
private fun RowScope.TitleBlock(
    title: String,
    description: String?,
    titleColor: Color = SettingsTheme.TextPrimary,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            color = if (enabled) titleColor else SettingsTheme.TextMuted,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                color = SettingsTheme.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: tappable category (used on the root Settings screen).
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    BaseRow(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(SettingsTheme.Cyan.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = SettingsTheme.Cyan,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        TitleBlock(title = title, description = description)
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = SettingsTheme.TextFaint,
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: toggle / switch
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    BaseRow(onClick = if (enabled) { { onCheckedChange(!checked) } } else null) {
        TitleBlock(title = title, description = description, enabled = enabled)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor   = SettingsTheme.Cyan,
                checkedTrackColor   = SettingsTheme.Cyan.copy(alpha = 0.4f),
                uncheckedThumbColor = SettingsTheme.TextMuted,
                uncheckedTrackColor = SettingsTheme.Surface,
                uncheckedBorderColor = SettingsTheme.Border,
            ),
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: text value (shown as label + value + chevron or trailing content)
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsValueRow(
    title: String,
    value: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    BaseRow(onClick = onClick) {
        TitleBlock(title = title, description = description)
        if (value.isNotBlank()) {
            Text(
                text = value,
                color = SettingsTheme.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SettingsTheme.TextFaint,
            )
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: dropdown / select (single-choice)
 * ──────────────────────────────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> SettingsDropdownRow(
    title: String,
    description: String? = null,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, color = SettingsTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!description.isNullOrBlank()) {
            Text(description, color = SettingsTheme.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = label(selected),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                colors = settingsTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(SettingsTheme.Surface),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(label(option), color = SettingsTheme.TextPrimary) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: text input
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsTextFieldRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String? = null,
    placeholder: String? = null,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var revealed by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, color = SettingsTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!description.isNullOrBlank()) {
            Text(description, color = SettingsTheme.TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it, color = SettingsTheme.TextFaint) } },
            visualTransformation = when {
                isSecret && !revealed -> PasswordVisualTransformation()
                else                  -> VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isSecret) KeyboardType.Password else keyboardType,
            ),
            trailingIcon = if (isSecret) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = SettingsTheme.TextMuted,
                        )
                    }
                }
            } else null,
            colors = settingsTextFieldColors(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: slider
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String? = null,
    valueLabel: ((Float) -> String)? = null,
    steps: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = SettingsTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            valueLabel?.invoke(value)?.let {
                Text(it, color = SettingsTheme.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!description.isNullOrBlank()) {
            Text(description, color = SettingsTheme.TextMuted, fontSize = 12.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor       = SettingsTheme.Cyan,
                activeTrackColor = SettingsTheme.Cyan,
                inactiveTrackColor = SettingsTheme.Border,
            ),
        )
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Row: primary action button (inline, inside a group)
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsActionRow(
    title: String,
    description: String? = null,
    actionLabel: String,
    destructive: Boolean = false,
    confirm: Boolean = false,
    confirmCopy: String = "Confirm",
    onAction: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(title, color = SettingsTheme.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (!description.isNullOrBlank()) {
            Text(description, color = SettingsTheme.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(6.dp))
        }

        val accent = if (destructive) SettingsTheme.Destructive else SettingsTheme.Cyan

        if (confirm && confirming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirming = false }) {
                    Text("Cancel", color = SettingsTheme.TextMuted)
                }
                TextButton(
                    onClick = {
                        onAction()
                        confirming = false
                    },
                ) {
                    Text(confirmCopy, color = accent, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            OutlinedButton(
                onClick = {
                    if (confirm) confirming = true else onAction()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = BorderStroke(1.dp, accent),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(actionLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/* ──────────────────────────────────────────────────────────────────────────
 * Inline helper card — used for explanatory / "coming soon" / status copy.
 * ──────────────────────────────────────────────────────────────────────── */

@Composable
internal fun SettingsInfoCard(
    title: String? = null,
    body: String,
    accent: Color = SettingsTheme.Cyan,
    background: Color = SettingsTheme.InfoBg,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, SettingsTheme.RowShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) {
            Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = SettingsTheme.TextMuted, fontSize = 12.sp)
    }
}

/** Full-width primary button (outside of a group). */
@Composable
internal fun SettingsPrimaryButton(
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) SettingsTheme.Destructive else SettingsTheme.Cyan
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SettingsTheme.Surface,
            contentColor   = tint,
            disabledContainerColor = SettingsTheme.Surface,
            disabledContentColor   = SettingsTheme.TextFaint,
        ),
        border = BorderStroke(1.dp, if (enabled) tint else SettingsTheme.Border),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
