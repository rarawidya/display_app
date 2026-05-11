package com.example.displayapp.presentation.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import com.example.displayapp.ui.theme.Dim

/**
 * Floating pill-shaped bottom navigation matching the redesigned cockpit.
 *
 * Layout:
 *   ┌──────────────────────────────────────────┐
 *   │     [Home]      [Chart]      [Data]      │   ← pill-shaped container,
 *   └──────────────────────────────────────────┘     elevated above gesture nav
 *
 * - Single rounded container (32dp radius) with the theme's surfaceVariant fill,
 *   producing a floating "card" effect above content.
 * - Active tab: icon + label tinted with [MaterialTheme.colorScheme.primary].
 * - Inactive: onSurfaceVariant.
 * - Per-tab recomposition: only the tab whose `selected` flag flips recomposes.
 */
@Composable
fun EvBottomBar(
    currentDestination: NavDestination?,
    onTabSelected: (Destination) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Dim.md, vertical = Dim.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(32.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dim.sm, vertical = 10.dp)
                .height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomTabs.forEach { tab ->
                val selected = currentDestination?.hierarchy?.any {
                    it.route == tab.destination.route
                } == true
                BottomTabItem(
                    tab = tab,
                    selected = selected,
                    onClick = { onTabSelected(tab.destination) }
                )
            }
        }
    }
}

@Composable
private fun BottomTabItem(
    tab: TopLevel,
    selected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val animatedTint by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        label = "tab-tint"
    )

    val interaction = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = Dim.lg, vertical = 4.dp)
            .semantics { /* tab role propagates from clickable */ }
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            modifier = Modifier.size(22.dp),
            tint = animatedTint
        )
        Text(
            text = tab.label,
            fontSize = 12.sp,
            color = animatedTint
        )
    }
}
