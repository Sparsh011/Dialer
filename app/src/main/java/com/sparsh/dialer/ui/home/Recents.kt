package com.sparsh.dialer.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Whatsapp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.CallGroup
import com.sparsh.dialer.data.CallLogRepo
import com.sparsh.dialer.data.CallType
import com.sparsh.dialer.data.Contact
import com.sparsh.dialer.data.RecentItem
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.ui.components.*
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlin.math.abs

const val FEATURE_WIFI = 0x8
const val FEATURE_HD = 0x4

@Composable
fun RecentsScreen(vm: AppViewModel, state: LazyListState, isDefault: Boolean, isTopScreen: Boolean) {
    val items by vm.recents.collectAsStateWithLifecycle()
    val missedOnly by vm.missedOnly.collectAsStateWithLifecycle()
    val query by vm.recentsQuery.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val sims by Sims.sims.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val searchFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var focusSearch by remember { mutableStateOf(false) }
    var expanded by remember { mutableLongStateOf(-1L) }
    var deleteRevealedFor by remember { mutableLongStateOf(-1L) }
    var deleting by remember { mutableStateOf<CallGroup?>(null) }

    LaunchedEffect(query) {
        if (searchOpen) state.scrollToItem(0)
    }

    val closeSearch = {
        focus.clearFocus()
        keyboard?.hide()
        vm.recentsQuery.value = ""
        searchOpen = false
    }
    BackHandler(enabled = searchOpen && isTopScreen, onBack = closeSearch)
    BackHandler(enabled = deleteRevealedFor != -1L && isTopScreen) { deleteRevealedFor = -1L }

    LifecycleResumeEffect(Unit) {
        CallLogRepo.markMissedSeen(context)
        onPauseOrDispose { }
    }

    val list = items
    val callCount = remember(list) { list.orEmpty().sumOf { (it as? RecentItem.Row)?.group?.count ?: 0 } }
    val searching = query.isNotBlank()
    BigHeaderList(
        title = if (missedOnly) "Missed" else "Recents",
        subtitle = when {
            list == null -> null
            searching -> if (callCount == 0) "No matches" else "$callCount matching calls"
            callCount == 0 -> "No calls yet"
            missedOnly -> "$callCount missed calls"
            else -> null
        },
        state = state,
        bottomPadding = BottomBarSpace,
        pinListBelowBar = true,
        actions = {
            TopIcon(Icons.Rounded.Search, "Search call history") {
                deleteRevealedFor = -1L
                focusSearch = true
                searchOpen = true
            }
            TopIcon(
                Icons.Rounded.FilterList, "Show missed calls only",
                tint = if (missedOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            ) {
                vm.missedOnly.value = !missedOnly
                expanded = -1L
                deleteRevealedFor = -1L
            }
            TopIcon(Icons.Outlined.Settings, "Settings") { actions.open(Screen.Settings) }
        },
        compactContent = if (searchOpen) {
            {
                SearchField(query, "Search name or number", Modifier.weight(1f), focusRequester = searchFocus) {
                    vm.recentsQuery.value = it
                    expanded = -1L
                    deleteRevealedFor = -1L
                }
                TopIcon(Icons.Rounded.Close, "Close search", onClick = closeSearch)
                LaunchedEffect(focusSearch) {
                    if (focusSearch) {
                        searchFocus.requestFocus()
                        keyboard?.show()
                        focusSearch = false
                    }
                }
            }
        } else null,
    ) {
        if (!isDefault && !searching) item(key = "__default", contentType = "banner") { DefaultBanner(actions.requestDefault) }
        if (list != null && list.isEmpty()) {
            item(key = "__empty") {
                EmptyState(
                    if (searching) Icons.Rounded.Search else Icons.Rounded.History,
                    when { searching -> "Nothing found"; missedOnly -> "Nothing missed"; else -> "No recent calls" },
                    when {
                        searching -> "No calls match “${query.trim()}”."
                        missedOnly -> "Missed and declined calls show up here."
                        else -> "Calls you make and receive will appear here."
                    },
                )
            }
        }
        list.orEmpty().forEach { item ->
            when (item) {
                is RecentItem.Header -> stickyHeader(key = item.stableKey, contentType = "date") {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                            .semantics { heading() }.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
                is RecentItem.Row -> item(key = item.stableKey, contentType = "call") {
                    RecentRow(
                        item = item,
                        contact = contacts.byKey[item.group.key],
                        twoSims = sims.size > 1,
                        expanded = expanded == item.group.id,
                        deleteRevealed = deleteRevealedFor == item.group.id,
                        modifier = Modifier.animateItem(),
                        onToggle = {
                            deleteRevealedFor = -1L
                            haptics.tick()
                            expanded = if (expanded == item.group.id) -1 else item.group.id
                        },
                        onRevealChange = { reveal ->
                            if (reveal) {
                                deleteRevealedFor = item.group.id
                                expanded = -1L
                            } else if (deleteRevealedFor == item.group.id) {
                                deleteRevealedFor = -1L
                            }
                        },
                        onDelete = {
                            deleteRevealedFor = -1L
                            deleting = item.group
                        },
                    )
                }
            }
        }
    }

    deleting?.let { g ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = LocalExtra.current.card,
            title = { Text(if (g.count > 1) "Delete ${g.count} calls?" else "Delete this call?") },
            text = { Text("${g.name ?: Fmt.number(g.number)} · ${Fmt.relative(g.date)}. This also removes it from the phone's call log.") },
            confirmButton = {
                TextButton(onClick = { haptics.reject(); vm.delete(g.ids); deleting = null; expanded = -1 }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DefaultBanner(onClick: () -> Unit) {
    val extra = LocalExtra.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .pressable(RoundedCornerShape(22.dp), 0.98f, onClick = onClick)
            .background(extra.card)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Make Dialer your phone app", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "Needed for the call screen and SIM selection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("Set up", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RecentRow(
    item: RecentItem.Row,
    contact: Contact?,
    twoSims: Boolean,
    expanded: Boolean,
    deleteRevealed: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onRevealChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val g = item.group
    val actions = LocalActions.current
    val context = LocalContext.current
    val extra = LocalExtra.current
    val missed = g.type == CallType.MISSED
    val display = g.name ?: Fmt.number(g.number)
    val canCall = g.key.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }

    // Rows sit flat on the page. The selected one rises into its own bordered card.
    val lift = animateFloatAsState(if (expanded) 1f else 0f, spring(0.75f, 380f), label = "lift")
    val gap by animateDpAsState(if (expanded) 8.dp else 0.dp, spring(0.75f, 380f), label = "gap")
    val shape = RoundedCornerShape(24.dp)

    Column(
        modifier
            .padding(horizontal = 8.dp)
            .padding(vertical = gap.coerceAtLeast(0.dp))
            .graphicsLayer {
                val v = lift.value.coerceIn(0f, 1f)
                shadowElevation = if (extra.isDark) 0f else 14.dp.toPx() * v
                this.shape = shape
                spotShadowColor = Color(0x30000000)
                ambientShadowColor = Color(0x18000000)
            }
            .clip(shape)
            .background(extra.card.copy(alpha = lift.value.coerceIn(0f, 1f)))
            .border(1.dp, extra.hairline.copy(alpha = lift.value.coerceIn(0f, 1f)), shape),
    ) {
        RecentSwipeActions(canCall, deleteRevealed, onRevealChange, { actions.callRecent(g) }, onDelete) { swipeModifier ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(swipeModifier)
                    .pressable(
                        shape, 0.985f, haptic = false,
                        onLongClick = { onRevealChange(false); menuOpen = true },
                        onClick = { if (deleteRevealed) onRevealChange(false) else onToggle() },
                    )
                    .rowDivider(extra.hairline, !item.last && !expanded, inset = 74.dp)
                    .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(g.name, g.key.ifEmpty { "?" }, g.photoUri, 46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            display, style = MaterialTheme.typography.titleMedium,
                            color = if (missed) extra.missed else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (g.count > 1) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${g.count}", fontSize = 13.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (missed) extra.missed else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .semantics { contentDescription = "${g.count} calls" }
                                    .background((if (missed) extra.missed else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.15f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(typeIcon(g.type), CallType.label(g.type), Modifier.size(14.dp), tint = typeColor(g.type))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when {
                                g.name != null -> g.numberLabel ?: Fmt.number(g.number)
                                g.key.isEmpty() -> "Private number"
                                else -> CallType.label(g.type).removeSuffix(" call")
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (g.features and FEATURE_WIFI != 0) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.Wifi, "Wi-Fi call", Modifier.size(13.dp), tint = extra.subtle)
                        }
                        if (twoSims) {
                            Spacer(Modifier.width(8.dp))
                            SimBadge(Sims.slotFor(g.accountId))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    Fmt.time(g.date), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (missed) extra.missed else extra.subtle,
                )
            }
            ContactMenu(menuOpen, { menuOpen = false }, g.number, g.name, contact, g.accountId)
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(0.78f, 420f, IntSize.VisibilityThreshold)) + fadeIn(),
            exit = shrinkVertically(spring(1f, 600f, IntSize.VisibilityThreshold)) + fadeOut(),
        ) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                Text(
                    buildString {
                        if (g.key.isNotEmpty()) append(Fmt.number(g.number)).append("   ·   ")
                        append(CallType.label(g.type))
                        val d = Fmt.duration(g.duration)
                        if (d.isNotEmpty()) append(", ").append(d)
                    },
                    style = MaterialTheme.typography.bodySmall, color = extra.subtle,
                    modifier = Modifier.padding(start = 60.dp, bottom = 14.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Tap to call with the default SIM; hold to pick a SIM.
                    PopIn(0, Modifier.weight(1f)) {
                        PillButton(
                            Icons.Rounded.Call, "Call", Modifier.fillMaxWidth(),
                            container = if (canCall) extra.success else extra.fill,
                            content = if (canCall) extra.onSuccess else extra.subtle,
                            filled = canCall,
                            onLongClick = { if (canCall) actions.call(g.number, g.name, true) },
                        ) { if (canCall) actions.call(g.number, g.name, false) }
                    }
                    if (canCall) PopIn(1) {
                        CircleAction(Icons.Rounded.Whatsapp, "WhatsApp", size = 54.dp) {
                            val normalized = contact?.numbers?.firstOrNull { it.key == g.key }?.normalizedNumber
                            openWhatsApp(context, g.number, normalized, g.accountId)
                        }
                    }
                    PopIn(2) {
                        CircleAction(Icons.Rounded.Info, "Info", size = 54.dp) { actions.open(Screen.Detail(g.number)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSwipeActions(
    canCall: Boolean,
    deleteRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onCall: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val call by rememberUpdatedState(onCall)
    val reveal by rememberUpdatedState(onRevealChange)
    val isRevealed by rememberUpdatedState(deleteRevealed)
    val haptics = rememberHaptics()
    val colors = LocalExtra.current
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val revealWidth = 88.dp
    val revealPx = with(density) { revealWidth.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val target = when {
        dragging -> drag
        deleteRevealed -> -revealPx
        else -> 0f
    }
    val animated by animateFloatAsState(
        target, if (dragging) snap() else spring(stiffness = 650f), label = "Recent call swipe",
    )
    val offset = if (dragging) drag else animated

    Box(
        Modifier.fillMaxWidth()
            .semantics {
                customActions = buildList {
                    if (canCall && !deleteRevealed) {
                        add(CustomAccessibilityAction("Call with last used SIM") { haptics.select(); call(); true })
                    }
                    add(CustomAccessibilityAction(if (deleteRevealed) "Hide delete" else "Show delete") {
                        reveal(!deleteRevealed)
                        true
                    })
                }
            }
            .pointerInput(direction, canCall, revealPx) {
                // 25% more travel than before, so a casual sideways brush can't place a call.
                val threshold = minOf(150.dp.toPx(), size.width * 0.50f)
                val limit = 180.dp.toPx()
                var beganRevealed = false
                var vibrated = false

                detectHorizontalDragGestures(
                    onDragStart = {
                        beganRevealed = isRevealed
                        vibrated = false
                        drag = if (beganRevealed) -revealPx else 0f
                        dragging = true
                    },
                    onDragCancel = { dragging = false },
                    onDragEnd = {
                        dragging = false
                        when {
                            beganRevealed -> reveal(drag < -revealPx / 2f)
                            drag <= -threshold -> reveal(true)
                            canCall && drag >= threshold -> call()
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    val minimum = if (beganRevealed) -revealPx else -limit
                    val maximum = if (beganRevealed || !canCall) 0f else limit
                    drag = (drag + amount * direction).coerceIn(minimum, maximum)
                    val crossed = if (beganRevealed) drag >= -revealPx / 2f
                        else drag <= -threshold || (canCall && drag >= threshold)
                    if (crossed && !vibrated) {
                        // Armed: letting go now will act. Make that impossible to miss.
                        haptics.strong()
                        vibrated = true
                    } else if (!crossed && vibrated) {
                        // Pulled back under the threshold: disarmed.
                        haptics.release()
                        vibrated = false
                    }
                }
            },
    ) {
        if (abs(offset) >= 1f) {
            val width = with(density) { abs(offset).toDp() }
            val trailing = offset < 0f
            val canDelete = trailing && deleteRevealed && !dragging
            val scheme = MaterialTheme.colorScheme
            Box(
                Modifier.matchParentSize(),
                contentAlignment = if (trailing) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                val action = if (canDelete) Modifier.pressable(haptic = false, onClick = onDelete)
                    .semantics { contentDescription = "Delete calls" } else Modifier
                Box(
                    Modifier.width(width).fillMaxHeight()
                        .background(if (trailing) scheme.errorContainer else colors.successContainer)
                        .then(action),
                    contentAlignment = Alignment.Center,
                ) {
                    if (width >= 28.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (trailing) Icons.Outlined.DeleteOutline else Icons.Rounded.Call,
                                null, Modifier.size(22.dp), tint = if (trailing) scheme.onErrorContainer else colors.success,
                            )
                            if (trailing && width >= 64.dp) {
                                Text("Delete", style = MaterialTheme.typography.labelMedium, color = scheme.onErrorContainer)
                            }
                        }
                    }
                }
            }
        }
        content(Modifier.graphicsLayer { translationX = offset * direction })
    }
}

/** Staggered spring pop used for action buttons. */
@Composable
fun PopIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 34L)
        a.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 400f))
    }
    Box(
        modifier.graphicsLayer {
            val v = a.value
            alpha = v.coerceIn(0f, 1f)
            val s = 0.6f + 0.4f * v
            scaleX = s; scaleY = s
            translationY = (1f - v) * 14.dp.toPx()
        },
    ) { content() }
}
