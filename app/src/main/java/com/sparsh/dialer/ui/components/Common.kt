package com.sparsh.dialer.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.sparsh.dialer.data.CallType
import com.sparsh.dialer.data.SimInfo
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.ui.theme.Brand
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val BouncySpring = spring<Float>(dampingRatio = 0.5f, stiffness = 420f)
val SnappySpring = spring<Float>(dampingRatio = 0.78f, stiffness = 520f)
val GentleSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 260f)

/**
 * Click target with a springy press-scale. All taps/long-presses in the app flow through here,
 * so haptics are guaranteed everywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    shape: Shape = RoundedCornerShape(0.dp),
    pressedScale: Float = 0.95f,
    enabled: Boolean = true,
    haptic: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) pressedScale else 1f, BouncySpring, label = "press")
    val haptics = rememberHaptics()
    return this
        .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(shape)
        .combinedClickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onLongClick = if (onLongClick != null) {
                { if (haptic) haptics.heavy(); onLongClick() }
            } else null,
            onClick = { if (haptic) haptics.click(); onClick() },
        )
}

/** light-theme ink / dark-theme ink per hue; backgrounds are a faint wash of the same hue. */
private val AvatarHues = listOf(
    Color(0xFFD81F5C) to Color(0xFFFF7FA3),
    Color(0xFF5B45E0) to Color(0xFFA899FF),
    Color(0xFF12827E) to Color(0xFF7FDAD6),
    Color(0xFFC95A22) to Color(0xFFFFAE85),
    Color(0xFF2468CF) to Color(0xFF8DBBFF),
    Color(0xFF0B8A60) to Color(0xFF6FE0B8),
)

private val photoCache = LruCache<String, ImageBitmap>(120)

private suspend fun loadPhoto(context: Context, uri: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
}

@Composable
fun Avatar(name: String?, seed: String, photoUri: String?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var photo by remember(photoUri) { mutableStateOf(photoUri?.let { photoCache.get(it) }) }
    if (photoUri != null && photo == null) {
        LaunchedEffect(photoUri) {
            loadPhoto(context, photoUri)?.let { photoCache.put(photoUri, it); photo = it }
        }
    }
    val dark = LocalExtra.current.isDark
    val pair = AvatarHues[(seed.hashCode() and 0x7fffffff) % AvatarHues.size]
    val ink = if (dark) pair.second else pair.first
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(ink.copy(alpha = if (dark) 0.16f else 0.11f))
            .border(1.dp, ink.copy(alpha = if (dark) 0.22f else 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val p = photo
        if (p != null) {
            Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val initials = Fmt.initials(name)
            if (initials.isEmpty()) {
                Icon(Icons.Outlined.PersonOutline, null, Modifier.size(size * 0.5f), tint = ink)
            } else {
                Text(
                    initials, color = ink, fontWeight = FontWeight.Medium,
                    fontSize = (size.value * 0.36f).sp, letterSpacing = 0.02.em, maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SimBadge(slot: Int, modifier: Modifier = Modifier, tint: Color = LocalExtra.current.subtle) {
    if (slot <= 0) return
    Box(
        modifier
            .size(width = 14.dp, height = 16.dp)
            .border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 3.dp, bottomStart = 3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$slot", fontSize = 9.5.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

fun typeIcon(type: Int): ImageVector = when (type) {
    CallType.OUTGOING -> Icons.Rounded.CallMade
    CallType.MISSED -> Icons.Rounded.CallMissed
    CallType.REJECTED, CallType.BLOCKED -> Icons.Rounded.Block
    CallType.VOICEMAIL -> Icons.Rounded.Voicemail
    else -> Icons.Rounded.CallReceived
}

@Composable
fun typeColor(type: Int): Color = when (type) {
    CallType.OUTGOING -> LocalExtra.current.success
    CallType.MISSED, CallType.REJECTED, CallType.BLOCKED -> LocalExtra.current.missed
    else -> MaterialTheme.colorScheme.tertiary
}

/** Quiet round icon button: faint fill, hairline ring, spring press. */
@Composable
fun CircleAction(
    icon: ImageVector,
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    container: Color = LocalExtra.current.fill,
    content: Color = MaterialTheme.colorScheme.onSurface,
    bordered: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .pressable(CircleShape, 0.88f, onLongClick = onLongClick, onClick = onClick)
                .background(container)
                .then(if (bordered) Modifier.border(1.dp, LocalExtra.current.hairline, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(size * 0.42f), tint = content) }
        if (label != null) {
            Spacer(Modifier.height(7.dp))
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pill button with icon + label. The filled variant carries a soft glow of its own colour. */
@Composable
fun PillButton(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    container: Color = LocalExtra.current.fill,
    content: Color = MaterialTheme.colorScheme.onSurface,
    filled: Boolean = false,
    height: Dp = 54.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .height(height)
            .then(if (filled) Modifier.shadow(14.dp, CircleShape, ambientColor = container, spotColor = container) else Modifier)
            .pressable(CircleShape, 0.95f, onLongClick = onLongClick, onClick = onClick)
            .background(container)
            .then(if (filled) Modifier else Modifier.border(1.dp, LocalExtra.current.hairline, CircleShape))
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = content)
        Spacer(Modifier.width(9.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp, color = content, maxLines = 1)
    }
}

@Composable
fun TopIcon(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).pressable(CircleShape, 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = tint) }
}

/** Samsung-style "Make calls with" chooser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimSheet(number: String, name: String?, onDismiss: () -> Unit, onPick: (SimInfo) -> Unit) {
    val sims by Sims.sims.collectAsState()
    val haptics = rememberHaptics()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = LocalExtra.current.card,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Make calls with", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                name?.let { "$it · ${Fmt.number(number)}" } ?: Fmt.number(number),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (sims.isEmpty()) {
                Text("No SIM available", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            sims.forEachIndexed { i, sim ->
                val tint = if (sim.color != 0) Color(sim.color).copy(alpha = 1f) else AvatarHues[i % AvatarHues.size].let { if (LocalExtra.current.isDark) it.second else it.first }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .pressable(RoundedCornerShape(22.dp), 0.97f, haptic = false) { haptics.confirm(); onPick(sim) }
                        .background(LocalExtra.current.fill)
                        .border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).background(tint.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.SimCard, null, tint = tint) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("SIM ${sim.slot} · ${sim.label}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sim.number != null) {
                            Text(Fmt.number(sim.number), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Rounded.Call, null, tint = LocalExtra.current.success)
                }
            }
        }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(LocalExtra.current.card)
            .border(1.dp, LocalExtra.current.hairline, RoundedCornerShape(24.dp)),
        content = content,
    )
}

/** Hairline under a list row, inset so it starts where the text starts. */
fun Modifier.rowDivider(color: Color, show: Boolean, inset: Dp = 76.dp): Modifier =
    if (!show) this else drawBehind {
        drawRect(color, topLeft = Offset(inset.toPx(), size.height - 1.dp.toPx()), size = Size(size.width - inset.toPx() - 20.dp.toPx(), 1.dp.toPx()))
    }

/** Small tracked uppercase label that introduces a group of rows. */
@Composable
fun GroupLabel(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.subtle,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).background(LocalExtra.current.fill, CircleShape).border(1.dp, LocalExtra.current.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Search pill shared by Recents and Contacts. */
@Composable
fun SearchField(
    value: String, hint: String, modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null, onFocus: () -> Unit = {}, onChange: (String) -> Unit,
) {
    val extra = LocalExtra.current
    Row(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(50.dp)
            .background(extra.fill, CircleShape)
            .border(1.dp, extra.hairline, CircleShape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = extra.subtle)
        Spacer(Modifier.width(11.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = extra.subtle)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = hint }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocus() },
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(38.dp).pressable(CircleShape, 0.85f) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
