package com.example.displayapp.presentation.ui.settings.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.displayapp.presentation.ui.icons.EvIcons

/**
 * Section grouping with header + grouped rounded card.
 *
 *   Section
 *   ┌──────────────────────┐
 *   │ row 1                │
 *   │ ── divider ──        │
 *   │ row 2                │
 *   └──────────────────────┘
 *
 * Pattern mirrors Tesla's grouped-settings UX: section heading sits *above*
 * the card, then rows live inside a single surface to give the eye a clear
 * "this group acts as one unit".
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

/** Thin divider used between rows inside a [SettingsSection]. */
@Composable
fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    )
}

/* -------------------------------------------------------------------------- */
/*  Row primitives                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Generic row scaffold — leading icon, title + optional subtitle, trailing slot.
 * Most other rows compose this internally.
 */
@Composable
fun PreferenceRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(12.dp))
            trailing()
        }
    }
}

/** Switch-toggle row. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: ImageVector? = null
) {
    PreferenceRow(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

/** Action row: title + chevron, fires [onClick]. Useful for nav / dialogs. */
@Composable
fun ActionRow(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leadingTint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    PreferenceRow(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        leadingTint = leadingTint,
        onClick = onClick,
        trailing = {
            Icon(
                imageVector = EvIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

/** Display-only value row (no chevron). */
@Composable
fun ValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    PreferenceRow(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                fontWeight = FontWeight.Medium
            )
        }
    )
}

/**
 * Expandable single-choice row. Tapping toggles an inline radio list.
 *
 *   Title                              [current value] ▾
 *     ○ Option A
 *     ● Option B
 *     ○ Option C
 *
 * Inline expansion keeps the picker scrollable inside the settings list,
 * unlike a modal dialog — fits the Tesla/Rivian large-touch-target pattern.
 */
@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    labelFor: (T) -> String,
    descriptionFor: (T) -> String? = { null },
    leadingIcon: ImageVector? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron-rot"
    )
    Column {
        PreferenceRow(
            title = title,
            subtitle = labelFor(selected),
            leadingIcon = leadingIcon,
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    imageVector = EvIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation)
                )
            }
        )
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option); expanded = false }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = option == selected,
                            onClick = { onSelected(option); expanded = false }
                        )
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text(
                                text = labelFor(option),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            descriptionFor(option)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A row showing a runtime-permission's status + a "Manage" button. */
@Composable
fun PermissionRow(
    name: String,
    rationale: String,
    granted: Boolean,
    notApplicable: Boolean = false,
    onManage: () -> Unit
) {
    val (label, dotColor) = when {
        notApplicable -> "Not required" to MaterialTheme.colorScheme.onSurfaceVariant
        granted -> "Granted" to com.example.displayapp.ui.theme.EvGreen
        else    -> "Denied"  to com.example.displayapp.ui.theme.EvRed
    }
    PreferenceRow(
        title = name,
        subtitle = rationale,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!notApplicable && !granted) {
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = onManage) { Text("Manage") }
                }
            }
        }
    )
}

/** Standard destructive-confirm dialog used by Storage actions. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    cancelLabel: String = "Cancel",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        }
    )
}
