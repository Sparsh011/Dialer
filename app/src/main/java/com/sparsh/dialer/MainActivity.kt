package com.sparsh.dialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sparsh.dialer.call.CallManager
import com.sparsh.dialer.call.InCallActivity
import com.sparsh.dialer.data.CallGroup
import com.sparsh.dialer.data.CallLogRepo
import com.sparsh.dialer.data.Dial
import com.sparsh.dialer.data.Prefs
import com.sparsh.dialer.data.Sims
import com.sparsh.dialer.data.granted
import com.sparsh.dialer.ui.components.SimSheet
import com.sparsh.dialer.ui.components.pressable
import com.sparsh.dialer.ui.detail.DetailScreen
import com.sparsh.dialer.ui.home.Home
import com.sparsh.dialer.ui.settings.RecordingsScreen
import com.sparsh.dialer.ui.settings.SettingsScreen
import com.sparsh.dialer.ui.theme.DialerTheme
import com.sparsh.dialer.ui.theme.LocalExtra
import com.sparsh.dialer.util.Fmt
import com.sparsh.dialer.util.rememberHaptics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

sealed interface Screen {
    data class Detail(val number: String) : Screen
    data object Settings : Screen
    data class Recordings(val key: String? = null) : Screen
}

/** App-wide actions, provided through a CompositionLocal so screens stay decoupled. */
class AppActions(
    val call: (number: String, name: String?, chooseSim: Boolean) -> Unit,
    val callRecent: (CallGroup) -> Unit,
    val message: (number: String) -> Unit,
    val open: (Screen) -> Unit,
    val back: () -> Unit,
    val requestDefault: () -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }

/**
 * Minimal back stack. Screens push in from the right over a receding, dimmed previous screen;
 * the back gesture peels the top screen away to preview what is underneath before committing.
 */
class Nav(private val scope: CoroutineScope) {
    class Entry(val screen: Screen) {
        /** 0 = off-screen right, 1 = settled. */
        val progress = Animatable(0f)
        /** 0..1 while a back gesture is in flight: shrinks and rounds the screen into a card. */
        val peel = Animatable(0f)
        val id = counter++
        var closing = false
    }

    val stack: SnapshotStateList<Entry> = mutableStateListOf()

    fun push(screen: Screen) {
        if (stack.lastOrNull()?.screen == screen) return
        stack.add(Entry(screen))
    }

    fun pop() {
        val entry = stack.lastOrNull { !it.closing } ?: return
        entry.closing = true
        scope.launch {
            entry.progress.animateTo(0f, spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.0005f))
            stack.remove(entry)
        }
    }

    companion object { private var counter = 0 }
}

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val tab = mutableIntStateOf(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            val theme by Prefs.theme.collectAsState()
            DialerTheme(theme) { AppRoot(vm, tab) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data?.scheme == "tel") {
                    vm.dialInput = data.schemeSpecificPart.orEmpty()
                    tab.intValue = 0
                } else if (intent.action == Intent.ACTION_DIAL) {
                    tab.intValue = 0
                } else {
                    tab.intValue = 1
                }
            }
        }
    }
}

private val CORE = arrayOf(
    Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
)
private val ALL = CORE + arrayOf(
    Manifest.permission.WRITE_CALL_LOG, Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO,
)

fun isDefaultDialer(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)

@Composable
private fun AppRoot(vm: AppViewModel, tab: MutableIntState) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(CORE.all { context.granted(it) }) }
    var isDefault by remember { mutableStateOf(isDefaultDialer(context)) }

    val startData = { (context.applicationContext as DialerApp).startData() }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ready = CORE.all { p -> context.granted(p) }
        startData()
    }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = isDefaultDialer(context)
        permissions.launch(ALL)
    }
    val requestDefault = {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_DIALER) && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        } else permissions.launch(ALL)
    }
    LifecycleResumeEffect(Unit) {
        ready = CORE.all { context.granted(it) }
        isDefault = isDefaultDialer(context)
        startData()
        CallLogRepo.requestSync(context)
        onPauseOrDispose { }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Crossfade(ready, label = "root") { ok ->
            if (ok) MainHost(vm, tab, isDefault, requestDefault)
            else Onboarding(isDefault, requestDefault) { permissions.launch(ALL) }
        }
    }
}

@Composable
private fun Onboarding(isDefault: Boolean, requestDefault: () -> Unit, requestPermissions: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(92.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Call, null, Modifier.size(46.dp), tint = Color.White) }
        Spacer(Modifier.height(28.dp))
        Text("Dialer", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "Fast, one-handed calling with full call history for every contact.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        listOf(
            Icons.Rounded.Phone to "Make it your default phone app to answer and place calls here",
            Icons.Rounded.History to "Call log and contacts access to build your history",
            Icons.Rounded.Lock to "Everything stays on this phone — no account, no network",
        ).forEach { (icon, text) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(LocalExtra.current.fill, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.width(14.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .pressable(CircleShape, 0.96f, onClick = if (isDefault) requestPermissions else requestDefault)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (isDefault) "Grant permissions" else "Set as default phone app",
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
        }
        if (!isDefault) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp).pressable(CircleShape, 0.96f, onClick = requestPermissions),
                contentAlignment = Alignment.Center,
            ) { Text("Not now, just grant permissions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun MainHost(vm: AppViewModel, tab: MutableIntState, isDefault: Boolean, requestDefault: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nav = remember { Nav(scope) }
    val haptics = rememberHaptics()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var simFor by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val actions = remember(requestDefault) {
        fun placeCall(number: String, name: String?, choose: Boolean, accountId: String?, confirm: Boolean = true) {
            if (number.isNotBlank()) {
                Sims.refresh(context)
                val sims = Sims.sims.value
                val sim = accountId?.let { id -> sims.firstOrNull { it.id == id } }
                if (sim == null && ((choose && sims.isNotEmpty()) || Dial.needsSimChoice(context))) {
                    simFor = number to name
                } else if (!Dial.call(context, number, sim)) {
                    Toast.makeText(context, "Couldn't place the call", Toast.LENGTH_SHORT).show()
                } else if (confirm) {
                    haptics.confirm()
                }
            }
        }
        AppActions(
            call = { number, name, choose -> placeCall(number, name, choose, null) },
            callRecent = { group ->
                scope.launch {
                    val accountId = vm.lastAccountFor(group.key) ?: group.accountId
                    placeCall(group.number, group.name, false, accountId, confirm = false)
                }
            },
            message = { number ->
                runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))) }
            },
            open = {
                // Never leave a keyboard (and a focused field) alive under a pushed screen.
                focus.clearFocus(force = true)
                keyboard?.hide()
                nav.push(it)
            },
            back = { nav.pop() },
            requestDefault = requestDefault,
        )
    }

    BackHandler(enabled = nav.stack.isEmpty() && tab.intValue == 2) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        tab.intValue = 1
    }
    PredictiveBackHandler(enabled = nav.stack.isNotEmpty()) { events ->
        val entry = nav.stack.lastOrNull { !it.closing }
        try {
            events.collect { e ->
                // Decelerated so the screen follows the finger closely at first, then resists.
                val t = 1f - (1f - e.progress) * (1f - e.progress)
                entry?.peel?.snapTo(t)
                entry?.progress?.snapTo(1f - t * 0.32f)
            }
            nav.pop()
        } catch (e: CancellationException) {
            scope.launch { entry?.progress?.animateTo(1f, spring(0.9f, 380f)) }
            scope.launch { entry?.peel?.animateTo(0f, spring(0.9f, 380f)) }
            throw e
        }
    }

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize()) {
            val stack = nav.stack
            // The screen underneath slides a quarter-width left under a scrim, then stops drawing.
            fun Modifier.behind(index: Int): Modifier = graphicsLayer {
                val p = stack.getOrNull(index + 1)?.progress?.value?.coerceIn(0f, 1f) ?: 0f
                translationX = -size.width * 0.24f * p
            }.drawWithContent {
                val p = stack.getOrNull(index + 1)?.progress?.value?.coerceIn(0f, 1f) ?: 0f
                if (p < 0.999f) {
                    drawContent()
                    if (p > 0f) drawRect(Color.Black, alpha = 0.42f * p)
                }
            }

            Box(Modifier.fillMaxSize().behind(-1)) { Home(vm, tab, isDefault, stack.isEmpty()) }

            stack.forEachIndexed { index, entry ->
                key(entry.id) {
                    LaunchedEffect(entry) {
                        entry.progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 260f, visibilityThreshold = 0.0005f))
                    }
                    val bg = MaterialTheme.colorScheme.background
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(index + 1f)
                            .behind(index)
                            .graphicsLayer {
                                val p = entry.progress.value
                                val g = entry.peel.value
                                translationX = size.width * (1f - p)
                                val s = 1f - 0.08f * g
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(1f, 0.5f)
                                val moving = p < 0.999f
                                shadowElevation = if (moving) 28.dp.toPx() else 0f
                                shape = RoundedCornerShape((g * 34f).dp)
                                clip = g > 0.001f
                            }
                            .background(bg)
                            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
                    ) {
                        when (val s = entry.screen) {
                            is Screen.Detail -> DetailScreen(s.number, vm)
                            Screen.Settings -> SettingsScreen(vm, isDefault)
                            is Screen.Recordings -> RecordingsScreen(vm, s.key)
                        }
                    }
                }
            }

            ReturnToCall(Modifier.align(Alignment.TopCenter).zIndex(50f))
        }

        simFor?.let { (number, name) ->
            SimSheet(number, name, onDismiss = { simFor = null }) { sim ->
                simFor = null
                if (!Dial.call(context, number, sim)) {
                    Toast.makeText(context, "Phone permission is needed to place calls", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun ReturnToCall(modifier: Modifier) {
    val calls by CallManager.calls.collectAsState()
    val call = CallManager.primary(calls) ?: return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.id) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val extra = LocalExtra.current
    Row(
        modifier
            .statusBarsPadding()
            .padding(top = 6.dp)
            .pressable(CircleShape, 0.94f) { InCallActivity.launch(context) }
            .background(extra.success)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Call, null, Modifier.size(16.dp), tint = extra.onSuccess)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                call.ringing -> "Incoming call"
                call.connectTime > 0 -> "Return to call · ${Fmt.clock((now - call.connectTime) / 1000)}"
                else -> "Return to call"
            },
            style = MaterialTheme.typography.labelLarge, color = extra.onSuccess,
        )
    }
}
