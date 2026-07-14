package com.innodrive.evdash.presentation.ui.charts

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.innodrive.evdash.presentation.state.TimeRange

/**
 * Segmented control for selecting the chart time window.
 * Used by the Charts tab. Pure stateless — caller owns the selection.
 */
@Composable
fun TimeRangeSelector(
    selected: TimeRange,
    onSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp)
    ) {
        TimeRange.entries.forEach { range ->
            val isSelected = range == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "rangeBg"
            )
            val fg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "rangeFg"
            )
            Text(
                text = range.label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelected(range) }
                    .background(bg)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
