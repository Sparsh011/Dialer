package com.sparsh.dialer.call

import android.content.Intent
import android.graphics.RuntimeShader
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.Connection
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sparsh.dialer.MainActivity
import com.sparsh.dialer.data.AppDb
import com.sparsh.dialer.data.CallEntity
import com.sparsh.dialer.data.CallType
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.Dial
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.ui.components.Avatar
import com.sparsh.dialer.ui.components.pressable
import com.sparsh.dialer.ui.home.KeyGrid
import com.sparsh.dialer.ui.home.PopIn
import com.sparsh.dialer.ui.home.rememberKeyTone
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private enum class Phase { INCOMING, LIVE, PICK_SIM, ENDING, ENDED }

private val AnswerGreen = Color(0xFF2FD39A)
private val DeclineRed = Color(0xFFFF453A)
private val MutedText = Color(0xFFAAAAAF)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun InCallScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val calls by CallManager.calls.collectAsState()
    val ended by CallManager.ended.collectAsState()
    val audio by CallManager.audio.collectAsState()
    val contacts by ContactsRepo.state.collectAsState()
    val sims by Sims.sims.collectAsState()

    val call = CallManager.primary(calls) ?: ended
    LaunchedEffect(call == null) {
        if (call == null) {
            delay(350)
            onFinish()
        }
    }
    if (call == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val phase = when {
        call.state == Call.STATE_DISCONNECTED -> Phase.ENDED
        call.state == Call.STATE_DISCONNECTING || call.ending -> Phase.ENDING
        call.ringing && call.answering -> Phase.LIVE
        call.ringing -> Phase.INCOMING
        call.selectingAccount -> Phase.PICK_SIM
        else -> Phase.LIVE
    }
    val view = LocalView.current
    val keepScreenOn = phase != Phase.ENDED
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val contact = contacts.byKey[call.key]
    val callerName = contact?.name ?: call.callerDisplayName
    val number = Fmt.number(call.number)
    var keypad by remember(call.id) { mutableStateOf(false) }
    var typed by remember(call.id) { mutableStateOf("") }
    var replySheet by remember(call.id, phase) { mutableStateOf(false) }
    var routeSheet by remember(call.id, phase) { mutableStateOf(false) }
    var callingBack by remember(call.id) { mutableStateOf(false) }
    val finish by rememberUpdatedState(onFinish)

    LaunchedEffect(callingBack) {
        if (callingBack) {
            delay(4000)
            callingBack = false
        }
    }
    LaunchedEffect(call.id, phase, call.endedAt, callingBack) {
        if (phase == Phase.ENDED && !callingBack) {
            val remaining = if (call.endedAt > 0) {
                (call.endedAt + 3_000L - System.currentTimeMillis()).coerceIn(0L, 3_000L)
            } else 3_000L
            delay(remaining.milliseconds)
            finish()
        }
    }

    // The halo uses the accent you picked throughout the call and turns red once it ends.
    val accent = MaterialTheme.colorScheme.primary
    val over = phase == Phase.ENDED || phase == Phase.ENDING
    val colorA by animateColorAsState(
        when {
            over -> Color(0xFF8A1622)
            call.held -> Color(0xFF34343A)
            else -> lerp(Color.Black, accent, 0.72f)
        }, tween(if (over) 450 else 900), label = "Call halo",
    )
    val colorB by animateColorAsState(
        when {
            over -> Color(0xFF3A0A12)
            call.held -> Color(0xFF1D1D21)
            call.dialing && !call.incoming -> Color(0xFF7A3A22)
            else -> Color(0xFF4A3AA8)
        }, tween(if (over) 450 else 900), label = "Call halo accent",
    )
    // Lift the controls into thumb reach while ringing; settle lower once the call is live.
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val bottomLift by animateDpAsState(
        if (phase == Phase.INCOMING) screenH * 0.16f else 20.dp,
        spring(dampingRatio = 0.85f, stiffness = 260f), label = "Controls lift",
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Aurora(colorA, colorB)
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!keypad || phase != Phase.LIVE) {
                        Box(contentAlignment = Alignment.Center) {
                            if (phase == Phase.INCOMING || call.dialing) PulseRings(colorA)
                            Avatar(callerName, call.key.ifEmpty { "?" }, contact?.photoUri, 80.dp)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        callerName ?: number,
                        style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White,
                    )
                    if (callerName != null && call.number.isNotBlank() && callerName != number) {
                        Spacer(Modifier.height(4.dp))
                        Text(number, style = MaterialTheme.typography.bodyLarge, color = MutedText)
                    }
                    if (call.incoming && call.number.isNotBlank()) {
                        val verification = when (call.callerVerification) {
                            Connection.VERIFICATION_STATUS_PASSED -> "Number verified by carrier"
                            Connection.VERIFICATION_STATUS_FAILED -> "Caller number may be spoofed"
                            else -> null
                        }
                        if (verification != null) {
                            Spacer(Modifier.height(6.dp))
                            val verified = call.callerVerification == Connection.VERIFICATION_STATUS_PASSED
                            Text(
                                verification, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                color = if (verified) MutedText else Color(0xFFFF9D98),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    CallStatus(call, phase, callingBack, if (sims.size > 1) Sims.slotFor(call.accountId) else 0)
                    // Hidden while the in-call keypad is open so the digits have room.
                    if (call.key.isNotEmpty() && !(keypad && phase == Phase.LIVE)) {
                        LastCallCard(call)
                    }
                }
            }

            val other = calls.firstOrNull { it.id != call.id && !it.ended }
            if (other != null) {
                OtherCallBanner(other, CallManager.nameFor(other), call)
                Spacer(Modifier.height(16.dp))
            }
            SharedTransitionLayout(Modifier.fillMaxWidth()) {
                AnimatedContent(
                    phase,
                    transitionSpec = {
                        (fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(140)))
                            .using(SizeTransform(clip = false))
                    },
                    contentAlignment = Alignment.BottomCenter,
                    label = "Call controls",
                ) { shownPhase ->
                    val endButton = Modifier.sharedBounds(
                        rememberSharedContentState("call button"),
                        this@AnimatedContent,
                        boundsTransform = { _, _ -> spring(0.7f, 280f) },
                    )
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (shownPhase) {
                            Phase.INCOMING -> {
                                if (call.number.isNotBlank()) {
                                    Row(
                                        Modifier.pressable(CircleShape, 0.94f) { replySheet = true }
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 22.dp, vertical = 13.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.ChatBubbleOutline, null, Modifier.size(18.dp), tint = Color.White)
                                        Spacer(Modifier.width(9.dp))
                                        Text("Message", style = MaterialTheme.typography.labelLarge, color = Color.White)
                                    }
                                    Spacer(Modifier.height(26.dp))
                                }
                                key(call.id) {
                                    AnswerSlider(
                                        handleModifier = endButton,
                                        onAnswer = { CallManager.answer(call.id) },
                                        onDecline = { CallManager.reject(call.id) },
                                    )
                                }
                            }

                            Phase.LIVE -> {
                                AnimatedContent(
                                    keypad,
                                    transitionSpec = {
                                        (scaleIn(spring(0.7f, 340f), initialScale = 0.85f) + fadeIn()) togetherWith
                                            (scaleOut(targetScale = 0.9f) + fadeOut(tween(100)))
                                    },
                                    contentAlignment = Alignment.Center,
                                    label = "Call keypad",
                                ) { showPad ->
                                    if (showPad) {
                                        val tone = rememberKeyTone()
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                typed.ifEmpty { " " }, style = MaterialTheme.typography.headlineSmall, color = Color.White,
                                                maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.padding(bottom = 12.dp),
                                            )
                                            KeyGrid(
                                                keySize = 62.dp,
                                                onKey = { digit -> haptics.key(); tone(digit); typed += digit; CallManager.dtmf(call.id, digit) },
                                                container = Color.White.copy(alpha = 0.10f), content = Color.White,
                                            )
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            Row(Modifier.fillMaxWidth()) {
                                                PopIn(0, Modifier.weight(1f)) {
                                                    CallControl(
                                                        Icons.Rounded.PersonAddAlt, "Add call",
                                                        enabled = call.active || call.held,
                                                    ) {
                                                        haptics.click()
                                                        runCatching {
                                                            context.startActivity(
                                                                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_DIAL)
                                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                            )
                                                        }
                                                    }
                                                }
                                                PopIn(1, Modifier.weight(1f)) {
                                                    CallControl(
                                                        if (call.held) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                                        if (call.held) "Resume" else "Hold",
                                                        active = call.held, enabled = call.held || (call.active && call.canHold),
                                                    ) {
                                                        haptics.toggle(!call.held)
                                                        CallManager.hold(call.id, !call.held)
                                                    }
                                                }
                                                PopIn(2, Modifier.weight(1f)) {
                                                    CallControl(
                                                        if (audio.bluetooth) Icons.Rounded.BluetoothAudio else Icons.Rounded.Bluetooth,
                                                        if (audio.bluetooth) audio.bluetoothName ?: "Bluetooth" else "Bluetooth",
                                                        active = audio.bluetooth, enabled = audio.hasBluetooth, activeColor = Color(0xFF4C9BFF),
                                                    ) {
                                                        if (audio.devices.size > 1) {
                                                            haptics.click()
                                                            routeSheet = true
                                                        } else {
                                                            haptics.toggle(!audio.bluetooth)
                                                            CallManager.setRoute(if (audio.bluetooth) audio.phoneRoute else CallAudioState.ROUTE_BLUETOOTH)
                                                        }
                                                    }
                                                }
                                            }
                                            Row(Modifier.fillMaxWidth()) {
                                                PopIn(3, Modifier.weight(1f)) {
                                                    CallControl(Icons.AutoMirrored.Rounded.VolumeUp, "Speaker", active = audio.speaker) {
                                                        haptics.toggle(!audio.speaker)
                                                        CallManager.setRoute(if (audio.speaker) audio.phoneRoute else CallAudioState.ROUTE_SPEAKER)
                                                    }
                                                }
                                                PopIn(4, Modifier.weight(1f)) {
                                                    CallControl(
                                                        if (audio.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                                        if (audio.muted) "Muted" else "Mute", active = audio.muted, activeColor = DeclineRed,
                                                    ) {
                                                        haptics.toggle(!audio.muted)
                                                        CallManager.setMuted(!audio.muted)
                                                    }
                                                }
                                                PopIn(5, Modifier.weight(1f)) {
                                                    CallControl(Icons.Rounded.Dialpad, "Keypad") { haptics.click(); keypad = true }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(30.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    HangupButton(endButton, "End call") { haptics.reject(); CallManager.hangup(call.id) }
                                    if (keypad) {
                                        CallSideButton(
                                            Icons.Rounded.KeyboardArrowDown, "Hide keypad",
                                            Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
                                        ) { keypad = false }
                                    }
                                }
                            }

                            Phase.PICK_SIM -> {
                                Column(Modifier.heightIn(max = 216.dp).verticalScroll(rememberScrollState())) {
                                    call.accounts.forEachIndexed { index, handle ->
                                        val sim = sims.firstOrNull { it.handle == handle }
                                        PopIn(index) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                                    .pressable(RoundedCornerShape(24.dp), 0.97f, haptic = false) {
                                                        haptics.confirm()
                                                        CallManager.selectAccount(call.id, handle)
                                                    }
                                                    .background(Color.White.copy(alpha = 0.12f)).padding(18.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(Icons.Rounded.SimCard, null, tint = Color.White)
                                                Spacer(Modifier.width(14.dp))
                                                Text(
                                                    sim?.let { "SIM ${it.slot} · ${it.label}" } ?: "SIM ${index + 1}",
                                                    style = MaterialTheme.typography.titleMedium, color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                HangupButton(endButton, "Cancel") { haptics.reject(); CallManager.hangup(call.id) }
                            }

                            Phase.ENDING -> Spacer(Modifier.height(74.dp))

                            Phase.ENDED -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    if (call.canCallBack && calls.none { !it.ended }) {
                                        EndedCallAction(Icons.Rounded.Call, "Call back", !callingBack, AnswerGreen, Color.Black) {
                                            Sims.refresh(context)
                                            val sim = Sims.sims.value.firstOrNull { it.id == call.accountId }
                                            callingBack = Dial.call(context, call.number, sim)
                                            if (callingBack) haptics.confirm()
                                            else Toast.makeText(context, "Couldn't place the call", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    EndedCallAction(Icons.Rounded.Check, "Done", !callingBack, Color(0xFF252528), Color.White, onClick = onFinish)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(bottomLift))
        }
    }

    if (replySheet) {
        ModalBottomSheet(onDismissRequest = { replySheet = false }) {
            Text("Decline with a message", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            listOf("Can't talk right now. What's up?", "I'll call you right back.", "I'll call you later.", "In a meeting — text me?").forEach { message ->
                Text(
                    message, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().pressable { replySheet = false; CallManager.reject(call.id, message) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (routeSheet) {
        ModalBottomSheet(onDismissRequest = { routeSheet = false }) {
            Text("Call audio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            AudioOption(Icons.Rounded.Phone, if (audio.wired) "Wired headset" else "Phone", audio.route == audio.phoneRoute) {
                CallManager.setRoute(audio.phoneRoute)
                routeSheet = false
            }
            AudioOption(Icons.AutoMirrored.Rounded.VolumeUp, "Speaker", audio.speaker) {
                CallManager.setRoute(CallAudioState.ROUTE_SPEAKER)
                routeSheet = false
            }
            audio.devices.forEach { device ->
                AudioOption(Icons.Rounded.Bluetooth, device.name, audio.bluetooth && audio.activeAddress == device.address) {
                    CallManager.setBluetooth(device)
                    routeSheet = false
                }
            }
            if (audio.hasBluetooth && audio.devices.isEmpty()) {
                AudioOption(Icons.Rounded.Bluetooth, "Bluetooth", audio.bluetooth) {
                    CallManager.setRoute(CallAudioState.ROUTE_BLUETOOTH)
                    routeSheet = false
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CallStatus(call: CallUi, phase: Phase, callingBack: Boolean, simSlot: Int) {
    val endedAt = remember(call.id, phase) { System.currentTimeMillis() }
    val now by produceState(System.currentTimeMillis(), call.id, call.active) {
        while (call.active) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }
    val status = when (phase) {
        Phase.INCOMING -> "Incoming call"
        Phase.PICK_SIM -> "Choose a SIM"
        Phase.ENDING -> "Ending call…"
        Phase.ENDED -> when {
            callingBack -> "Calling…"
            call.connectTime > 0 -> "Call ended · ${Fmt.clock(((call.endedAt.takeIf { it > 0 } ?: endedAt) - call.connectTime) / 1000)}"
            else -> "Call ended"
        }
        Phase.LIVE -> when {
            call.held -> "On hold"
            call.answering -> "Connecting…"
            call.active && call.connectTime > 0 -> Fmt.clock((now - call.connectTime) / 1000)
            call.active -> "Call connected"
            else -> "Calling…"
        }
    }
    val text = buildList {
        add(status)
        if (simSlot > 0 && phase != Phase.ENDED) add("SIM $simSlot")
        if (phase == Phase.INCOMING || phase == Phase.LIVE) {
            if (call.wifi) add("Wi-Fi call")
            if (call.hd) add("HD call")
        }
    }.joinToString(" · ")
    Text(
        text, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
        textAlign = TextAlign.Center, color = MutedText,
    )
}

/**
 * "When did we last talk?" Read once from Room when the call appears — the system only logs
 * this call after it ends, so the row returned is always the previous one.
 */
@Composable
private fun LastCallCard(call: CallUi) {
    val context = LocalContext.current
    // null = still loading, Optional-style wrapper so "no previous call" is distinguishable.
    val last by produceState<Result<CallEntity?>?>(null, call.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppDb.get(context).calls().last(call.key) }
        }
    }
    val loaded = last
    AnimatedVisibility(
        visible = loaded != null,
        enter = fadeIn(tween(220)) + scaleIn(spring(0.7f, 320f), initialScale = 0.92f) + expandVertically(spring(0.9f, 400f)),
    ) {
        val previous = loaded?.getOrNull()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(20.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(20.dp))
                    .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MutedText)
                Spacer(Modifier.width(10.dp))
                if (previous == null) {
                    Text("First call with this number", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                } else {
                    Column {
                        Text(
                            "Last call · ${Fmt.relative(previous.date)}",
                            style = MaterialTheme.typography.labelLarge, color = Color.White,
                        )
                        val d = Fmt.duration(previous.duration)
                        val missed = previous.type == CallType.MISSED || previous.type == CallType.REJECTED
                        Text(
                            CallType.label(previous.type).removeSuffix(" call") + (if (d.isNotEmpty() && !missed) " · $d" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (missed) Color(0xFFFF9D98) else MutedText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    activeColor: Color = Color.White,
    onClick: () -> Unit,
) {
    val morph = animateFloatAsState(if (active) 1f else 0f, spring(0.5f, 340f), label = "Control shape")
    val background by animateColorAsState(if (active) activeColor else Color.White.copy(alpha = 0.09f), label = "Control fill")
    val ring by animateColorAsState(if (active) Color.Transparent else Color.White.copy(alpha = 0.10f), label = "Control outline")
    val foreground by animateColorAsState(if (active && activeColor == Color.White) Color.Black else Color.White, label = "Control icon")
    val alpha = animateFloatAsState(if (enabled) 1f else 0.35f, label = "Control availability")

    Column(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(74.dp).graphicsLayer {
                clip = true
                shape = RoundedCornerShape(percent = (50 - 20 * morph.value.coerceIn(0f, 1.3f)).toInt().coerceIn(15, 50))
                val scale = 1f + 0.04f * morph.value
                scaleX = scale
                scaleY = scale
            }.background(background).drawBehind {
                val radius = size.minDimension * (50 - 20 * morph.value.coerceIn(0f, 1.3f)).coerceIn(15f, 50f) / 100f
                drawRoundRect(ring, cornerRadius = CornerRadius(radius), style = Stroke(2.dp.toPx()))
            }.pressable(pressedScale = 0.88f, enabled = enabled, haptic = false, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(27.dp), tint = foreground) }
        Spacer(Modifier.height(9.dp))
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = MutedText,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp),
        )
    }
}

@Composable
private fun HangupButton(modifier: Modifier, label: String, onClick: () -> Unit) {
    Box(
        modifier.size(width = 150.dp, height = 74.dp)
            .pressable(CircleShape, 0.9f, haptic = false, onClick = onClick)
            .background(DeclineRed),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.CallEnd, label, Modifier.size(34.dp), tint = Color.White) }
}

@Composable
private fun CallSideButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(56.dp).graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .pressable(CircleShape, 0.85f, enabled = enabled, onClick = onClick)
            .background(Color.White.copy(alpha = 0.09f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(22.dp), tint = Color.White) }
}

@Composable
private fun EndedCallAction(
    icon: ImageVector, label: String, enabled: Boolean, background: Color, foreground: Color, onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick, enabled = enabled, modifier = Modifier.size(58.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = background, contentColor = foreground),
        ) {
            Icon(icon, label, Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun AudioOption(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(Icons.Rounded.Check, "Selected", Modifier.size(20.dp))
    }
}

@Composable
private fun OtherCallBanner(other: CallUi, name: String, primary: CallUi) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF18181B), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when {
                other.held -> "On hold"
                other.active -> "Ongoing"
                other.ringing -> "Incoming"
                else -> "Connecting"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
        if (other.held && !primary.ringing) {
            IconButton(onClick = { CallManager.hold(other.id, false) }) {
                Icon(Icons.Rounded.SwapCalls, "Swap calls")
            }
            if (primary.canMerge || other.canMerge) {
                IconButton(onClick = { CallManager.merge(primary.id) }) { Icon(Icons.AutoMirrored.Rounded.CallMerge, "Merge calls") }
            }
        }
        IconButton(onClick = { CallManager.hangup(other.id) }) { Icon(Icons.Rounded.CallEnd, "End other call", tint = DeclineRed) }
    }
}

/**
 * Drag the handle toward Answer or Decline.
 * A tap does nothing (it just nudges), so pocket touches can't pick up or reject a call.
 */
@Composable
private fun AnswerSlider(handleModifier: Modifier, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val offset = remember { Animatable(0f) }
    var maxPx by remember { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var armed by remember { mutableIntStateOf(0) }
    val answer by rememberUpdatedState(onAnswer)
    val decline by rememberUpdatedState(onDecline)
    val handleW = 108.dp
    val handlePx = with(density) { handleW.toPx() }
    val padPx = with(density) { 8.dp.toPx() }

    LaunchedEffect(dragging, done) {
        if (!dragging && !done) {
            delay(1200)
            while (true) {
                offset.animateTo(maxPx * 0.16f, spring(0.5f, 180f))
                offset.animateTo(0f, spring(0.32f, 200f))
                delay(1800)
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .onSizeChanged { maxPx = ((it.width - handlePx) / 2f - padPx).coerceAtLeast(1f) }
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.1f), cornerRadius = r, style = Stroke(1.dp.toPx()))
                val f = (offset.value / maxPx).coerceIn(-1f, 1f)
                if (f > 0f) drawRoundRect(AnswerGreen, cornerRadius = r, alpha = f * 0.6f)
                else if (f < 0f) drawRoundRect(DeclineRed, cornerRadius = r, alpha = -f * 0.6f)
            },
    ) {
        Text(
            "Decline", style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 30.dp)
                .graphicsLayer { alpha = (1f - (offset.value / maxPx).coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f) },
        )
        Text(
            "Answer", style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 30.dp)
                .graphicsLayer { alpha = (1f - (-offset.value / maxPx).coerceIn(0f, 1f) * 2f).coerceIn(0f, 1f) },
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(handleModifier)
                .size(handleW, 72.dp)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Answer call") {
                            if (!done) { done = true; answer(); true } else false
                        },
                        CustomAccessibilityAction("Decline call") {
                            if (!done) { done = true; decline(); true } else false
                        },
                    )
                }
                .graphicsLayer {
                    val f = (kotlin.math.abs(offset.value) / maxPx).coerceIn(0f, 1f)
                    val s = 1f + 0.06f * f
                    scaleX = s; scaleY = s
                }
                .background(Color.White, CircleShape)
                .pointerInput(direction) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; haptics.dragStart() },
                        onDragCancel = {
                            dragging = false
                            armed = 0
                            scope.launch { offset.animateTo(0f, spring(0.5f, 380f)) }
                        },
                        onDragEnd = {
                            dragging = false
                            when (armed) {
                                // Act the instant the finger lifts; the snap animation just plays alongside.
                                1 -> {
                                    done = true; haptics.confirm()
                                    answer()
                                    scope.launch { offset.animateTo(maxPx, spring(1f, 900f, visibilityThreshold = 0.5f)) }
                                }
                                -1 -> {
                                    done = true; haptics.reject()
                                    decline()
                                    scope.launch { offset.animateTo(-maxPx, spring(1f, 900f, visibilityThreshold = 0.5f)) }
                                }
                                else -> {
                                    haptics.dragEnd()
                                    scope.launch { offset.animateTo(0f, spring(0.45f, 380f)) }
                                }
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            if (!done) {
                                val next = (offset.value + delta * direction).coerceIn(-maxPx, maxPx)
                                scope.launch { offset.snapTo(next) }
                                val f = next / maxPx
                                val a = if (f > 0.8f) 1 else if (f < -0.8f) -1 else 0
                                if (a != armed) { armed = a; if (a != 0) haptics.select() else haptics.tick() }
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Call, null, tint = AnswerGreen,
                modifier = Modifier.size(32.dp).graphicsLayer { alpha = (1f + (offset.value / maxPx).coerceIn(-1f, 0f) * 2.2f).coerceIn(0f, 1f) },
            )
            Icon(
                Icons.Rounded.CallEnd, null, tint = DeclineRed,
                modifier = Modifier.size(32.dp).graphicsLayer {
                    val f = (-offset.value / maxPx).coerceIn(0f, 1f)
                    alpha = (f * 2.2f).coerceIn(0f, 1f)
                    rotationZ = (1f - f) * -135f
                },
            )
        }
    }
}

@Composable
private fun PulseRings(color: Color) {
    val progress = rememberInfiniteTransition(label = "Caller pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "Pulse progress",
    )
    Box(
        Modifier.size(144.dp).drawBehind {
            val start = 40.dp.toPx()
            val end = size.minDimension / 2f - 2.dp.toPx()
            for (index in 0 until 3) {
                val fraction = (progress.value + index / 3f) % 1f
                val eased = 1f - (1f - fraction) * (1f - fraction)
                drawCircle(
                    color, radius = start + eased * (end - start),
                    alpha = (1f - fraction) * 0.36f, style = Stroke((2.5f - 1.5f * fraction).dp.toPx()),
                )
            }
        },
    )
}

private const val AURORA = """
uniform float2 uSize;
uniform float uTime;
layout(color) uniform half4 uA;
layout(color) uniform half4 uB;

half4 main(float2 fc) {
    float2 uv = fc / uSize;
    float2 p = float2(uv.x, uv.y * uSize.y / uSize.x);
    float t = uTime * 0.22;
    float2 c1 = float2(0.30 + 0.28 * sin(t * 1.1), 0.34 + 0.16 * cos(t * 0.9));
    float2 c2 = float2(0.72 + 0.24 * cos(t * 0.7 + 1.3), 0.78 + 0.20 * sin(t * 1.3));
    float2 c3 = float2(0.50 + 0.36 * sin(t * 0.5 + 2.1), 1.25 + 0.18 * cos(t * 0.8));
    float g1 = exp(-dot(p - c1, p - c1) * 3.2);
    float g2 = exp(-dot(p - c2, p - c2) * 2.6);
    float g3 = exp(-dot(p - c3, p - c3) * 3.8);
    half3 col = uA.rgb * half(g1 * 0.30) + uB.rgb * half(g2 * 0.17) + uA.rgb * half(g3 * 0.08);
    col *= half(1.0 - smoothstep(0.12, 0.92, uv.y));
    float noise = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453);
    col += half3(half((noise - 0.5) / 255.0));
    return half4(col, 1.0);
}
"""

@Composable
private fun Aurora(a: Color, b: Color) {
    val shader = remember { RuntimeShader(AURORA) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val time = produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    Box(
        Modifier.fillMaxSize().drawBehind {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", time.value)
            shader.setColorUniform("uA", a.toArgb())
            shader.setColorUniform("uB", b.toArgb())
            drawRect(brush)
        },
    )
}
