package com.sparsh.dialer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlinx.coroutines.launch

/** Scrolls the big header away so the first content row sits right under the pinned bar. */
val LocalCollapseHeader = compositionLocalOf<() -> Unit> { {} }

/**
 * One UI style one-handed layout: the top third of the screen is a big, calm title area so all
 * interactive content starts within thumb reach. It scrolls away into a compact pinned bar and
 * snaps so it never rests half-collapsed.
 */
@Composable
fun BigHeaderList(
    title: String,
    state: LazyListState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    headerFraction: Float = 0.36f,
    bottomPadding: Dp = 24.dp,
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable BoxScope.() -> Unit)? = null,
    compactContent: (@Composable RowScope.() -> Unit)? = null,
    pinListBelowBar: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = statusTop + 60.dp
    val headerHeight = (LocalConfiguration.current.screenHeightDp * headerFraction).dp.coerceAtLeast(barHeight + 120.dp)
    val barPx = with(density) { barHeight.toPx() }
    val listTop = if (pinListBelowBar) barHeight else 0.dp
    val collapseOffset = if (pinListBelowBar) 0 else -barPx.toInt()
    val rangePx = with(density) { (headerHeight - barHeight).toPx() }
    val bg = MaterialTheme.colorScheme.background
    val glow = MaterialTheme.colorScheme.primary
    val glowAlpha = if (com.sparsh.dialer.ui.theme.LocalExtra.current.isDark) 0.16f else 0.09f
    val scope = rememberCoroutineScope()
    val collapse: () -> Unit = remember(state, collapseOffset) {
        { scope.launch { if (state.firstVisibleItemIndex == 0) state.animateScrollToItem(1, collapseOffset) } }
    }

    // 0 = fully expanded, 1 = collapsed. Read only inside draw/layer lambdas => no recomposition while scrolling.
    val fraction: () -> Float = remember(state, rangePx) {
        { if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / rangePx).coerceIn(0f, 1f) }
    }

    val hasCompactContent = compactContent != null
    LaunchedEffect(state, rangePx, hasCompactContent, collapseOffset) {
        if (hasCompactContent) return@LaunchedEffect
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && state.firstVisibleItemIndex == 0) {
                val off = state.firstVisibleItemScrollOffset
                if (off > 0 && off < rangePx - 1) {
                    if (off > rangePx * 0.45f && state.canScrollForward) state.animateScrollToItem(1, collapseOffset)
                    else state.animateScrollToItem(0)
                }
            }
        }
    }

    val shortList by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount < 16 } }
    val drawHalo: DrawScope.(Float, Float) -> Unit = { height, top ->
        val alpha = glowAlpha * (1f - fraction())
        if (alpha > 0f && size.width > 0f && height > 0f) {
            // Radius is capped by the header height so the glow has fully faded before the
            // header ends — otherwise it stops in a hard edge where the list begins.
            val radius = minOf(size.width * 0.85f, height * 0.72f)
            drawRect(
                Brush.radialGradient(
                    0f to glow.copy(alpha = alpha),
                    0.45f to glow.copy(alpha = alpha * 0.45f),
                    1f to Color.Transparent,
                    center = Offset(size.width * 0.5f, top + height * 0.2f),
                    radius = radius,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, height),
            )
        }
    }
    Box(modifier.fillMaxSize().drawBehind {
        if (pinListBelowBar && !hasCompactContent) {
            clipRect(bottom = listTop.roundToPx().toFloat()) {
                drawHalo(headerHeight.toPx(), -state.firstVisibleItemScrollOffset.toFloat())
            }
        }
    }) {
        CompositionLocalProvider(LocalCollapseHeader provides collapse) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().imePadding().padding(top = listTop).clipToBounds(),
            contentPadding = PaddingValues(bottom = bottomPadding + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item(key = "__header", contentType = "header") {
                if (hasCompactContent) Spacer(Modifier.height(barHeight - listTop)) else Box(
                    Modifier.fillMaxWidth().height(headerHeight - listTop).drawBehind {
                        if (pinListBelowBar) {
                            // Keep this part of the glow inside the scrolling header, even during overscroll.
                            clipRect { drawHalo(headerHeight.toPx(), -listTop.roundToPx().toFloat()) }
                        } else {
                            drawHalo(size.height, 0f)
                        }
                    },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = (statusTop + 8.dp - listTop).coerceAtLeast(0.dp),
                                bottom = 52.dp + (listTop - statusTop - 8.dp).coerceAtLeast(0.dp),
                            )
                            .graphicsLayer {
                                val f = fraction()
                                alpha = (1f - f * 1.7f).coerceIn(0f, 1f)
                                val s = 1f - 0.08f * f
                                scaleX = s; scaleY = s
                                translationY = state.firstVisibleItemScrollOffset * 0.35f * (if (state.firstVisibleItemIndex == 0) 1f else 0f)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (header != null) header() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                title, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            if (subtitle != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    // Action row sits at the bottom of the big header: reachable with a thumb.
                    val showActions by remember(fraction) { derivedStateOf { fraction() < 1f / 3f } }
                    if (showActions) Row(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 12.dp, bottom = 4.dp)
                            .graphicsLayer { alpha = (1f - fraction() * 3f).coerceIn(0f, 1f) },
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
            content()
            // Short lists (e.g. search results) still need room to tuck the header away.
            if (shortList && !hasCompactContent) item(key = "__filler", contentType = "filler") { Spacer(Modifier.height(headerHeight - barHeight)) }
        }
        }

        // Pinned compact bar
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .drawBehind {
                    val alpha = if (hasCompactContent) 1f else ((fraction() - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    drawRect(bg, alpha = alpha)
                }
                .padding(top = statusTop, start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compactContent != null) {
                AnimatedVisibility(
                    visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                    exit = ExitTransition.None,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, content = compactContent)
                }
            } else {
                if (onBack != null) TopIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack) else Spacer(Modifier.width(14.dp))
                Text(
                    title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .graphicsLayer {
                            val a = ((fraction() - 0.6f) / 0.4f).coerceIn(0f, 1f)
                            alpha = a
                            translationY = (1f - a) * 14.dp.toPx()
                        },
                )
                // Only composed once collapsed, so invisible buttons can never be hit by accident.
                val collapsed by remember(fraction) { derivedStateOf { fraction() > 0.7f } }
                if (collapsed) Row(
                    Modifier.graphicsLayer { alpha = ((fraction() - 0.7f) / 0.3f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}
