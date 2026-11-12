package com.sparsh.dialer.ui.home

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sparsh.dialer.AppViewModel
import com.sparsh.dialer.LocalActions
import com.sparsh.dialer.Screen
import com.sparsh.dialer.data.Contact
import com.sparsh.dialer.data.Dial
import com.sparsh.dialer.data.Prefs
import com.sparsh.dialer.ui.components.Avatar
import com.sparsh.dialer.ui.components.pressable
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlinx.coroutines.launch

private val Keys = listOf(
    '1' to "", '2' to "ABC", '3' to "DEF",
    '4' to "GHI", '5' to "JKL", '6' to "MNO",
    '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '*' to "", '0' to "+", '#' to "",
)

private fun toneFor(c: Char): Int = when (c) {
    in '0'..'9' -> ToneGenerator.TONE_DTMF_0 + (c - '0')
    '*' -> ToneGenerator.TONE_DTMF_S
    '#' -> ToneGenerator.TONE_DTMF_P
    else -> -1
}

/** Plays DTMF feedback tones; created lazily and released with the composition. */
@Composable
fun rememberKeyTone(): (Char) -> Unit {
    val holder = remember { arrayOfNulls<ToneGenerator>(1) }
    DisposableEffect(Unit) { onDispose { runCatching { holder[0]?.release() }; holder[0] = null } }
    return remember {
        { c: Char ->
            if (Prefs.tones.value) runCatching {
                val g = holder[0] ?: ToneGenerator(AudioManager.STREAM_DTMF, 55).also { holder[0] = it }
                val tone = toneFor(c)
                if (tone >= 0) g.startTone(tone, 110)
            }
        }
    }
}

@Immutable
private data class Match(val contact: Contact, val number: String)

@Composable
fun KeypadScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val actions = LocalActions.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val tone = rememberKeyTone()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val input = vm.dialInput

    val matches = remember(input, contacts) {
        if (input.length < 2) emptyList() else {
            val digits = input.filter { it.isDigit() }
            val out = ArrayList<Match>(12)
            if (digits.length == input.length || input.startsWith("+")) {
                for (c in contacts.list) {
                    val byName = c.t9.startsWith(digits) || c.t9.contains(" $digits")
                    val n = if (byName) c.numbers.first() else c.numbers.firstOrNull { it.key.contains(digits) }
                    if (n != null) out.add(Match(c, n.number))
                    if (out.size >= 12) break
                }
            }
            out
        }
    }

    fun press(c: Char) {
        haptics.key(); tone(c)
        vm.dialInput = input + c
        if (vm.dialInput == "*#06#") {
            runCatching { if (context.getSystemService(TelecomManager::class.java).handleMmi("*#06#")) vm.dialInput = "" }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = BottomBarSpace - 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Suggestions grow upward from the number, closest match nearest the thumb.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            reverseLayout = true,
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(matches, key = { it.contact.id }) { m ->
                Row(
                    Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .pressable(RoundedCornerShape(20.dp), 0.98f) { vm.dialInput = m.number.filter { it.isDigit() || it == '+' } }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(m.contact.name, Fmt.key(m.number), m.contact.photoUri, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.contact.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.number(m.number), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.NorthWest, "Use number", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Number display
        val shown = if (input.any { it == '*' || it == '#' }) input else Fmt.number(input).takeIf { input.isNotEmpty() }.orEmpty()
        val size = when {
            shown.length <= 12 -> 38.sp
            shown.length <= 16 -> 31.sp
            shown.length <= 20 -> 25.sp
            else -> 20.sp
        }
        @OptIn(ExperimentalFoundationApi::class)
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 24.dp)
                .combinedClickable(
                    interactionSource = null, indication = null,
                    onLongClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        val cleaned = clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                        if (cleaned.isNotEmpty()) { haptics.heavy(); vm.dialInput = cleaned }
                    },
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                shown, fontSize = size, fontWeight = FontWeight.Light, letterSpacing = 0.5.sp, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, overflow = TextOverflow.StartEllipsis,
            )
        }
        Box(Modifier.height(30.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                input.length >= 3 && matches.isEmpty(), enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f),
            ) {
                Row(
                    Modifier
                        .pressable(CircleShape, 0.94f) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, input),
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PersonAddAlt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Add to contacts", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        KeyGrid(
            keySize = 76.dp,
            onKey = ::press,
            onLongKey = { c ->
                when (c) {
                    '0' -> { tone('0'); vm.dialInput = input + '+'; true }
                    '1' -> if (input.isEmpty()) { Dial.voicemail(context); true } else false
                    else -> false
                }
            },
        )
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(input.isNotEmpty(), enter = scaleIn(spring(0.5f, 400f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        Modifier.size(56.dp).pressable(CircleShape, 0.85f) {
                            runCatching {
                                val key = Fmt.key(input)
                                if (contacts.byKey[key] != null || key.length >= 3) actions.open(Screen.Detail(input))
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.History, "History for this number", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val extra = LocalExtra.current
            // Tap = call with the default SIM (or redial-fill when empty). Hold = choose SIM.
            Box(
                Modifier
                    .size(74.dp)
                    .shadow(18.dp, CircleShape, ambientColor = extra.success, spotColor = extra.success)
                    .pressable(
                        CircleShape, 0.9f, haptic = false,
                        onLongClick = { if (input.isNotEmpty()) actions.call(input, contacts.byKey[Fmt.key(input)]?.name, true) },
                    ) {
                        if (input.isEmpty()) {
                            haptics.click()
                            scope.launch { vm.lastDialed()?.let { vm.dialInput = it.filter { c -> c.isDigit() || c == '+' || c == '*' || c == '#' } } }
                        } else {
                            actions.call(input, contacts.byKey[Fmt.key(input)]?.name, false)
                        }
                    }
                    .background(extra.success),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Call, "Call", Modifier.size(30.dp), tint = extra.onSuccess) }

            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(input.isNotEmpty(), enter = scaleIn(spring(0.5f, 400f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        Modifier.size(56.dp).pressable(
                            CircleShape, 0.85f, haptic = false,
                            onLongClick = { vm.dialInput = "" },
                        ) { haptics.key(); vm.dialInput = input.dropLast(1) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun KeyGrid(
    keySize: Dp,
    onKey: (Char) -> Unit,
    onLongKey: (Char) -> Boolean = { false },
    container: Color = LocalExtra.current.fill,
    content: Color = MaterialTheme.colorScheme.onSurface,
    pressedContainer: Color = MaterialTheme.colorScheme.primary,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (col in 0 until 3) {
                    val (c, letters) = Keys[row * 3 + col]
                    DialKey(c, letters, keySize, container, content, pressedContainer, { onKey(c) }, { onLongKey(c) })
                }
            }
        }
    }
}

/** A key that morphs from a circle to a squircle and flashes the brand colour while pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    c: Char, letters: String, size: Dp, container: Color, content: Color, pressedContainer: Color,
    onClick: () -> Unit, onLong: () -> Boolean,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val morph = animateFloatAsState(if (pressed) 1f else 0f, if (pressed) spring(1f, 3000f) else spring(0.45f, 300f), label = "morph")
    val bg by animateColorAsState(if (pressed) pressedContainer else container, if (pressed) spring(stiffness = 4000f) else spring(stiffness = 180f), label = "bg")
    val fg by animateColorAsState(if (pressed) Color.White else content, if (pressed) spring(stiffness = 4000f) else spring(stiffness = 180f), label = "fg")
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(width = size * 1.16f, height = size)
            .graphicsLayer {
                val m = morph.value
                val s = 1f - 0.07f * m
                scaleX = s; scaleY = s
                clip = true
                shape = RoundedCornerShape(percent = (50 - 22 * m.coerceIn(0f, 1.2f)).toInt())
            }
            .background(bg)
            .combinedClickable(
                interactionSource = source, indication = null,
                onLongClick = { if (onLong()) haptics.heavy() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$c", fontSize = if (c == '*') 34.sp else 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.Light, color = fg)
            if (letters.isNotEmpty()) {
                Text(letters, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = fg.copy(alpha = 0.45f))
            }
        }
    }
}
