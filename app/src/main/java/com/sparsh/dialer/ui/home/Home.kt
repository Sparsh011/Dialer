package com.sparsh.dialer.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.ui.components.pressable
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.rememberHaptics

private class Tab(val label: String, val icon: ImageVector)

private val Tabs = listOf(
    Tab("Keypad", Icons.Rounded.Dialpad),
    Tab("Recents", Icons.Rounded.History),
    Tab("Contacts", Icons.Rounded.PersonOutline),
)

val BottomBarSpace = 92.dp

@Composable
fun Home(vm: AppViewModel, tab: MutableIntState, isDefault: Boolean, isTopScreen: Boolean) {
    val recentsState = rememberLazyListState()
    val contactsState = rememberLazyListState()
    val tabState = rememberSaveableStateHolder()
    val selected = tab.intValue

    // Tabs stay composed after their first visit, so a switch only moves layers on the GPU —
    // nothing is rebuilt mid-animation, which is what made the old transition stutter.
    val visited = remember { mutableStateListOf(selected) }
    if (selected !in visited) visited.add(selected)
    // Direction of travel, frozen per switch so the outgoing tab keeps moving the same way.
    var travel by remember { mutableIntStateOf(0) }
    var last by remember { mutableIntStateOf(selected) }
    if (last != selected) { travel = if (selected > last) 1 else -1; last = selected }

    Box(Modifier.fillMaxSize()) {
        Tabs.indices.forEach { index ->
            if (index !in visited) return@forEach
            val on = index == selected
            // Critically damped spring: fast start, long silky settle, no bounce.
            val p = animateFloatAsState(
                if (on) 1f else 0f,
                spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.001f),
                label = "Tab $index",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (on) 1f else 0f)
                    .graphicsLayer {
                        val v = p.value
                        // Material shared-axis: outgoing fades in the first 35%, incoming fades in after.
                        alpha = if (on) ((v - 0.25f) / 0.75f).coerceIn(0f, 1f) else ((v - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        val shift = 34.dp.toPx() * (1f - v)
                        translationX = if (on) shift * travel else -shift * travel
                        val sc = 0.965f + 0.035f * v
                        scaleX = sc; scaleY = sc
                    }
                    .then(if (on) Modifier else Modifier.pointerInput(Unit) {
                        // A hidden tab swallows any stray touch that reaches it.
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }),
            ) {
                tabState.SaveableStateProvider(index) {
                    when (index) {
                        0 -> KeypadScreen(vm)
                        1 -> RecentsScreen(vm, recentsState, isDefault, isTopScreen && on)
                        else -> ContactsScreen(vm, contactsState)
                    }
                }
            }
        }
        BottomBar(selected, Modifier.align(Alignment.BottomCenter).zIndex(5f)) { tab.intValue = it }
    }
}

@Composable
private fun BottomBar(selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val extra = LocalExtra.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val itemWidth = 102.dp
    val indicator by animateDpAsState(
        itemWidth * selected, spring(dampingRatio = 0.8f, stiffness = 380f), label = "indicator",
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .shadow(if (extra.isDark) 0.dp else 24.dp, CircleShape, spotColor = Color(0x26000000), ambientColor = Color(0x14000000))
            .background(extra.card.copy(alpha = 0.96f), CircleShape)
            .border(1.dp, extra.hairline, CircleShape)
            .padding(5.dp),
    ) {
        // A faint tinted lozenge glides under the active tab; the label carries the colour.
        Box(
            Modifier
                .graphicsLayer { translationX = indicator.toPx() }
                .size(itemWidth, 48.dp)
                .background(accent.copy(alpha = if (extra.isDark) 0.16f else 0.10f), CircleShape),
        )
        Row {
            Tabs.forEachIndexed { index, tab ->
                val on = index == selected
                val tint by androidx.compose.animation.animateColorAsState(
                    if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint",
                )
                val pop = animateFloatAsState(if (on) 1f else 0.92f, spring(0.45f, 420f), label = "pop")
                Row(
                    Modifier
                        .size(itemWidth, 48.dp)
                        .pressable(CircleShape, 0.92f, haptic = false) {
                            if (!on) { haptics.select(); focus.clearFocus(force = true); onSelect(index) }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        tab.icon, null, tint = tint,
                        modifier = Modifier.size(20.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}
